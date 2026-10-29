import com.google.gson.GsonBuilder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.Body;
import soot.Scene;
import soot.SootClass;
import soot.SootMethod;
import soot.SootMethodRef;
import soot.Unit;
import soot.jimple.InvokeExpr;
import soot.jimple.Stmt;
import utils.InfoflowAnalyzer;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;

/**
 * Builds the SQLite database the explorer reads through DB_PATH.
 *
 * ADGE's repository does not include the tool that produced its databases (tables ResourceIdToMethodMap and Methods
 * with the columns tainted and icc). This rebuilds what ADGE's own code can compute:
 *  - ResourceIdToMethodMap: widget resource id -> listener methods, from InfoflowAnalyzer.analysisGUIByBinding and
 *    analysisGUIByFind (the analysis Main.java used before it switched to the database);
 *  - Methods.icc = 1 when a listener reaches an inter-component call (startActivity, startService, sendBroadcast, ...)
 *    within MAX_DEPTH calls of the app's own code;
 *  - Methods.tainted = 1 when the listener can reach a TARGET method through the app's call graph. Targets are passed
 *    in a file (one "class#method#paramCount" per line); per the ADGE paper this is the tainted ICFG, where "the
 *    target method [is] the sink" and "all nodes that can reach the sink [are] tainted". With no targets file, tainted
 *    stays 0 (the unguided build).
 *
 * Usage: BuildDB <apk> <out.db> <android platforms dir> [stats.json] [deadline seconds] [targets file | -]
 *        BuildDB --empty <out.db> [stats.json]            schema only, for an unguided run
 */
public class BuildDB {
    private static final Logger logger = LoggerFactory.getLogger(BuildDB.class);
    private static final int MAX_DEPTH = 3;
    private static final Set<String> ICC_METHODS = new HashSet<>(Arrays.asList(
            "startActivity", "startActivityForResult", "startActivities", "startActivityIfNeeded", "startActivityFromChild",
            "startActivityFromFragment", "startNextMatchingActivity", "startIntentSender", "startIntentSenderForResult",
            "startService", "startForegroundService", "bindService", "sendBroadcast", "sendOrderedBroadcast",
            "sendStickyBroadcast", "sendBroadcastAsUser"));
    private static final String[] LIBRARY_PREFIXES = {"java.", "javax.", "android.", "androidx.", "kotlin.", "kotlinx.",
            "com.google.android.", "dalvik.", "sun.", "org.json.", "org.xml.", "org.w3c."};

    public static void main(String[] args) throws Exception {
        org.apache.log4j.LogManager.getRootLogger().setLevel(org.apache.log4j.Level.INFO);
        Map<String, Object> stats = new LinkedHashMap<>();
        long t0 = System.currentTimeMillis();
        if (args.length >= 2 && args[0].equals("--empty")) {
            stats.put("mode", "empty");
            writeDb(args[1], new TreeMap<>(), new HashSet<>(), new HashSet<>());
            stats.put("mappings", 0);
            writeStats(args.length > 2 ? args[2] : null, stats);
            return;
        }
        if (args.length < 3) {
            System.err.println("usage: BuildDB <apk> <out.db> <android platforms dir> [stats.json] [deadline seconds] [targets file | -]");
            System.exit(2);
        }
        String apk = args[0], out = args[1], platforms = args[2];
        String statsPath = args.length > 3 ? args[3] : null;
        int deadline = args.length > 4 ? Integer.parseInt(args[4]) : 1200;
        String targetsPath = args.length > 5 && !args[5].equals("-") ? args[5] : null;
        stats.put("mode", "analysis");

        // targets: "fqcn#methodName#paramCount" per line (the methods Monkey could not reach)
        Set<String> targetKeys = targetsPath == null ? Collections.emptySet() : loadTargets(targetsPath);
        stats.put("targets_mode", targetsPath == null ? "none" : "monkey-missed");
        stats.put("targets_total", targetKeys.size());

        InfoflowAnalyzer analyzer = InfoflowAnalyzer.getInstance();
        analyzer.setPlatformDir(platforms);
        analyzer.setAppPath(apk);
        analyzer.init();
        analyzer.initializeSoot();
        String resourcePackage = analyzer.getAppName();
        stats.put("resource_package", resourcePackage);
        stats.put("soot_seconds", (System.currentTimeMillis() - t0) / 1000);

        boolean[] ok = new boolean[2];
        Thread worker = new Thread(() -> {
            try {
                analyzer.analysisGUIByBinding();
                ok[0] = true;
            } catch (Throwable t) {
                logger.error("analysisGUIByBinding failed", t);
            }
            try {
                analyzer.analysisGUIByFind();
                ok[1] = true;
            } catch (Throwable t) {
                logger.error("analysisGUIByFind failed", t);
            }
        }, "gui-analysis");
        worker.setDaemon(true);
        worker.start();
        worker.join(deadline * 1000L);
        boolean complete = !worker.isAlive();
        if (!complete) {
            logger.warn("GUI analysis still running after " + deadline + " s; writing what it found so far");
        }
        stats.put("complete", complete);
        stats.put("binding_ok", ok[0]);
        stats.put("find_ok", ok[1]);

        // resource ids as the device reports them: "<package in resources.arsc>:id/<name>"
        Map<String, Set<String>> map = new TreeMap<>();
        for (Map.Entry<String, HashSet<String>> e : snapshot(analyzer.getResourceIdToMethodMap()).entrySet()) {
            String key = normalize(e.getKey(), resourcePackage);
            if (key == null || e.getValue() == null) {
                continue;
            }
            for (String m : e.getValue()) {
                if (m != null && !m.isEmpty()) {
                    map.computeIfAbsent(key, k -> new TreeSet<>()).add(m);
                }
            }
        }
        Set<String> methods = new TreeSet<>();
        for (Set<String> ms : map.values()) {
            methods.addAll(ms);
        }
        Set<String> icc = new HashSet<>();
        for (String m : methods) {
            try {
                SootMethod sm = Scene.v().grabMethod(m);
                if (sm != null && reachesIcc(sm)) {
                    icc.add(m);
                }
            } catch (Throwable t) {
                logger.debug("icc check failed for " + m + ": " + t);
            }
        }

        // tainted ICFG: signatures of app methods that can reach a target method through the app call graph
        Set<String> canReachTarget = Collections.emptySet();
        if (!targetKeys.isEmpty()) {
            try {
                canReachTarget = buildCanReachTarget(targetKeys, stats);
            } catch (Throwable t) {
                logger.error("tainted-ICFG construction failed", t);
            }
        }
        int tainted = 0;
        for (String m : methods) {
            if (canReachTarget.contains(m)) {
                tainted++;
            }
        }

        writeDb(out, map, icc, canReachTarget);
        stats.put("resource_ids", map.size());
        stats.put("mappings", map.values().stream().mapToInt(Set::size).sum());
        stats.put("methods", methods.size());
        stats.put("icc_methods", icc.size());
        stats.put("tainted_methods", tainted);
        stats.put("seconds", (System.currentTimeMillis() - t0) / 1000);
        writeStats(statsPath, stats);
        logger.info("database written: " + stats);
        System.exit(0);
    }

    private static Set<String> loadTargets(String path) {
        Set<String> keys = new HashSet<>();
        try {
            for (String line : Files.readAllLines(Paths.get(path), StandardCharsets.UTF_8)) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    keys.add(line);
                }
            }
        } catch (IOException e) {
            logger.error("cannot read targets file " + path + ": " + e);
        }
        return keys;
    }

    private static String keyOf(SootMethod sm) {
        return sm.getDeclaringClass().getName() + "#" + sm.getName() + "#" + sm.getParameterCount();
    }

    /**
     * All app-method signatures that can reach a target method, by backward BFS over the app call graph
     * (edge caller -> callee, reversed). The set includes the target methods themselves.
     */
    private static Set<String> buildCanReachTarget(Set<String> targetKeys, Map<String, Object> stats) {
        Map<String, List<String>> callers = new HashMap<>();   // callee signature -> caller signatures
        Set<String> targetSigs = new HashSet<>();
        int appMethods = 0;
        for (SootClass sc : new ArrayList<>(Scene.v().getClasses())) {
            if (isLibrary(sc.getName())) {
                continue;
            }
            for (SootMethod caller : new ArrayList<>(sc.getMethods())) {
                if (keyOf(caller) != null && targetKeys.contains(keyOf(caller))) {
                    targetSigs.add(caller.getSignature());
                }
                if (!caller.isConcrete()) {
                    continue;
                }
                appMethods++;
                Body body;
                try {
                    body = caller.retrieveActiveBody();
                } catch (Throwable t) {
                    continue;
                }
                for (Unit u : body.getUnits()) {
                    if (!(u instanceof Stmt) || !((Stmt) u).containsInvokeExpr()) {
                        continue;
                    }
                    SootMethodRef ref = ((Stmt) u).getInvokeExpr().getMethodRef();
                    if (isLibrary(ref.getDeclaringClass().getName())) {
                        continue;
                    }
                    SootMethod callee;
                    try {
                        callee = ref.tryResolve();
                    } catch (Throwable t) {
                        callee = null;
                    }
                    if (callee == null) {
                        continue;
                    }
                    if (targetKeys.contains(keyOf(callee))) {
                        targetSigs.add(callee.getSignature());
                    }
                    callers.computeIfAbsent(callee.getSignature(), k -> new ArrayList<>()).add(caller.getSignature());
                }
            }
        }
        // backward BFS from targets over caller edges -> everything that can reach a target
        Set<String> reach = new HashSet<>(targetSigs);
        Deque<String> queue = new ArrayDeque<>(targetSigs);
        while (!queue.isEmpty()) {
            for (String caller : callers.getOrDefault(queue.poll(), Collections.emptyList())) {
                if (reach.add(caller)) {
                    queue.add(caller);
                }
            }
        }
        stats.put("app_methods_scanned", appMethods);
        stats.put("targets_matched", targetSigs.size());
        stats.put("reach_nodes", reach.size());
        return reach;
    }

    private static Map<String, HashSet<String>> snapshot(Map<String, HashSet<String>> live) {
        for (int attempt = 0; attempt < 50; attempt++) {
            try {
                Map<String, HashSet<String>> copy = new HashMap<>();
                for (Map.Entry<String, HashSet<String>> e : new ArrayList<>(live.entrySet())) {
                    copy.put(e.getKey(), e.getValue() == null ? null : new HashSet<>(e.getValue()));
                }
                return copy;
            } catch (ConcurrentModificationException e) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                }
            }
        }
        return new HashMap<>();
    }

    private static String normalize(String key, String resourcePackage) {
        if (key == null) {
            return null;
        }
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            return null;
        }
        String prefix = key.substring(0, colon);
        // the analysis builds some ids from the R class's Java package; the device uses the resource table's package
        return prefix.equals("android") ? key : resourcePackage + key.substring(colon);
    }

    private static boolean isLibrary(String className) {
        for (String p : LIBRARY_PREFIXES) {
            if (className.startsWith(p)) {
                return true;
            }
        }
        return false;
    }

    private static boolean reachesIcc(SootMethod start) {
        Map<SootMethod, Integer> depth = new HashMap<>();
        Deque<SootMethod> queue = new ArrayDeque<>();
        depth.put(start, 0);
        queue.add(start);
        while (!queue.isEmpty()) {
            SootMethod m = queue.poll();
            int d = depth.get(m);
            if (!m.isConcrete()) {
                continue;
            }
            Body body;
            try {
                body = m.retrieveActiveBody();
            } catch (Throwable t) {
                continue;
            }
            for (Unit u : body.getUnits()) {
                Stmt s = (Stmt) u;
                if (!s.containsInvokeExpr()) {
                    continue;
                }
                InvokeExpr ie = s.getInvokeExpr();
                SootMethodRef ref = ie.getMethodRef();
                String name = ref.getName();
                SootClass declaring = ref.getDeclaringClass();
                if (ICC_METHODS.contains(name)
                        || (name.equals("launch") && declaring.getName().startsWith("androidx.activity.result."))) {
                    return true;
                }
                if (d < MAX_DEPTH && !isLibrary(declaring.getName())) {
                    SootMethod callee = ref.tryResolve();
                    if (callee != null && !depth.containsKey(callee)) {
                        depth.put(callee, d + 1);
                        queue.add(callee);
                    }
                }
            }
        }
        return false;
    }

    private static void writeDb(String path, Map<String, Set<String>> map, Set<String> icc, Set<String> tainted) throws SQLException {
        new File(path).delete();
        try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + path)) {
            c.setAutoCommit(false);
            try (Statement st = c.createStatement()) {
                st.executeUpdate("CREATE TABLE ResourceIdToMethodMap (id INTEGER PRIMARY KEY, project_id INTEGER, resource_id TEXT, method_name TEXT)");
                st.executeUpdate("CREATE TABLE Methods (id INTEGER PRIMARY KEY, name TEXT, tainted INTEGER DEFAULT 0, icc INTEGER DEFAULT 0)");
                st.executeUpdate("CREATE INDEX idx_methods_name ON Methods(name)");
                // read by DBHelper.getSinks(), which the explorer does not call; kept so the schema is complete
                st.executeUpdate("CREATE TABLE TaintNodes (hash TEXT, flag INTEGER)");
                st.executeUpdate("CREATE TABLE Units (hash TEXT, toString TEXT)");
            }
            Set<String> methods = new TreeSet<>();
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO ResourceIdToMethodMap (project_id, resource_id, method_name) VALUES (1, ?, ?)")) {
                for (Map.Entry<String, Set<String>> e : map.entrySet()) {
                    for (String m : e.getValue()) {
                        ps.setString(1, e.getKey());
                        ps.setString(2, m);
                        ps.addBatch();
                        methods.add(m);
                    }
                }
                ps.executeBatch();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO Methods (name, tainted, icc) VALUES (?, ?, ?)")) {
                for (String m : methods) {
                    ps.setString(1, m);
                    ps.setInt(2, tainted.contains(m) ? 1 : 0);
                    ps.setInt(3, icc.contains(m) ? 1 : 0);
                    ps.addBatch();
                }
                ps.executeBatch();
            }
            c.commit();
        }
    }

    private static void writeStats(String path, Map<String, Object> stats) throws IOException {
        if (path == null) {
            return;
        }
        try (FileWriter w = new FileWriter(path)) {
            w.write(new GsonBuilder().setPrettyPrinting().create().toJson(stats));
            w.write("\n");
        }
    }
}

package utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import configure.Configure;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import soot.*;
import soot.jimple.*;
import soot.jimple.infoflow.AbstractInfoflow;
import soot.jimple.infoflow.InfoflowConfiguration;
import soot.jimple.infoflow.android.InfoflowAndroidConfiguration;
import soot.jimple.infoflow.android.SetupApplication;
import soot.jimple.infoflow.android.axml.AXmlNode;
import soot.jimple.infoflow.android.config.SootConfigForAndroid;
import soot.jimple.infoflow.android.manifest.ProcessManifest;
import soot.jimple.infoflow.android.manifest.binary.BinaryManifestActivity;
import soot.jimple.infoflow.android.resources.ARSCFileParser;
import soot.jimple.infoflow.cfg.LibraryClassPatcher;
import soot.jimple.infoflow.config.IInfoflowConfig;
import soot.jimple.internal.*;
import soot.jimple.toolkits.callgraph.CallGraph;
import soot.options.Options;
import soot.toolkits.scalar.ConstantValueToInitializerTransformer;
import soot.util.Chain;
import soot.util.HashChain;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.*;

public class InfoflowAnalyzer {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private static String defaultPlatformDir = null;

    private static InfoflowAnalyzer instance;
    private SetupApplication app;
    private String appPath;
    private String platformDir;
    private InfoflowAndroidConfiguration config;
    private boolean forceAndroidJar = false;
    protected IInfoflowConfig sootConfig = new SootConfigForAndroid();

    private String packageName = "";

    private HashMap<String, HashSet<String>> resourceIdToMethodMap = new HashMap<String, HashSet<String>>();

    private CallGraph cg;

    public static InfoflowAnalyzer getInstance() {
        if (instance == null) {
            instance = new InfoflowAnalyzer();
        }
        return instance;
    }

    private InfoflowAnalyzer() {
        Gson gson = new Gson();
        File configFile = new File("configures" + File.separator + "config.json");
        if (!configFile.exists()) {
            // BuildDB passes the platform directory explicitly
            return;
        }
        try (FileReader reader = new FileReader(configFile)) {
            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);
            if (jsonObject.get("defaultPlatformDir") != null) {
                defaultPlatformDir = jsonObject.get("defaultPlatformDir").getAsString();
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public HashMap<String, HashSet<String>> getResourceIdToMethodMap() {
        return resourceIdToMethodMap;
    }

    public void init() {
        config = new InfoflowAndroidConfiguration();
        config.getAnalysisFileConfig().setTargetAPKFile(this.appPath);
        config.getAnalysisFileConfig().setAndroidPlatformDir(platformDir == null ? defaultPlatformDir : platformDir);
//        config.setWriteOutputFiles(true);
        config.setMergeDexFiles(true);
        config.setCallgraphAlgorithm(InfoflowConfiguration.CallgraphAlgorithm.valueOf("CHA"));
        config.getCallbackConfig().setCallbackAnalyzer(InfoflowAndroidConfiguration.CallbackAnalyzer.valueOf("Default"));
        config.setMaxThreadNum(1);
        config.setDataFlowDirection(InfoflowConfiguration.DataFlowDirection.valueOf("Backwards"));
        app = new SetupApplication(config);
        packageName = getAppName();
    }

    public void initializeSoot() {
        logger.info("Start initializing soot");
        final String androidJar = config.getAnalysisFileConfig().getAndroidPlatformDir();
        final String apkFileLocation = config.getAnalysisFileConfig().getTargetAPKFile();

        G.reset();

        Options.v().set_no_bodies_for_excluded(true);
        Options.v().set_allow_phantom_refs(true);
        if (config.getWriteOutputFiles()) Options.v().set_output_format(Options.output_format_jimple);
        else Options.v().set_output_format(Options.output_format_none);
        Options.v().set_whole_program(true);
        Options.v().set_process_dir(Collections.singletonList(apkFileLocation));
        if (forceAndroidJar) Options.v().set_force_android_jar(androidJar);
        else Options.v().set_android_jars(androidJar);
        Options.v().set_src_prec(Options.src_prec_apk_class_jimple);
        Options.v().set_keep_offset(false);
        Options.v().set_keep_line_number(config.getEnableLineNumbers());
        Options.v().set_throw_analysis(Options.throw_analysis_dalvik);
        Options.v().set_process_multiple_dex(config.getMergeDexFiles());
        Options.v().set_ignore_resolution_errors(true);

        // Set soot phase option if original names should be used
        if (config.getEnableOriginalNames()) Options.v().setPhaseOption("jb", "use-original-names:true");

        // Set the Soot configuration options. Note that this will needs to be
        // done before we compute the classpath.
        if (sootConfig != null) sootConfig.setSootOptions(Options.v(), config);

        Options.v().set_soot_classpath(getClasspath());
        Main.v().autoSetOptions();
        configureCallgraph();

        // Load whatever we need
        logger.info("Loading dex files...");
        Scene.v().loadNecessaryClasses();

        logger.info("wjpp phase...");
        // Make sure that we have valid Jimple bodies
        PackManager.v().getPack("wjpp").apply();

        logger.info("patching...");
        // Patch the callgraph to support additional edges. We do this now,
        // because during callback discovery, the context-insensitive callgraph
        // algorithm would flood us with invalid edges.
        LibraryClassPatcher patcher = getLibraryClassPatcher();
        patcher.patchLibraries();

    }

    private String getClasspath() {
        final String androidJar = config.getAnalysisFileConfig().getAndroidPlatformDir();
        final String apkFileLocation = config.getAnalysisFileConfig().getTargetAPKFile();
        final String additionalClasspath = config.getAnalysisFileConfig().getAdditionalClasspath();

        String classpath = forceAndroidJar ? androidJar : Scene.v().getAndroidJarPath(androidJar, apkFileLocation);
        if (additionalClasspath != null && !additionalClasspath.isEmpty())
            classpath += File.pathSeparator + additionalClasspath;
        logger.debug("soot classpath: " + classpath);
        return classpath;
    }

    protected void configureCallgraph() {
        // Configure the callgraph algorithm
        switch (config.getCallgraphAlgorithm()) {
            case AutomaticSelection:
            case SPARK:
                Options.v().setPhaseOption("cg.spark", "on");
                break;
            case GEOM:
                Options.v().setPhaseOption("cg.spark", "on");
                AbstractInfoflow.setGeomPtaSpecificOptions();
                break;
            case CHA:
                Options.v().setPhaseOption("cg.cha", "on");
                break;
            case RTA:
                Options.v().setPhaseOption("cg.spark", "on");
                Options.v().setPhaseOption("cg.spark", "rta:true");
                Options.v().setPhaseOption("cg.spark", "on-fly-cg:false");
                break;
            case VTA:
                Options.v().setPhaseOption("cg.spark", "on");
                Options.v().setPhaseOption("cg.spark", "vta:true");
                break;
            default:
                throw new RuntimeException("Invalid callgraph algorithm");
        }
        if (config.getEnableReflection()) Options.v().setPhaseOption("cg", "types-for-invoke:true");
    }

    protected LibraryClassPatcher getLibraryClassPatcher() {
        return new LibraryClassPatcher();
    }

    public void setAppPath(String appPath) {
        File targetFile = new File(appPath);
        if (!targetFile.exists()) {
            logger.error("Target file not exists");
            return;
        }
        try {
            this.appPath = targetFile.getCanonicalPath();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    public void setPlatformDir(String platformDir) {
        this.platformDir = platformDir;
    }

    public void constructCallGraph() {
        logger.info("Start constructing callgraph");
        app.constructCallgraph();
        cg = Scene.v().getCallGraph();
        logger.info("Callgraph constructed");
    }

    /**
     * FlowDroid's ProcessManifest(String) also parses resources.arsc and throws on tables from recent aapt2 versions
     * ("res1 was not zero"); the manifest itself does not need them, so fall back to an empty resource table.
     */
    private ProcessManifest openManifest() throws Exception {
        try {
            return new ProcessManifest(this.appPath);
        } catch (RuntimeException e) {
            logger.debug("manifest with resources failed (" + e + "), reading it without resources");
            try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(this.appPath)) {
                java.util.zip.ZipEntry entry = zip.getEntry("AndroidManifest.xml");
                byte[] data;
                try (java.io.InputStream in = zip.getInputStream(entry)) {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[1 << 16];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        bytes.write(buf, 0, n);
                    }
                    data = bytes.toByteArray();
                }
                return new ProcessManifest(new java.io.ByteArrayInputStream(data), new ARSCFileParser());
            }
        }
    }

    public String getMainActivity() {
        try (ProcessManifest handler = openManifest();) {
            Set<AXmlNode> launchables = handler.getLaunchableActivityNodes();
            assert (launchables.size() == 1);
            for (AXmlNode launchable : launchables) {
                logger.info("launchable:" + launchable.getAttribute("name").getValue());
                return expandClassName(handler.getPackageName(), (String) launchable.getAttribute("name").getValue());
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
        return null;
    }

    /** Activity names declared in the manifest, fully qualified (replaces the apktool + AndroidManifest.xml parsing). */
    public HashSet<String> getManifestActivities() {
        HashSet<String> activities = new HashSet<>();
        try (ProcessManifest handler = openManifest()) {
            for (BinaryManifestActivity activity : handler.getActivities()) {
                String name = activity.getNameString();
                if (name != null && !name.isEmpty()) {
                    activities.add(expandClassName(handler.getPackageName(), name));
                }
            }
        } catch (Exception e) {
            logger.error("cannot read the activities from the manifest", e);
        }
        return activities;
    }

    private static String expandClassName(String manifestPackage, String name) {
        if (name.startsWith(".")) {
            return manifestPackage + name;
        }
        if (!name.contains(".")) {
            return manifestPackage + "." + name;
        }
        return name;
    }

    /**
     * Java package of the app's own code: the package of the app's R class that encloses the main activity.
     * It differs from the package name when the build sets an applicationId suffix (for example ".debug").
     */
    public String getAppJavaPackage() {
        String mainActivity = getMainActivity();
        String best = null;
        for (SootClass sc : Scene.v().getClasses()) {
            String name = sc.getName();
            if (!name.endsWith(".R$id")) {
                continue;
            }
            String pkg = name.substring(0, name.length() - ".R$id".length());
            if (mainActivity != null && mainActivity.startsWith(pkg + ".") && (best == null || pkg.length() > best.length())) {
                best = pkg;
            }
        }
        if (best == null && mainActivity != null && mainActivity.lastIndexOf('.') > 0) {
            best = mainActivity.substring(0, mainActivity.lastIndexOf('.'));
        }
        return best == null ? packageName : best;
    }

    private String cachedAppName = null;

    public String getAppName() {
        if (cachedAppName != null) {
            return cachedAppName;
        }
        // same value as before (the last package of the resource table), read with ArscResources because FlowDroid's
        // parser rejects tables from recent aapt2 versions; FlowDroid's parser stays as the fallback
        try {
            List<String> packages = ArscResources.read(this.appPath).packages;
            if (!packages.isEmpty()) {
                cachedAppName = packages.get(packages.size() - 1);
                return cachedAppName;
            }
        } catch (Exception e) {
            logger.warn("cannot read the resource table: " + e);
        }
        ARSCFileParser parser = new ARSCFileParser();
        try {
            parser.parse(this.appPath);
        } catch (Exception e) {
            e.printStackTrace();
        }
        List<ARSCFileParser.ResPackage> pkgs = parser.getPackages();
//        assert (pkgs.size() == 1);
        return pkgs.get(pkgs.size()-1).getPackageName();
    }

    private SetupApplication getInfoflowApplication() {
        return app;
    }

    public CallGraph getCallgraph() {
        return cg;
    }

    public void test() {
        logger.info("Start testing");
        SootClass testClass = Scene.v().getSootClass("com.exp.wuatest.databinding.ActivityMainBinding");
        List<SootMethod> methods = testClass.getMethods();
        return;
    }

    /**
     * Resource id -> "package:type/name", the same content the original read from apktool's res/values/public.xml,
     * now read straight from resources.arsc (no apktool, no Windows batch file).
     */
    private Map<Integer, String> createIdNameMap() {
        Map<Integer, String> idNameMap = new HashMap<Integer, String>();
        try {
            for (Map.Entry<Integer, String[]> e : ArscResources.read(appPath).resources.entrySet()) {
                idNameMap.put(e.getKey(), packageName + ":" + e.getValue()[1] + "/" + e.getValue()[2]);
            }
        } catch (Exception e) {
            logger.error("cannot read resources.arsc: " + e);
        }
        logger.info("resource ids read from resources.arsc: " + idNameMap.size());
        return idNameMap;
    }

    public void analysisGUIByBinding() {
        logger.info("Start analysis GUI by binding");

        //第一步先拿到databinding下的所有类，顺便过滤出属于package的类
        logger.info("stage 1");
        Chain<SootClass> classes = Scene.v().getClasses();
//        Chain<SootClass> classes = Scene.v().getApplicationClasses();
        Iterator<SootClass> it = classes.iterator();
        Chain<SootClass> bindingClasses = new HashChain<SootClass>();
        Chain<SootClass> resourceClasses = new HashChain<SootClass>();
        Chain<SootClass> workList = new HashChain<SootClass>();
        Chain<SootClass> workListStatic = new HashChain<SootClass>();
        // the app's Java package can differ from the package name (applicationId suffix such as ".debug")
        String appJavaPackage = getAppJavaPackage();
        logger.info("app Java package: " + appJavaPackage);
        while (it.hasNext()) {
            SootClass sc = it.next();
            if (sc.getName().matches(".*\\.databinding\\..*")) {
                bindingClasses.add(sc);
            } else if (sc.getName().startsWith(packageName) || sc.getName().startsWith(appJavaPackage + ".")) {
                if (sc.getName().matches(".*\\.R\\$.*")) {
                    resourceClasses.add(sc);
                } else {
                    workList.add(sc);
                    workListStatic.add(sc);
                }
            }
        }

        //第二步解析binding类，构建{int, name}的映射
        //Todo 这部分可以并行处理
        //不确定是否都是SootField，可能后续还得修改
        logger.info("stage 2");
//        Map<String, SootMethod> resourceIdToMethodMap = new HashMap<String, SootMethod>();
        Map<Integer, HashSet<String>> idFuncMap = new HashMap<Integer, HashSet<String>>();
        Map<SootField, Integer> nameIdMap = new HashMap<SootField, Integer>();
        while (!bindingClasses.isEmpty()) {
            SootClass sc = bindingClasses.getFirst();
            bindingClasses.remove(sc);
            try {
            //debug
//            if (!sc.getName().equals("com.exp.wuatest.databinding.ActivityMainBinding")) {
//                continue;
//            }
            // work on a copy (the loop removes and re-adds entries; it must not change the class), and skip classes
            // without bind(): their <init> would be re-queued forever
            List<SootMethod> methodsWorkList = new ArrayList<SootMethod>(sc.getMethods());
            boolean hasBind = false;
            for (SootMethod m : methodsWorkList) {
                if (m.getName().equals("bind")) {
                    hasBind = true;
                }
            }
            if (!hasBind) {
                continue;
            }
            //localMap是在bind函数里调用init时，变量名与参数位置的映射
            Map<Integer, Value> localMap = new HashMap<Integer, Value>();
            //idNameLocalMap是在bind里的id和name的映射，最后会在init处理过程中转成全局的idNameMap
            Map<Value, Integer> idNameLocalMap = new HashMap<Value, Integer>();
            Boolean bindAnalysis = false;
            while (!methodsWorkList.isEmpty()) {
                SootMethod sm = methodsWorkList.get(0);
                methodsWorkList.remove(sm);
                if (sm.getName().equals("bind")) {
                    for (Unit u : sm.retrieveActiveBody().getUnits()) {
                        //处理findChildViewById,通过参数定位id，返回值定位存储
                        int id;
                        int idx;
                        String ret;
                        if (u instanceof AssignStmt) {
                            if (((AssignStmt) u).getRightOp() instanceof JStaticInvokeExpr) {
                                JStaticInvokeExpr rightOp = (JStaticInvokeExpr) ((AssignStmt) u).getRightOp();
                                if (rightOp.getMethod().getName().equals("findChildViewById") || rightOp.getMethod().getName().equals("findViewById")) {
                                    idx = rightOp.getArgCount() == 2 ? 1 : 0;
                                    if (rightOp.getArg(idx) instanceof IntConstant)
                                        id = ((IntConstant) rightOp.getArg(idx)).value;
                                    else {
                                        //TODO
                                        logger.error("todo");
                                        id = -1;
                                    }
                                    ret = ((AssignStmt) u).getLeftOp().toString();
                                    //获取当前unit的下一条unit
                                    Unit succUnit = sm.retrieveActiveBody().getUnits().getSuccOf(u);
                                    //判断下下条unit是否是把ret的值赋给变量
                                    //按理应该是的，有特殊情况再处理
                                    if (succUnit instanceof AssignStmt) {
                                        //得加个转换，不然直接toString是拿到一个cast的字符串：(android.widget.Button) $u-1
                                        String rightName = "";
                                        if (((AssignStmt) succUnit).getRightOp() instanceof JCastExpr)
                                            rightName = ((JCastExpr) ((AssignStmt) succUnit).getRightOp()).getOp().toString();
                                        else {
                                            //TODO
                                            logger.error("todo");
                                        }
                                        if (rightName.equals(ret)) {
                                            //先加个check，虽然应该没啥用
                                            if (((AssignStmt) succUnit).getLeftOp() instanceof JimpleLocal) {
                                                idNameLocalMap.put(((AssignStmt) succUnit).getLeftOp(), id);
                                            } else {
                                                //TODO
                                                logger.error("todo");
                                            }
                                        }
                                    } else {
                                        logger.error("todo");
                                    }
                                }
                            }
                        }
                        //处理最后的调用init函数
                        if (u instanceof InvokeStmt) {
                            if (((InvokeStmt) u).getInvokeExpr().getMethod().getName().equals("<init>")) {
                                List<Value> args = ((InvokeStmt) u).getInvokeExpr().getArgs();
                                for (Value localVar : idNameLocalMap.keySet()) {
                                    if (args.contains(localVar)) {
                                        localMap.put(args.indexOf(localVar), localVar);
                                    }
                                }
                            }
                        }
                    }
                    bindAnalysis = true;
                } else if (sm.getName().equals("<init>")) {
                    //如果还没处理bind函数就先跳过，加入worklist
                    if (!bindAnalysis) {
                        methodsWorkList.add(sm);
                        continue;
                    }
                    //下面的写法是考虑到binding的init不会很复杂，不存在什么变量来回赋值等复杂情况，如果有再考虑修改
                    Map<Value, Integer> initFuncLocalMap = new HashMap<Value, Integer>();
                    for (Unit u : sm.retrieveActiveBody().getUnits()) {
                        //先处理参数赋值
                        if (u instanceof JIdentityStmt) {
                            if (((IdentityStmt) u).getRightOp() instanceof ParameterRef) {
                                ParameterRef rightOp = (ParameterRef) ((IdentityStmt) u).getRightOp();
                                if (localMap.get(rightOp.getIndex()) != null) {
                                    initFuncLocalMap.put(((IdentityStmt) u).getLeftOp(), rightOp.getIndex());
                                }
                            } else {
                                logger.debug("todo");
                            }
                        } else if (u instanceof AssignStmt) {
                            //处理后续赋值
                            u.getUnitBoxes();
                            if (initFuncLocalMap.get(((AssignStmt) u).getRightOp()) != null) {
                                if (((AssignStmt) u).getLeftOp() instanceof JInstanceFieldRef) {
                                    //先对应init的本地变量到bind的本地变量的映射
                                    int argsIdx = initFuncLocalMap.get(((AssignStmt) u).getRightOp());
                                    Value bindLocalVar = localMap.get(argsIdx);
                                    Integer id = idNameLocalMap.get(bindLocalVar);
                                    if (id != null) {
                                        nameIdMap.put(((JInstanceFieldRef) ((AssignStmt) u).getLeftOp()).getField(), id);
                                    } else {
                                        logger.error("todo");
                                    }
                                } else {
                                    logger.debug("todo");
                                }
                            }
                        }
                    }
                }
            }
            } catch (Throwable t) {
                logger.warn("binding analysis skipped " + sc.getName() + ": " + t);
            }
        }

        //第三步,处理worklist
        logger.info("stage 3");
        while (!workList.isEmpty()) {
            SootClass sc = workList.getFirst();
            workList.remove(sc);
            try {

            List<SootMethod> methods = sc.getMethods();
            for (SootMethod sm : methods) {
                if (!sm.isConcrete()) {
                    continue;
                }
                for (Unit u : sm.retrieveActiveBody().getUnits()) {
                    if (u instanceof AssignStmt) {
                        if (((AssignStmt) u).getRightOp() instanceof JInstanceFieldRef) {
                            SootField rightOp = ((JInstanceFieldRef) ((AssignStmt) u).getRightOp()).getField();
                            if (nameIdMap.get(rightOp) != null) {
                                logger.debug("find");
                                //定位到binding后，先拿到控件的本地变量
                                Value leftOp = ((AssignStmt) u).getLeftOp();
                                //然后查找变量的调用，如
                                // virtualinvoke $r5.<android.widget.Button: void setOnClickListener(android.view.View$OnClickListener)>($r6);
                                Unit u_ = u;
                                Boolean endFlag = false;
                                while (!endFlag) {
                                    if (u_ == null) {
                                        //默认如果找不到后继的原因是binding调的不是setclickonlistener系列的函数
                                        endFlag = true;
                                        break;
                                    }
                                    u_ = sm.retrieveActiveBody().getUnits().getSuccOf(u_);
                                    if (u_ instanceof InvokeStmt) {
                                        InvokeExpr invokeExpr = ((InvokeStmt) u_).getInvokeExpr();
                                        if (invokeExpr instanceof JVirtualInvokeExpr) {
                                            u_.getUnitBoxes();
                                            if (((JVirtualInvokeExpr) invokeExpr).getBase().equals(leftOp)) {
                                                //先假设第一个找到的调用就是setOnClickListener系列的函数，应该也不会有问题
                                                //做个简单的检查
                                                if (((JVirtualInvokeExpr) invokeExpr).getArgCount() != 1) {
                                                    logger.error("not setOnClickListener");
                                                    break;
                                                }
                                                if (!((JVirtualInvokeExpr) invokeExpr).getMethod().getName().matches("set.*Listener")) {
                                                    break;
                                                }
                                                if (((JVirtualInvokeExpr) invokeExpr).getMethod().getName().equals("setListener")) {
                                                    break;
                                                }
                                                Value classInstance = ((JVirtualInvokeExpr) invokeExpr).getArg(0);
                                                //找到了类变量，再往回查这个变量的初始化
                                                Unit preUnit = u_;
                                                Boolean findFlag = false;

                                                while (!findFlag) {
                                                    //如果找不到，比如通过参数传入的，就先跳过
                                                    if (preUnit == null) {
                                                        SootMethod fakeSm = Scene.v().makeSootMethod("fakeSm", new ArrayList<Type>(), VoidType.v(), Modifier.PUBLIC);
                                                        SootClass fakeClass = Scene.v().makeSootClass("fakeClass", Modifier.PUBLIC);
                                                        fakeClass.addMethod(fakeSm);
                                                        HashSet<String> tmp = new HashSet<String>();
                                                        tmp.add(fakeSm.toString());
                                                        idFuncMap.put(nameIdMap.get(rightOp), tmp);
                                                        endFlag = true;
                                                        break;
                                                    }
                                                    preUnit = sm.retrieveActiveBody().getUnits().getPredOf(preUnit);
                                                    if (preUnit instanceof AssignStmt) {
                                                        //当左值与classInstance相同且右值是new操作，则认为new的class就是对应的类
                                                        if (((JAssignStmt) preUnit).getLeftOp().equals(classInstance) && ((JAssignStmt) preUnit).getRightOp() instanceof JNewExpr) {
                                                            SootClass targetClass = ((JNewExpr) ((JAssignStmt) preUnit).getRightOp()).getBaseType().getSootClass();
                                                            //直接找onClick函数
                                                            List<SootMethod> targetMethods = targetClass.getMethods();
                                                            for (SootMethod sm_ : targetMethods) {
                                                                if (sm_.getName().matches("on.*lick") || sm_.getName().matches("on.*Touch") || sm_.getName().matches("on.*")) {
                                                                    HashSet<String> tmp = idFuncMap.get(nameIdMap.get(rightOp)) == null ? tmp = new HashSet<String>() : idFuncMap.get(nameIdMap.get(rightOp));
                                                                    tmp.add(sm_.toString());
                                                                    idFuncMap.put(nameIdMap.get(rightOp), tmp);
                                                                    endFlag = true;
                                                                    findFlag = true;
                                                                } else {
                                                                    logger.error("todo");
                                                                }
                                                            }
                                                            if (!findFlag) {
                                                                logger.error("something wrong");
                                                                break;
                                                            }
                                                        }
                                                    } else if (preUnit instanceof JIdentityStmt) {
                                                        //处理参数赋值

                                                        if (((JIdentityStmt) preUnit).getLeftOp().equals(classInstance)) {
                                                            if (((JIdentityStmt) preUnit).getRightOp() instanceof ParameterRef) {
                                                                int paramIdx = ((ParameterRef) ((JIdentityStmt) preUnit).getRightOp()).getIndex();
                                                                HashSet<String> res = analyzeParameter(paramIdx, sm, workListStatic, "todo", null);
                                                                idFuncMap.put(nameIdMap.get(rightOp), res);
                                                            } else if (((JIdentityStmt) preUnit).getRightOp() instanceof ThisRef) {
                                                                List<SootMethod> targetMethods = sc.getMethods();
                                                                for (SootMethod sm_ : targetMethods) {
                                                                    if (sm_.getName().matches("on.*lick") || sm_.getName().matches("on.*Touch") || sm_.getName().matches("on.*")) {
                                                                        HashSet<String> tmp = idFuncMap.get(nameIdMap.get(rightOp)) == null ? tmp = new HashSet<String>() : idFuncMap.get(nameIdMap.get(rightOp));
                                                                        tmp.add(sm_.toString());
                                                                        idFuncMap.put(nameIdMap.get(rightOp), tmp);
                                                                        endFlag = true;
                                                                        findFlag = true;
                                                                    }
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        } else {
                                            //TODO
                                            continue;
                                        }
                                    }
                                }
                            } else {
                                //TODO
                            }
                        }
                    }
                }
            }
            } catch (Throwable t) {
                logger.warn("binding analysis skipped " + sc.getName() + ": " + t);
            }
        }

        //最后分析.R文件
        logger.info("stage 4");
        while (!resourceClasses.isEmpty()) {
            SootClass sc = resourceClasses.getFirst();
            resourceClasses.remove(sc);
            try {
            //需要转换一下，不然不一定有clinit，抄的flowdroid的writejimple的办法
            if (!sc.isPhantom()) {
                ConstantValueToInitializerTransformer.v().transformClass(sc);
            }
            List<SootMethod> methods = sc.getMethods();
            for (SootMethod sm : methods) {
                for (Unit u : sm.retrieveActiveBody().getUnits()) {
                    if (u instanceof AssignStmt) {
                        if (((JAssignStmt) u).getRightOp() instanceof IntConstant) {
                            //只处理值对应了func的情况
                            if (idFuncMap.get(((IntConstant) ((JAssignStmt) u).getRightOp()).value) != null) {
                                if (((JAssignStmt) u).getLeftOp() instanceof StaticFieldRef) {
                                    SootField leftOp = ((StaticFieldRef) ((JAssignStmt) u).getLeftOp()).getField();
                                    String declaringClass = leftOp.getDeclaringClass().getName();
                                    String name = leftOp.getName();
                                    String resourceId = parseClassNameToResourceId(declaringClass) + "/" + name;
                                    resourceIdToMethodMap.put(resourceId, idFuncMap.get(((IntConstant) ((JAssignStmt) u).getRightOp()).value));
                                } else {
                                    logger.error("todo");
                                }
                            }
                        }
                    }
                }
            }
            } catch (Throwable t) {
                logger.warn("R class skipped " + sc.getName() + ": " + t);
            }
        }
        analyze();
        return;
    }

    public void analysisGUIByFind() {
        logger.info("Start analysis GUI by findviewbyid");

        logger.info("handle classes");

        String fullClassName = getMainActivity();
        if (fullClassName == null) {
            fullClassName = packageName + ".Unknown";
        }
        int lastDotIndex = fullClassName.lastIndexOf('.');
        String mainPackage = fullClassName.substring(0, lastDotIndex);

        Configure configure = Configure.getInstance();
        HashMap<String, HashSet<String>> apiMap = configure.getApiMap();
        Chain<SootClass> classes = Scene.v().getClasses();
//        Chain<SootClass> classes = Scene.v().getApplicationClasses();
        Iterator<SootClass> it = classes.iterator();
        Chain<SootClass> resourceClasses = new HashChain<SootClass>();
        Chain<SootClass> workList = new HashChain<SootClass>();
        Chain<SootClass> workListStatic = new HashChain<SootClass>();
        Map<Integer, String> idNameMap = new HashMap<Integer, String>();
        while (it.hasNext()) {
            SootClass sc = it.next();
            //因为有的apk的类不是包名，所以感觉得用黑名单把android那些过滤了。。。
            if (!sc.getName().startsWith("com.google.android") && !sc.getName().startsWith("android") && !sc.getName().startsWith("androidx") && !sc.getName().startsWith("com.android") && !sc.getName().startsWith("java")) {
                if (sc.getName().matches(".*\\.R\\$.*")) {
                    resourceClasses.add(sc);
                } else {
                    workList.add(sc);
                    workListStatic.add(sc);
                }
            }
            //白名单
//            if (sc.getName().startsWith(packageName) || sc.getName().startsWith(mainPackage)) {
//                if (sc.getName().matches(".*\\.R\\$.*")) {
//                    resourceClasses.add(sc);
//                } else {
//                    workList.add(sc);
//                    workListStatic.add(sc);
//                }
//            }
        }
        //先分析一边R文件，建立一个int -> resourceId的映射
        logger.info("handle R");
//        while (!resourceClasses.isEmpty()) {
//            SootClass sc = resourceClasses.getFirst();
//            //需要转换一下，不然不一定有clinit，抄的flowdroid的writejimple的办法
//            if (!sc.isPhantom()) {
//                ConstantValueToInitializerTransformer.v().transformClass(sc);
//            }
//            resourceClasses.remove(sc);
//            List<SootMethod> methods = sc.getMethods();
//            for (SootMethod sm : methods) {
//                for (Unit u : sm.retrieveActiveBody().getUnits()) {
//                    if (u instanceof AssignStmt) {
//                        //有数组的情况，还没处理，看看以后需不需要处理
//                        if (((JAssignStmt) u).getRightOp() instanceof IntConstant && ((JAssignStmt) u).getLeftOp() instanceof StaticFieldRef) {
//                            // 直接将int和resourceid对应
//                            Integer id = ((IntConstant) ((JAssignStmt) u).getRightOp()).value;
//                            String resourceid = parseClassNameToResourceId(((StaticFieldRef) ((JAssignStmt) u).getLeftOp()).getField().getDeclaringClass().getName()) + "/" + ((StaticFieldRef) ((JAssignStmt) u).getLeftOp()).getField().getName();
//                            idNameMap.put(id, resourceid);
//                        }
//                    }
//                }
//            }
//        }
        idNameMap = createIdNameMap();

        //找到所有的setXXXXlistener
        logger.info("handle worklist");
        int cnt = 0;
        while (!workList.isEmpty()) {
            SootClass sc = workList.getFirst();
            workList.remove(sc);
            try {
            if (!sc.isPhantom()) {
                ConstantValueToInitializerTransformer.v().transformClass(sc);
            }
            List<SootMethod> methods = sc.getMethods();
            for (SootMethod sm : methods) {
                if (!sm.isConcrete()) {
                    continue;
                }
                for (Unit u : sm.retrieveActiveBody().getUnits()) {
                    if (u instanceof InvokeStmt) {
                        InvokeExpr invokeExpr = ((InvokeStmt) u).getInvokeExpr();
                        if (invokeExpr instanceof JVirtualInvokeExpr) {
                            String methodName = ((JVirtualInvokeExpr) invokeExpr).getMethod().getName();
                            if (!apiMap.containsKey(methodName)) {
                                continue;
                            }
                            Value base = ((JVirtualInvokeExpr) invokeExpr).getBase();
                            Value classInstance = ((JVirtualInvokeExpr) invokeExpr).getArg(0);
                            //存在setXXXListener(null)的情况
                            if (classInstance instanceof NullConstant) {
                                continue;
                            }
                            cnt++;
                            //找到了类变量和参数，再往回查这个变量的初始化
                            Unit preUnit = u;
                            Boolean findClass = false;
                            Boolean findBase = false;
                            HashSet<SootMethod> targetMethod = new HashSet<SootMethod>();
                            String resourceId = null;
                            HashSet<String> resourceIds = null;
                            HashSet<String> res = null;
                            while (!findBase || !findClass) {
                                if (preUnit == null) {
                                    logger.error("todo");
                                    break;
                                }
                                preUnit = sm.retrieveActiveBody().getUnits().getPredOf(preUnit);
                                if (preUnit instanceof AssignStmt) {
                                    //当左值与classInstance相同且右值是new操作，则认为new的class就是对应的类
                                    if (!findClass && ((JAssignStmt) preUnit).getLeftOp().equals(classInstance) && ((JAssignStmt) preUnit).getRightOp() instanceof JNewExpr) {
                                        SootClass targetClass = ((JNewExpr) ((JAssignStmt) preUnit).getRightOp()).getBaseType().getSootClass();
                                        //直接找onClick函数
                                        List<SootMethod> targetMethods = targetClass.getMethods();
                                        for (SootMethod sm_ : targetMethods) {
                                            if (apiMap.get(methodName).contains(sm_.getName())) {
                                                targetMethod.add(sm_);
                                                findClass = true;
                                            }
                                        }
                                    }
                                    //左值是base且右值是本地变量或者class私有变量
                                    //也可能是virtualinvoke
                                    if (!findBase && ((JAssignStmt) preUnit).getLeftOp().equals(base)) {
                                        Value rightOp = ((JAssignStmt) preUnit).getRightOp() instanceof JCastExpr ? ((JCastExpr) ((JAssignStmt) preUnit).getRightOp()).getOp() : ((JAssignStmt) preUnit).getRightOp();
                                        Boolean findFlag = false;
                                        Unit preUnit_ = preUnit;
                                        Value argId = null;
                                        //先判断下这个unit是不是virtualinvoke
                                        if (rightOp instanceof JVirtualInvokeExpr) {
                                            JVirtualInvokeExpr invokeExpr_ = (JVirtualInvokeExpr) rightOp;
                                            if (invokeExpr_.getMethod().getName().equals("findViewById") ||
                                                    invokeExpr_.getMethod().getName().equals("findViewWithTag") ||
                                                    invokeExpr_.getMethod().getName().equals("findViewsWithText") ||
                                                    invokeExpr_.getMethod().getName().equals("inflate")) {
                                                argId = invokeExpr_.getArg(0);
                                                //debug
                                                if (argId instanceof IntConstant) {
                                                    //find in R
                                                    if (idNameMap.get(((IntConstant) argId).value) != null) {
                                                        resourceId = idNameMap.get(((IntConstant) argId).value);
                                                        findFlag = true;
                                                        findBase = true;
                                                    } else {
                                                        logger.error("todo");
                                                    }
                                                }
                                            }
                                        }
                                        while (!findFlag) {
                                            if (preUnit_ == null) {
                                                //使用的是class的成员变量的时候需要再在这个类里找一遍在哪初始
                                                resourceIds = analyzeThisClassForBaseInit(sc, rightOp, idNameMap);
                                                if (resourceIds.isEmpty()) {
                                                    logger.error("todo");
                                                } else {
                                                    findBase = true;
                                                }
                                                break;
                                            }
                                            preUnit_ = sm.retrieveActiveBody().getUnits().getPredOf(preUnit_);
                                            if (preUnit_ instanceof AssignStmt) {
                                                //很奇怪r0.<com.example.wuatest2.MainActivity: android.widget.Button button1>的equals是false，所以用string替代
                                                if (((JAssignStmt) preUnit_).getLeftOp().toString().equals(rightOp.toString())) {
                                                    if (((JAssignStmt) preUnit_).getRightOp() instanceof JVirtualInvokeExpr) {
                                                        JVirtualInvokeExpr invokeExpr_ = (JVirtualInvokeExpr) ((JAssignStmt) preUnit_).getRightOp();
                                                        if (invokeExpr_.getMethod().getName().equals("findViewById") ||
                                                                invokeExpr_.getMethod().getName().equals("findViewWithTag") ||
                                                                invokeExpr_.getMethod().getName().equals("findViewsWithText") ||
                                                                invokeExpr_.getMethod().getName().equals("inflate")) {
                                                            argId = invokeExpr_.getArg(0);
                                                            //debug
                                                            if (argId instanceof IntConstant) {
                                                                //find in R
                                                                if (idNameMap.get(((IntConstant) argId).value) != null) {
                                                                    resourceId = idNameMap.get(((IntConstant) argId).value);
                                                                    findFlag = true;
                                                                    findBase = true;
                                                                } else {
                                                                    logger.error("todo");
                                                                }
                                                            }
                                                        } else {
                                                            //TODO 处理函数调用返回的情况
                                                            //$r8 = virtualinvoke r0.<com.sonyericsson.music.ui.SlidingTabLayout: android.widget.TextView createDefaultTabView(android.content.Context)>($r4)
                                                            //$r7 = (android.view.View) $r8
                                                            //virtualinvoke $r7.<android.view.View: void setOnClickListener(android.view.View$OnClickListener)>($r1)
                                                            logger.error("todo 111");
                                                        }
                                                    } else {
                                                        if (((JAssignStmt) preUnit_).getRightOp() instanceof JCastExpr) {
                                                            rightOp = ((JCastExpr) ((JAssignStmt) preUnit_).getRightOp()).getOp();
                                                        } else {
                                                            rightOp = ((JAssignStmt) preUnit_).getRightOp();
                                                        }
                                                    }
                                                } else if (argId != null && ((JAssignStmt) preUnit_).getLeftOp().equals(argId)) {
                                                    if (((JAssignStmt) preUnit_).getRightOp() instanceof StaticFieldRef) {
                                                        resourceId = parseClassNameToResourceId(((StaticFieldRef) ((JAssignStmt) preUnit_).getRightOp()).getField().getDeclaringClass().getName()) + "/" + ((StaticFieldRef) ((JAssignStmt) preUnit_).getRightOp()).getField().getName();
                                                        findFlag = true;
                                                        findBase = true;
                                                    } else {
                                                        logger.debug("something todo here");
                                                    }
                                                }

                                            }
                                        }
                                    }
                                } else if (preUnit instanceof JIdentityStmt) {
                                    //处理参数赋值
                                    if (!findClass && ((JIdentityStmt) preUnit).getLeftOp().equals(classInstance)) {
                                        if (((JIdentityStmt) preUnit).getRightOp() instanceof ParameterRef) {
                                            int paramIdx = ((ParameterRef) ((JIdentityStmt) preUnit).getRightOp()).getIndex();
                                            res = analyzeParameter(paramIdx, sm, workListStatic, methodName, apiMap);
                                            findClass = true;
                                        } else if (((JIdentityStmt) preUnit).getRightOp() instanceof ThisRef) {
                                            List<SootMethod> targetMethods = sc.getMethods();
                                            for (SootMethod sm_ : targetMethods) {
                                                if (apiMap.get(methodName).contains(sm_.getName())) {
                                                    targetMethod.add(sm_);
                                                    findClass = true;
                                                }
                                            }
                                        }
                                    } else if (!findBase && ((JIdentityStmt) preUnit).getLeftOp().equals(base)) {
                                        if (((JIdentityStmt) preUnit).getRightOp() instanceof ParameterRef) {
                                            int paramIdx = ((ParameterRef) ((JIdentityStmt) preUnit).getRightOp()).getIndex();
                                            resourceId = analyzeParameterForBase(paramIdx, sm, workListStatic, idNameMap);
                                            findBase = true;
                                            if (resourceId == null) {
                                                logger.error("todo");
                                            }
                                        }
                                    }
                                }
                            }
                            if (findBase && findClass) {
                                if (resourceIds != null && !resourceIds.isEmpty()) {
                                    for (String resourceId_ : resourceIds) {
                                        HashSet<String> tmp = resourceIdToMethodMap.get(resourceId_) == null ? tmp = new HashSet<String>() : resourceIdToMethodMap.get(resourceId_);
                                        if (res != null && !res.isEmpty()) {
                                            tmp.addAll(res);
                                        }
                                        if (!targetMethod.isEmpty()) {
                                            for (SootMethod targetMethod_ : targetMethod)
                                                tmp.add(targetMethod_.toString());
                                        }
                                        if (tmp.isEmpty()) {
                                            logger.error("something wrong");
                                        }
                                        resourceIdToMethodMap.put(resourceId_, tmp);
                                    }
                                } else {
                                    HashSet<String> tmp = resourceIdToMethodMap.get(resourceId) == null ? tmp = new HashSet<String>() : resourceIdToMethodMap.get(resourceId);
                                    if (res != null && !res.isEmpty()) {
                                        tmp.addAll(res);
                                    }
                                    if (!targetMethod.isEmpty()) {
                                        for (SootMethod targetMethod_ : targetMethod)
                                            tmp.add(targetMethod_.toString());
                                    }
                                    if (tmp.isEmpty()) {
                                        logger.error("something wrong");
                                    }
                                    //check
                                    if (resourceId == null) {
                                        logger.error("wrong");
                                    }
                                    resourceIdToMethodMap.put(resourceId, tmp);
                                }
                            }
                        }

                    }
                }
            }
            } catch (Throwable t) {
                logger.warn("GUI analysis skipped " + sc.getName() + ": " + t);
            }

        }
        logger.info("finish");
    }

    private HashSet<String> analyzeThisClassForBaseInit(SootClass sc, Value base, Map<Integer, String> idNameMap) {
        HashSet<String> resourceId = new HashSet<String>();
        if (!sc.isPhantom()) {
            ConstantValueToInitializerTransformer.v().transformClass(sc);
        }
        List<SootMethod> methods = sc.getMethods();
        for (SootMethod sm : methods) {
            if (!sm.isConcrete()) {
                continue;
            }
            for (Unit u : sm.retrieveActiveBody().getUnits()) {
                if (u instanceof AssignStmt && ((JAssignStmt) u).getLeftOp().toString().equals(base.toString())) {
                    Boolean findFlag = false;
                    Value localVar = base;
                    Unit preUnit = u;
                    Value argId = null;
                    while (!findFlag) {
                        if (preUnit == null) {
                            logger.error("todo");
                            break;
                        }
                        if (preUnit instanceof AssignStmt) {
                            if (((JAssignStmt) preUnit).getLeftOp().toString().equals(localVar.toString())) {
                                if (((JAssignStmt) preUnit).getRightOp() instanceof JVirtualInvokeExpr) {
                                    JVirtualInvokeExpr invokeExpr_ = (JVirtualInvokeExpr) ((JAssignStmt) preUnit).getRightOp();
                                    if (invokeExpr_.getMethod().getName().equals("findViewById") ||
                                            invokeExpr_.getMethod().getName().equals("findViewWithTag") ||
                                            invokeExpr_.getMethod().getName().equals("findViewsWithText") ||
                                            invokeExpr_.getMethod().getName().equals("inflate")) {
                                        argId = invokeExpr_.getArg(0);
                                        //debug
                                        if (argId instanceof IntConstant) {
                                            //find in R
                                            if (idNameMap.get(((IntConstant) argId).value) != null) {
                                                resourceId.add(idNameMap.get(((IntConstant) argId).value));
                                                findFlag = true;
                                            } else {
                                                logger.error("todo");
                                            }
                                        }
                                    }
                                } else {
                                    if (((JAssignStmt) preUnit).getRightOp() instanceof JCastExpr) {
                                        localVar = ((JCastExpr) ((JAssignStmt) preUnit).getRightOp()).getOp();
                                    } else {
                                        localVar = ((JAssignStmt) preUnit).getRightOp();
                                    }
                                }
                            } else if (argId != null && ((JAssignStmt) preUnit).getLeftOp().equals(argId)) {
                                if (((JAssignStmt) preUnit).getRightOp() instanceof StaticFieldRef) {
                                    resourceId.add(parseClassNameToResourceId(((StaticFieldRef) ((JAssignStmt) preUnit).getRightOp()).getField().getDeclaringClass().getName()) + "/" + ((StaticFieldRef) ((JAssignStmt) preUnit).getRightOp()).getField().getName());
                                    findFlag = true;
                                } else {
                                    logger.debug("something todo here");
                                }
                            }
                        }
                        preUnit = sm.retrieveActiveBody().getUnits().getPredOf(preUnit);
                    }
                }
            }
        }

        return resourceId;
    }

    private String parseClassNameToResourceId(String className) {
        String[] strs = className.split("\\.R\\$");
        String resId = "";
        if (strs.length != 2) {
            logger.error("something wrong");
            return "";
        }
        resId = strs[0] + ":" + strs[1];
        return resId;
    }

    private String analyzeParameterForBase(int paraIdx, SootMethod sm, Chain<SootClass> classes, Map<Integer, String> idNameMap) {
        Iterator<SootClass> it = classes.iterator();
        HashSet<String> targetMethod = new HashSet<String>();
        String resourceId = null;
        while (it.hasNext()) {
            SootClass sc = it.next();
            List<SootMethod> methods = sc.getMethods();
            for (SootMethod sm_ : methods) {
                if (!sm_.isConcrete()) {
                    continue;
                }
                //遍历找到方法的调用点，然后往回查参数的赋值
                for (Unit u : sm_.retrieveActiveBody().getUnits()) {
                    if (u instanceof JInvokeStmt) {
                        InvokeExpr invokeExpr = ((JInvokeStmt) u).getInvokeExpr();
                        if (invokeExpr.getMethod().equals(sm)) {
                            u.getUnitBoxes();
                            Value localVar = invokeExpr.getArg(paraIdx);
                            if (!(localVar instanceof JimpleLocal)) {
                                logger.warn("TODO");
                                continue;
                            }
                            Boolean endFlag = false;
                            Unit preUnit = u;
                            Value argId = null;
                            while (!endFlag) {
                                if (preUnit == null) {
                                    logger.error("todo");
                                    break;
                                }
                                preUnit = sm_.retrieveActiveBody().getUnits().getPredOf(preUnit);
                                if (preUnit instanceof AssignStmt) {
                                    if (((JAssignStmt) preUnit).getLeftOp().toString().equals(localVar.toString())) {
                                        //目前看有两种情况
                                        //第一种是本地变量就在当前方法内调用了findviewbyid
                                        //第二种是本地变量赋值的是别的方法的返回值
                                        //第一种情况
                                        if (((JAssignStmt) preUnit).getRightOp() instanceof JVirtualInvokeExpr) {
                                            JVirtualInvokeExpr invokeExpr_ = (JVirtualInvokeExpr) ((JAssignStmt) preUnit).getRightOp();
                                            if (invokeExpr_.getMethod().getName().equals("findViewById") ||
                                                    invokeExpr_.getMethod().getName().equals("findViewWithTag") ||
                                                    invokeExpr_.getMethod().getName().equals("findViewsWithText") ||
                                                    invokeExpr_.getMethod().getName().equals("inflate")) {
                                                argId = invokeExpr_.getArg(0);
                                                //debug
                                                if (argId instanceof IntConstant) {
                                                    //find in R
                                                    if (idNameMap.get(((IntConstant) argId).value) != null) {
                                                        resourceId = idNameMap.get(((IntConstant) argId).value);
                                                        return resourceId;
                                                    } else {
                                                        logger.error("todo");
                                                    }
                                                }
                                            }
                                        } else {
                                            if (((JAssignStmt) preUnit).getRightOp() instanceof JCastExpr) {
                                                localVar = ((JCastExpr) ((JAssignStmt) preUnit).getRightOp()).getOp();
                                            } else {
                                                localVar = ((JAssignStmt) preUnit).getRightOp();
                                            }
                                        }

                                    } else if (argId != null && ((JAssignStmt) preUnit).getLeftOp().equals(argId)) {
                                        if (((JAssignStmt) preUnit).getRightOp() instanceof StaticFieldRef) {
                                            resourceId = parseClassNameToResourceId(((StaticFieldRef) ((JAssignStmt) preUnit).getRightOp()).getField().getDeclaringClass().getName()) + "/" + ((StaticFieldRef) ((JAssignStmt) preUnit).getRightOp()).getField().getName();
                                            return resourceId;
                                        } else {
                                            logger.debug("something todo here");
                                        }
                                    }
                                } else if (preUnit instanceof JIdentityStmt) {
                                    if (((JIdentityStmt) preUnit).getLeftOp().equals(localVar)) {
                                        logger.debug("todo more func analysis");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return resourceId;
    }

    private HashSet<String> analyzeParameter(int paraIdx, SootMethod sm, Chain<SootClass> classes, String methodName, HashMap<String, HashSet<String>> apiMap) {
        Iterator<SootClass> it = classes.iterator();
        HashSet<String> targetMethod = new HashSet<String>();
        while (it.hasNext()) {
            SootClass sc = it.next();
            List<SootMethod> methods = sc.getMethods();
            for (SootMethod sm_ : methods) {
                if (!sm_.isConcrete()) {
                    continue;
                }
                //遍历找到方法的调用点，然后往回查参数的赋值
                for (Unit u : sm_.retrieveActiveBody().getUnits()) {
                    if (u instanceof JInvokeStmt) {
                        InvokeExpr invokeExpr = ((JInvokeStmt) u).getInvokeExpr();
                        if (invokeExpr.getMethod().equals(sm)) {
                            u.getUnitBoxes();
                            Value localVar = invokeExpr.getArg(paraIdx);
                            if (!(localVar instanceof JimpleLocal)) {
                                logger.warn("TODO");
                                continue;
                            }
                            Boolean endFlag = false;
                            Unit preUnit = u;
                            while (!endFlag) {
                                if (preUnit == null) {
                                    logger.error("todo");
                                    break;
                                }
                                preUnit = sm_.retrieveActiveBody().getUnits().getPredOf(preUnit);
                                if (preUnit instanceof AssignStmt) {
                                    if (((JAssignStmt) preUnit).getLeftOp().equals(localVar)) {
                                        preUnit.getUnitBoxes();
                                        //目前看有两种情况
                                        //第一种是本地变量就在当前方法内创建了对象然后赋值
                                        //第二种是本地变量赋值的是别的方法的返回值
                                        //第一种情况
                                        if (((JAssignStmt) preUnit).getRightOp() instanceof JNewExpr) {
                                            SootClass targetClass = ((JNewExpr) ((JAssignStmt) preUnit).getRightOp()).getBaseType().getSootClass();
                                            //直接找onClick函数
                                            List<SootMethod> targetMethods = targetClass.getMethods();
                                            for (SootMethod sm__ : targetMethods) {
                                                if (apiMap.get(methodName).contains(sm__.getName())) {
                                                    targetMethod.add(sm__.toString());
                                                    endFlag = true;
                                                    break;
                                                }
                                            }
                                        } else if (((JAssignStmt) preUnit).getRightOp() instanceof InvokeExpr) {
                                            //第二种情况
                                            SootMethod needToAnalyze = ((InvokeExpr) ((JAssignStmt) preUnit).getRightOp()).getMethod();
                                            analyzeFunc(needToAnalyze, methodName, apiMap);
                                        }

                                    }
                                } else if (preUnit instanceof JIdentityStmt) {
                                    if (((JIdentityStmt) preUnit).getLeftOp().equals(localVar)) {
                                        logger.debug("todo");
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        return targetMethod;
    }

    private HashSet<SootMethod> analyzeFunc(SootMethod needToAnalyze, String methodName, HashMap<String, HashSet<String>> apiMap) {
        HashSet<SootMethod> returnMethods = new HashSet<SootMethod>();
        if (!needToAnalyze.isConcrete()) {
            return null;
        } else {
            for (Unit u : needToAnalyze.retrieveActiveBody().getUnits()) {
                u.getUnitBoxes();
                //从return入手
                if (u instanceof JReturnStmt) {
                    Value returnValue = ((JReturnStmt) u).getOp();
                    Unit preUnit = u;
                    Boolean endFlag = false;
                    while (!endFlag) {
                        if (preUnit == null) {
                            logger.error("todo");
                            break;
                        }
                        preUnit = needToAnalyze.retrieveActiveBody().getUnits().getPredOf(preUnit);
                        if (preUnit instanceof AssignStmt) {
                            if (((JAssignStmt) preUnit).getLeftOp().equals(returnValue) && ((JAssignStmt) preUnit).getRightOp() instanceof JNewExpr) {
                                SootClass targetClass = ((JNewExpr) ((JAssignStmt) preUnit).getRightOp()).getBaseType().getSootClass();
                                //直接找onClick函数
                                List<SootMethod> targetMethods = targetClass.getMethods();
                                for (SootMethod sm_ : targetMethods) {
                                    if (apiMap.get(methodName).contains(sm_.getName())) {
                                        returnMethods.add(sm_);
                                        endFlag = true;
                                    }
                                }
                            }
                        } else if (preUnit instanceof JIdentityStmt) {
                            //TODO
                            logger.error("todo");
                        }
                    }
                }
            }
        }
        return returnMethods;
    }

    private void analyze() {
        int cnt = 0;
        for (HashSet<String> tmp : resourceIdToMethodMap.values()) {
            cnt += tmp.size();
        }

        int cnt2 = 0;
        HashSet<String> visited = new HashSet<String>();
        HashSet<String> record = new HashSet<String>();
        for (HashSet<String> tmp : resourceIdToMethodMap.values()) {
            for (String sm : tmp) {
                if (!visited.contains(sm)) {
                    visited.add(sm);
                } else {
                    record.add(sm);
                    cnt2++;
                }
            }
        }
        logger.info("finish");
    }

    public HashSet<String> getActivitySet() {
        HashSet<String> activitySet = new HashSet<>();

        List<SootClass> classes = new ArrayList<>(Scene.v().getApplicationClasses());

        // 查找所有Activity类
        for (SootClass sootClass : classes) {
            if (isSuperClassContainsActivity(sootClass)) {
                activitySet.add(sootClass.getName());
            }
        }

        return activitySet;
    }

    // 递归方法，获取最顶层父类
    public static Boolean isSuperClassContainsActivity(SootClass sootClass) {
        if (sootClass.hasSuperclass()) {
            SootClass superClass = sootClass.getSuperclass();
            if (superClass.getName().equals("android.app.Activity")) {
                return true;
            } else {
                return isSuperClassContainsActivity(superClass);
            }
        } else {
            return false;
        }
    }
}


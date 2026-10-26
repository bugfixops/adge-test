import utils.CMDHelper;
import utils.DBHelper;

import java.io.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.HostReceiver;
import utils.InfoflowAnalyzer;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import static java.lang.System.exit;

public class Main {
    private static final Logger logger = LoggerFactory.getLogger(Main.class);
    private static String DBPATH = null;

    private static String APKPATH = null;
    private static boolean SKIP_INSTALL = false;
    private static String HOME_PACKAGE = "";
    private static InfoflowAnalyzer analyzer;
    private static DBHelper db;
    private static HostReceiver fridaReceiver;

    private static HostReceiver uiReceiver;


    private static HashSet<String> activitySet = new HashSet<String>();

    private static HashMap<String, Integer> activityTriggerMap = new HashMap<>();

    private static ArrayList<Integer> times = new ArrayList<Integer>();

    private static Map<String, Object> results = new HashMap<>();

    private static int triggerCnt = 0;

    private static int sleepTime = 1000;

    public static void help() {
        logger.info("Usage: java -cp <classpath> Main   (settings are read from configures/config.json)");
    }

    public static boolean checkDevice() {
        String out = CMDHelper.run(new String[]{"adb", "devices"}, 60);
        logger.debug(out);
        for (String line : out.split("\n")) {
            if (line.trim().endsWith("\tdevice") || line.trim().matches("\\S+\\s+device")) {
                return true;
            }
        }
        return false;
    }

    public static boolean installAPK() {
        if (SKIP_INSTALL) {
            logger.info("SKIP_INSTALL is set, the APK was installed by the caller");
            return true;
        }
        String out = CMDHelper.run(new String[]{"adb", "install", "-r", "-g", "-t", APKPATH}, 600);
        logger.debug(out);
        return out.contains("Success");
    }

    public static void initialize() {
        Gson gson = new Gson();
        try (FileReader reader = new FileReader("configures" + File.separator + "config.json")) {
            JsonObject jsonObject = gson.fromJson(reader, JsonObject.class);

            // 获取 JSON 中的值
            DBPATH = jsonObject.get("DB_PATH").getAsString();
            APKPATH = jsonObject.get("APK_PATH").getAsString();
            sleepTime = jsonObject.get("SLEEP_TIME") == null ? 1000 : jsonObject.get("SLEEP_TIME").getAsInt();
            SKIP_INSTALL = jsonObject.get("SKIP_INSTALL") != null && jsonObject.get("SKIP_INSTALL").getAsBoolean();
            HOME_PACKAGE = jsonObject.get("HOME_PACKAGE") == null ? "" : jsonObject.get("HOME_PACKAGE").getAsString();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        if (DBPATH == null || APKPATH == null) {
            logger.error("DBPATH or APKPATH is null");
            exit(-1);
        }

        analyzer = InfoflowAnalyzer.getInstance();
        analyzer.setAppPath(APKPATH);

        analyzer.init();
        // The original also ran analyzer.initializeSoot() here. Nothing in the exploration uses the Soot scene
        // (the static results come from the database), so it is skipped; BuildDB runs it for the analysis.
        tryToGetResult();
        if (activitySet.isEmpty()) {
            getActivitiesFromManifest();
        }
        results.put("activityTriggerMap", activityTriggerMap);
        results.put("activitySet", activitySet);
        results.put("times", times);
        results.put("triggerCnt", triggerCnt);
        results.put("sleepTime", sleepTime);
        results.put("homePackage", HOME_PACKAGE);

        db = DBHelper.getInstance();
        db.loadDB(DBPATH);
        db.loadResourceIdToMethodMapFromDB();

        fridaReceiver = new HostReceiver(13337);
        uiReceiver = new HostReceiver(13331);

    }

    /** Loads the results of the earlier runs in this session (same file the Fuzzer writes), so they accumulate. */
    private static void tryToGetResult() {
        File file = new File("results" + File.separator + analyzer.getAppName() + File.separator + "result.json");
        if (file.exists()) {
            try (BufferedReader reader = new BufferedReader(new FileReader(file))) {
                String line;
                StringBuilder stringBuilder = new StringBuilder();
                while ((line = reader.readLine()) != null) {
                    stringBuilder.append(line);
                }
                Gson gson = new Gson();
                Map<String, Object> resultData = gson.fromJson(stringBuilder.toString(), Map.class);
                // 读取 "activityTriggerMap" 并转换为 HashMap<String, Integer>
                Map<String, Double> tempMap = (Map<String, Double>) resultData.get("activityTriggerMap");
                // 转换 Double 到 Integer
                for (Map.Entry<String, Double> entry : tempMap.entrySet()) {
                    activityTriggerMap.put(entry.getKey(), entry.getValue().intValue());
                }
                for (String activity : (ArrayList<String>) resultData.get("activitySet")) {
                    activitySet.add(activity);
                }
                ArrayList<Double> times_ = (ArrayList<Double>) resultData.get("times");
                for (Double time : times_) {
                    times.add(time.intValue());
                }
                triggerCnt = ((Double) resultData.get("triggerCnt")).intValue();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /** The activities declared in the manifest (read with FlowDroid instead of apktool.bat + AndroidManifest.xml). */
    private static void getActivitiesFromManifest() {
        activitySet.addAll(analyzer.getManifestActivities());
        logger.info("activities in the manifest: " + activitySet.size());
    }

    public static void main(String[] args) {
        logger.info("run main");

        if (!checkDevice()) {
            help();
            logger.error("No device detected");
            exit(2);
        }

        initialize();

        if (!installAPK()) {
            logger.error("APK install failed");
            exit(3);
        }

        Fuzzer fuzzer = new Fuzzer(analyzer, db, fridaReceiver, uiReceiver, results);
        fuzzer.start();
        exit(fuzzer.isCompleted() ? 0 : 4);
    }
}

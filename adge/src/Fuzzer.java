import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import strategy.GuidedStrategy;
import ui.UIOperator;
import ui.UIRecorder;
import ui.UIVisited;
import utils.CMDHelper;
import utils.DBHelper;
import utils.HostReceiver;
import utils.InfoflowAnalyzer;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

import static java.lang.Thread.sleep;

public class Fuzzer {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private InfoflowAnalyzer analyzer;
    private DBHelper db;

    private UIRecorder recorder = new UIRecorder();

    private HostReceiver fridaReceiver;

    private HostReceiver uiReceiver;

    private HashSet<String> activitySet;

    private HashMap<String, Integer> activityTriggerMap;

    private ArrayList<Integer> times;

    private Integer startTime;

    private int triggerCnt;

    private int sleepTime = 1000;

    private String homePackage;

    private boolean resultsWritten = false;

    private boolean completed = false;

    public Fuzzer(InfoflowAnalyzer analyzer, DBHelper db, HostReceiver fridaReceiver, HostReceiver uiReceiver,
                  Map<String, Object> results) {
        this.analyzer = analyzer;
        this.db = db;
        this.fridaReceiver = fridaReceiver;
        this.uiReceiver = uiReceiver;
        this.activitySet = (HashSet<String>) results.get("activitySet");
        this.activityTriggerMap = (HashMap<String, Integer>) results.get("activityTriggerMap");
        this.times = (ArrayList<Integer>) results.get("times");
        this.triggerCnt = (Integer) results.get("triggerCnt");
        // the original read SLEEP_TIME from the config but never passed it on
        this.sleepTime = (Integer) results.get("sleepTime");
        this.homePackage = (String) results.get("homePackage");
    }

    /** True when the exploration ended by itself (all actions tried or the app was left), not by a time limit. */
    public boolean isCompleted() {
        return completed;
    }

    public void start() {
        logger.info("Start fuzzing");
        // a time limit (SIGTERM from `timeout`) must not lose this run's results
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                if (startTime != null) {
                    writeResultsToJson();
                }
            } catch (Throwable t) {
                logger.error("could not write the results on shutdown", t);
            }
        }));
        while (true) {
            if (!runOnce()) {
                logger.info("Finish fuzzing");
                break;
            }
        }
    }

    private String fullActivityName(String appName, String mainActivity) {
        return mainActivity.startsWith(".") ? appName + mainActivity : (mainActivity.contains(appName) ? mainActivity : (mainActivity.contains(".") ? mainActivity : appName + "." + mainActivity));
    }

    private boolean isAppRunning(String appName) {
        return !CMDHelper.run(new String[]{"adb", "shell", "pidof", appName}, 30).trim().isEmpty();
    }

    public boolean runOnce() {
        // launch main activity
        String appName = analyzer.getAppName();
        logger.info("appName: " + appName);
        String mainActivity = analyzer.getMainActivity();
        if (mainActivity == null) {
            logger.error("no launchable activity in the manifest");
            return false;
        }
        String component = appName + "/" + fullActivityName(appName, mainActivity);
        //启动前先切回主界面，避免启动直接上次失败的界面
        CMDHelper.execute(new String[]{"adb", "shell", "input", "keyevent", "3"});
        // The original started the app through a helper launcher app (com.uiauto.uiautolauncher) that is not part of
        // ADGE's sources; the app is started directly with the launcher intent instead.
        // The original checked `ps | grep <package>`, which on Android 8+ only lists the shell's own processes.
        boolean started = false;
        for (int attempt = 1; attempt <= 10 && !started; attempt++) {
            CMDHelper.execute(new String[]{"adb", "shell", "am", "start", "-a", "android.intent.action.MAIN",
                    "-c", "android.intent.category.LAUNCHER", "-n", component}, 60);
            for (int i = 0; i < 5 && !started; i++) {
                try {
                    sleep(1000);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
                started = isAppRunning(appName);
            }
            if (!started) {
                logger.warn("app not running after start attempt " + attempt);
            }
        }
        if (!started) {
            logger.error("could not start " + component);
            return false;
        }

        // while, dump, strategy.getnext, perform action.
        UIVisited visited = new UIVisited();
        UIOperator uiOperator = new UIOperator(new GuidedStrategy(visited, db, analyzer), recorder, uiReceiver, appName, homePackage);
        ServerSocket fridaReceiverServerSocket = fridaReceiver.getServerSocket();

        //send signal to start frida script
        Socket fridaControl = null;
        for (int attempt = 0; attempt < 30 && fridaControl == null; attempt++) {
            try {
                fridaControl = new Socket("localhost", 13338);
                logger.debug("Connected to frida script Socket Server");
                PrintWriter writer = new PrintWriter(fridaControl.getOutputStream(), true);
                writer.println("start");
            } catch (IOException e) {
                try {
                    sleep(1000);
                } catch (InterruptedException ignored) {
                }
            }
        }
        if (fridaControl == null) {
            logger.error("frida script is not listening on 13338; exploring without trigger feedback");
        }
        try {
            sleep(5000);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        startTime = (int) (System.currentTimeMillis() / 1000) - 2;
        synchronized (this) {
            activityTriggerMap.put(fullActivityName(appName, mainActivity), ((int) (System.currentTimeMillis() / 1000)) - startTime);
        }

        try {
            Socket socket = null;
            BufferedReader reader = null;
            try {
                fridaReceiverServerSocket.setSoTimeout(60000);
                socket = fridaReceiverServerSocket.accept();
                reader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            } catch (SocketTimeoutException e) {
                logger.error("frida script did not connect back on 13337; exploring without trigger feedback");
            }
            boolean stop = false;
            int count = 0;
            while (!stop) {
                uiOperator.update_layout();
                int ret = uiOperator.fuzzOne();
                //sleep
                sleep(sleepTime);
                // read every trigger that arrived (the original read at most one line per step and checked the socket
                // instead of the reader's buffer, so triggers could wait for later steps)
                while (reader != null && reader.ready()) {
                    String message = reader.readLine();
                    if (message == null) {
                        break;
                    }
                    if (message.contains("trigger")) {
                        logger.debug("Recevied: " + message);
                        logger.info("Received trigger signal.");
                        String activity = extractMessage(message);
                        if (activity != null) {
                            recorder.writeToFile(appName, activity);
                            synchronized (this) {
                                triggerCnt++;
                            }
                            count++;
                        }
                    }
                }
                if (ret == -1) {
                    //press back button
                    CMDHelper.execute(new String[]{"adb", "shell", "input", "keyevent", "4"});
                } else if (ret == 1) {
                    completed = true;
                    writeResultsToJson();
                    logger.info("finish fuzzing");

                    break;
                } else if (ret == 2) {
                    //狗屎流氓应用劫持了返回
                    CMDHelper.execute(new String[]{"adb", "shell", "input", "keyevent", "4"});
                    CMDHelper.execute(new String[]{"adb", "shell", "input", "keyevent", "4"});
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        } catch (InterruptedException e) {
            throw new RuntimeException(e);
        }
        return false;
    }

    private String extractMessage(String message) {
        // "trigger : activity \n"
        String[] parts = message.split(":", 2);
        if (parts.length < 2) {
            return null;
        }
        String activity = parts[1].trim();
        int endTime = (int) (System.currentTimeMillis() / 1000);
        int spendTime = endTime - startTime;
        synchronized (this) {
            if (activityTriggerMap.containsKey(activity)) {
                int oldTime = activityTriggerMap.get(activity);
                if (spendTime > oldTime) {
                    spendTime = oldTime;
                }
            }
            activityTriggerMap.put(activity, spendTime);
        }
        logger.info("Activity: " + activity + " Time: " + spendTime);
        return activity;
    }

    private synchronized void writeResultsToJson() {
        if (resultsWritten) {
            return;
        }
        resultsWritten = true;
        int endTime = (int) (System.currentTimeMillis() / 1000);
        int spendTime = endTime - startTime;
        times.add(spendTime);
        //计算平均时间
        int sum = 0;
        for (int time : times) {
            sum += time;
        }
        int averageTime = sum / times.size();
        int averageTriggerTime = triggerCnt == 0 ? -1 : sum / triggerCnt;
        // 计算覆盖率 (percentage of the manifest's activities that were triggered; -1 when the manifest list is empty)
        int coverage = activitySet.isEmpty() ? -1 : activityTriggerMap.size() * 100 / activitySet.size();
        String appName = analyzer.getAppName();
        String directoryPath = "results" + File.separator + appName;
        String fileName = directoryPath + File.separator + "result.json";

        int averageActivityTriggerTime = 0;
        for (Integer time : activityTriggerMap.values()) {
            averageActivityTriggerTime += time;
        }
        averageActivityTriggerTime = activityTriggerMap.size() == 0 ? -1 : averageActivityTriggerTime / activityTriggerMap.size();

        // 创建目录
        File directory = new File(directoryPath);
        if (!directory.exists()) {
            directory.mkdirs();
        }

        // 准备数据
        Map<String, Object> resultData = new HashMap<>();
        resultData.put("activityTriggerMap", activityTriggerMap);
        resultData.put("activitySet", activitySet);
        resultData.put("coverage", coverage);
        resultData.put("times", times);
        resultData.put("averageTime", averageTime);
        resultData.put("triggerCnt", triggerCnt);
        resultData.put("averageTriggerTime", averageTriggerTime);
        resultData.put("averageActivityTriggerTime", averageActivityTriggerTime);

        // 初始化Gson
        Gson gson = new Gson();
        try (FileWriter writer = new FileWriter(fileName)) {
            // 写入JSON数据
            writer.write(gson.toJson(resultData));
        } catch (IOException e) {
            throw new RuntimeException("Error writing JSON results to file", e);
        }
        logger.info("results written to " + fileName);
    }

}

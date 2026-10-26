package ui;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.select.Elements;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import strategy.IStrategy;
import strategy.RandomStrategy;
import ui.data.Action;
import ui.data.UINode;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.HashSet;

import ui.data.WidgetRecorder;
import utils.CMDHelper;
import org.apache.commons.io.FileUtils;
import utils.HostReceiver;

import static java.lang.Thread.sleep;

public class UIOperator {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private IStrategy strategy;
    private HashSet<UINode> operables;

    private UIRecorder recorder;

    private Document mDoc = null;

    private Document mOldDoc = null;

    private Document mWidgetDoc = null;

    private HashMap<String, HashMap<Action, WidgetRecorder>> mWidgetMap = new HashMap<>();
    private Document mLauncherDoc = null;

    private boolean isBackToLauncher = false;

    private boolean needIterateWidget = false;

    private Action widgetSavedAction = null;

    private UIRecorder widgetSavedRecorder = null;
    private ServerSocket uiReceiver;
    private BufferedReader uiReceiverReader = null;
    private Socket socket_client;

    private String appName;

    private Action widgetAction;
    private int mBackCnt = 0;//为了能从那些需要快速两次返回的流氓应用返回

    private int mCheckCnt = 0;

    // package of the home screen: reaching it means the app was left (the original looked for its helper launcher app)
    private String homePackage;

    public UIOperator(IStrategy strategy, UIRecorder recorder, HostReceiver uiReceiver, String appName, String homePackage) {
        this.strategy = strategy;
        this.recorder = recorder;
        this.uiReceiver = uiReceiver.getServerSocket();
        this.appName = appName;
        this.homePackage = homePackage == null ? "" : homePackage;
//        initLauncherXML();
        initSocket();
    }


    private void initSocket() {
        // the uiautomator2 bridge opens its server only after it has connected to us; retry instead of failing once
        for (int attempt = 0; attempt < 60 && socket_client == null; attempt++) {
            try {
                sleep(500);
                socket_client = new Socket("localhost", 13332);
                logger.debug("Connected to uiautomator2 Socket Server");
            } catch (IOException e) {
                try {
                    sleep(500);
                } catch (InterruptedException ignored) {
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
        if (socket_client == null) {
            throw new IllegalStateException("uiautomator2 bridge is not listening on 13332");
        }
        try {
            if (uiReceiverReader == null) {
                uiReceiver.setSoTimeout(120000);
                Socket socket = uiReceiver.accept();
                uiReceiverReader = new BufferedReader(new InputStreamReader(socket.getInputStream()));
            }
        } catch (IOException e) {
            throw new IllegalStateException("uiautomator2 bridge did not connect on 13331", e);
        }
    }

    public void update_layout() {
        operables = new HashSet<UINode>();
        logger.debug("update UI tree");
//        CMDHelper.execute(new String[]{"adb", "shell", "uiautomator", "dump"});
//        CMDHelper.execute(new String[]{"adb", "pull", "/sdcard/window_dump.xml"});
        //改成uiautomator2
        try {
            PrintWriter writer = new PrintWriter(socket_client.getOutputStream(), true);
            writer.println("dump");
            while (true) {
                String line = uiReceiverReader.readLine();
                if (line == null) {
                    // the original spun here forever when the bridge went away
                    throw new IllegalStateException("uiautomator2 bridge closed the connection");
                }
                if (line.contains("dumped")) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("uiautomator2 bridge failed", e);
        }

        File dump = new File("window_dump.xml");

        Document doc = null;
        try {
            //有存在点击按钮后没有产生新界面的情况，需要判断是否是同一个页面，把上一个操作pop了
            mOldDoc = mDoc;
            String xml = FileUtils.readFileToString(dump, "UTF-8");
            doc = Jsoup.parse(dump, "UTF-8");
            Element rootNode = doc.selectFirst("hierarchy");
            Document tmpDoc = new Document("");
            if (rootNode == null) {
                logger.error("rootNode is null");
            } else {
                filterNode(rootNode);
                tmpDoc.appendChild(rootNode);
            }
            mDoc = tmpDoc;
            if (mOldDoc != null && mDoc != null && mOldDoc.outerHtml().equals(mDoc.outerHtml())) {
                recorder.delete();
            }
            if (xml.contains("com.uiauto.uiautolauncher")
                    || (!homePackage.isEmpty() && !homePackage.equals(appName) && xml.contains("package=\"" + homePackage + "\""))) {
                isBackToLauncher = true;
            }
            if (mDoc != null && mWidgetMap.containsKey(mDoc.outerHtml())) {
                for (Action action : mWidgetMap.get(mDoc.outerHtml()).keySet()) {
                    WidgetRecorder tmp = mWidgetMap.get(mDoc.outerHtml()).get(action);
                    if (tmp.getCnt() > 0) {
                        needIterateWidget = true;
                        widgetAction = action;
                        break;
                    }
                }
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        Elements nodes = mDoc.select("node");
        for (Element node : nodes) {
            if (!node.attr("package").equals(appName)) {
                logger.error("package name not match");
                continue;
            }

            UINode tmpNode = new UINode(node);
            tmpNode.init();
            if (tmpNode.isClickable()) {
                this.operables.add(tmpNode);
            }
        }

//        //保存触发widget现场
//        if (widgetFlag && operables.size() != 0) {
//            if (mWidgetDoc == null) {
//                widgetSavedAction = recorder.getTopAction();
//                if (widgetSavedAction == null) return;
//                mWidgetDoc = mOldDoc;
//                widgetSavedRecorder = recorder;
//            }
//        }
    }

    public int fuzzOne() {
        if (isBackToLauncher) {
            try {
                PrintWriter writer = new PrintWriter(socket_client.getOutputStream(), true);
                writer.println("exit");
            } catch (IOException e) {
                e.printStackTrace();
            }
            return 1;
        }
        Action res = null;
        if (needIterateWidget) {
            HashMap<Action, WidgetRecorder> tmp = mWidgetMap.get(mDoc.outerHtml());
            WidgetRecorder tmp_ = tmp.get(widgetAction);
            res = widgetAction;
            recorder = tmp_.getWidgetSavedRecorder();
            needIterateWidget = false;
        } else {
            res = this.strategy.getNext(this.operables);
            while (res != null && res.getAction() != null && mCheckCnt > 5) {
                if (res.getAction().contains("check")) {
                    res = this.strategy.getNext(this.operables);
                } else {
                    break;
                }
            }
        }

        if (res == null) {
            //所有操作都已经尝试过了
            if (recorder.isEmpty()) {
                try {
                    PrintWriter writer = new PrintWriter(socket_client.getOutputStream(), true);
                    writer.println("exit");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return 1;
            }
            //跳转到别的app
            if (mDoc.select("node").size() == 0) {
                mBackCnt++;
                if (mBackCnt > 3) {
                    if (mBackCnt > 6) {
                        // gg
                        return 1;
                    }
                    return 2;
                }
                return -1;
            }
            mBackCnt = 0;
            mCheckCnt = 0;
            recorder.delete();
            return -1;
        }
        if (res.getAction() == null) {
            mBackCnt = 0;
            logger.error("strategy return null action");
            return -1;
        } else {
            mBackCnt = 0;
            if (res.getNode().getClassName().contains("android.widget")
                    && !res.getNode().getClassName().contains("android.widget.Button")
                    && !res.getNode().getClassName().contains("android.widget.LinearLayout")
                    && !res.getNode().getClassName().contains("android.widget.RelativeLayout")
                    && !res.getNode().getClassName().contains("android.widget.FrameLayout")
                    && !res.getNode().getClassName().contains("Text")
                    && !res.getAction().contains("check")
                    && !res.getAction().contains("scroll")
                    && !res.getAction().contains("long-click")) {
                String mDocHtml = mDoc.outerHtml();
                if (mWidgetMap.containsKey(mDocHtml) && mWidgetMap.get(mDocHtml).containsKey(res)) {
                    WidgetRecorder tmp = mWidgetMap.get(mDocHtml).get(res);
                    if (tmp.getCnt() > 0) {
                        tmp.setCnt(tmp.getCnt() - 1);
                        tmp.setWidgetSavedRecorder(recorder);
                        HashMap<Action, WidgetRecorder> tmp_ = mWidgetMap.get(mDocHtml);
                        tmp_.put(res, tmp);
                        mWidgetMap.put(mDocHtml, tmp_);
                    } else {

                    }
                } else {
                    HashMap<Action, WidgetRecorder> tmp = new HashMap<>();
                    WidgetRecorder tmp_ = new WidgetRecorder();
                    tmp_.setWidgetSavedRecorder(recorder);
                    tmp.put(res, tmp_);
                    mWidgetMap.put(mDocHtml, tmp);
                }
            }
            if (res.getAction().contains("check")) {
                mCheckCnt++;
            } else {
                mCheckCnt = 0;
            }

            recorder.record(res);
            res.getNode().performAction(res.getAction());
            return 0;
        }
    }

    public int fuzzone_random() {
        Action res = null;
        res = this.strategy.getNext(this.operables);
        if (res == null) {
            //所有操作都已经尝试过了
            if (recorder.isEmpty()) {
                try {
                    PrintWriter writer = new PrintWriter(socket_client.getOutputStream(), true);
                    writer.println("exit");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                return 1;
            }
            //跳转到别的app
            if (mDoc.select("node").size() == 0) {
                mBackCnt++;
                if (mBackCnt > 3) {
                    if (mBackCnt > 6) {
                        // gg
                        return 1;
                    }
                    return 2;
                }
                return -1;
            }
            mBackCnt = 0;
            mCheckCnt = 0;
            recorder.delete();
            return -1;
        }

        if (res.getAction() == null) {
            mBackCnt = 0;
            logger.error("strategy return null action");
            return -1;
        } else {
            mBackCnt = 0;
            recorder.record(res);
            res.getNode().performAction(res.getAction());
            return 0;
        }
    }

    private void initLauncherXML() {
        File dump = new File("configures" + File.separator + "launcher_window_dump.xml");
        try {
            String launcher_xml = FileUtils.readFileToString(dump, "UTF-8");
            Document doc = Jsoup.parse(launcher_xml, "UTF-8");
            Element rootNode = doc.selectFirst("hierarchy");
            if (rootNode == null)
                logger.error("rootNode is null");
            filterLauncherNode(rootNode);
            Document tmpDoc = new Document("");
            tmpDoc.appendChild(rootNode);
            mLauncherDoc = tmpDoc;
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private boolean filterLauncherNode(Element node) {
        Elements childNodes = node.select("> node");

        boolean containsTargetPackage = false;

        for (Element child : childNodes) {
            boolean childHasTargetPackage = filterNode(child);
            if (!childHasTargetPackage) {
                child.remove();
            } else {
                containsTargetPackage = true;
            }
        }

        if (node.attr("package").equals("com.uiauto.uiautolauncher")) {
            containsTargetPackage = true;
        }

        return containsTargetPackage;
    }


    // 递归筛选节点，保留指定package的节点及其父节点
    private boolean filterNode(Element node) {
        Elements childNodes = node.select("> node");

        boolean containsTargetPackage = false;

        for (Element child : childNodes) {
            boolean childHasTargetPackage = filterNode(child);
            if (!childHasTargetPackage) {
                child.remove();
            } else {
                containsTargetPackage = true;
            }
        }

        if (node.attr("package").equals(appName)) {
            containsTargetPackage = true;
        }

        return containsTargetPackage;
    }
}
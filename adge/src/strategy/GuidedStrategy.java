package strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import soot.SootMethod;
import ui.UIRecorder;
import ui.UIVisited;
import ui.data.Action;
import ui.data.UINode;
import utils.DBHelper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;

import utils.InfoflowAnalyzer;

public class GuidedStrategy implements IStrategy {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private DBHelper db;
    private HashMap<String, String> resourceMap = new HashMap<String, String>();
    private InfoflowAnalyzer analyzer;
    private UIVisited visited;
    private static final int CASE1 = 1;
    private static final int CASE2 = 2;
    private static final int CASE3 = 3;

    private static final int CASE4 = 4;

    private static final int CASE5 = 5;


    public GuidedStrategy(UIVisited visited, DBHelper db, InfoflowAnalyzer analyzer) {
        this.visited = visited;
        this.db = db;
        this.analyzer = analyzer;
    }

    public Action getNext(HashSet<UINode> operables) {
        // get next action
        int shortest_distance = Integer.MAX_VALUE;
//        Map<String, HashSet<SootMethod>> resourceIdToMethodMap = analyzer.getResourceIdToMethodMap();
        Map<String, HashSet<String>> resourceIdToMethodMap = db.getResourceIdToMethodMap();
        ArrayList<Action> actions = new ArrayList<Action>();
        ArrayList<Action> actions2 = new ArrayList<Action>();
        ArrayList<Action> actions3 = new ArrayList<Action>();
        ArrayList<Action> actions4 = new ArrayList<Action>();
        ArrayList<Action> actions5 = new ArrayList<Action>();
        ArrayList<Action> actions6 = new ArrayList<Action>();
        Action res = null;
        for (UINode node : operables) {
            for (String action : node.getActions()) {
                Action tmp = new Action(node, action);
                if (visited.shouldDrop(tmp)) {
                    continue;
                } else {
                    switch (judgeAction(tmp)) {
                        case CASE1://TaintedICFG
                            actions.add(tmp);
                            break;
                        case CASE2://ICC
                            actions2.add(tmp);
                            break;
                        case CASE3://widget && resid != "
//                            actions3.add(tmp);
//                            break;
                        case CASE4://widget && resid == ""
                            actions4.add(tmp);
                            break;
                        case CASE5://other
                            actions5.add(tmp);
                            break;
                        default:
                            actions6.add(tmp);
                            break;
                    }
                }

            }
        }
        if (!actions.isEmpty()) {
            //TODO
            //直接从actions里选？
            Random random = new Random();
            int nodeRandomIndex = random.nextInt(actions.size());
            res = actions.get(nodeRandomIndex);
        } else if (!actions2.isEmpty()) {
            //TODO
            //如果actions没有，则从actions2里选？
            Random random = new Random();
            int nodeRandomIndex = random.nextInt(actions2.size());
            res = actions2.get(nodeRandomIndex);
        } else if (!actions3.isEmpty()) {
            Random random = new Random();
            int nodeRandomIndex = random.nextInt(actions3.size());
            res = actions3.get(nodeRandomIndex);
        } else if (!actions4.isEmpty()) {
            Random random = new Random();
            int nodeRandomIndex = random.nextInt(actions4.size());
            res = actions4.get(nodeRandomIndex);
        } else if (!actions5.isEmpty()) {
            Random random = new Random();
            int nodeRandomIndex = random.nextInt(actions5.size());
            res = actions5.get(nodeRandomIndex);
        } else if (!actions6.isEmpty()) {
            Random random = new Random();
            int nodeRandomIndex = random.nextInt(actions6.size());
            res = actions6.get(nodeRandomIndex);
        }

        if (res != null) {
            visited.record(res);
            return res;
        } else {
            //TODO
            return null;
        }
    }

    public int judgeAction(Action action) {
        //TODO
        int res = -1;
        String resourceId = action.getNode().getResourceId();
        String className = action.getNode().getClassName();
//        HashMap<String, HashSet<SootMethod>> resourceIdToMethodMap = analyzer.getResourceIdToMethodMap();
        Map<String, HashSet<String>> resourceIdToMethodMap = db.getResourceIdToMethodMap();

        if (resourceIdToMethodMap.get(resourceId) == null) {
            //处理android.widget.* 和 android.view.ViewGroup 的情况
            if (className.contains("android.widget") || className.contains("android.view.ViewGroup")) {
                return resourceId == "" ? CASE4 : CASE3;
            }
            return -1;
        }
//        HashSet<SootMethod> sms = resourceIdToMethodMap.get(resourceId);
        HashSet<String> sms = resourceIdToMethodMap.get(resourceId);

        try {
            //查询数据库，看methods有没有在TaintedICFG上的
            for (String sm : sms) {
                if (isInTaintedICFG(sm)) {
                    return CASE1;
                }
            }
            //查询数据库看是否有ICC
            for (String sm : sms) {
                if (isICC(sm)) {
                    return CASE2;
                }
            }
            //处理android.widget.* 和 android.view.ViewGroup 的情况
            if (className.contains("android.widget") || className.contains("android.view.ViewGroup")) {
                return resourceId == "" ? CASE4 : CASE3;
            }
            //既没有在TaintedICFG上，也没有ICC
            return CASE5;
        } catch (SQLException e) {
            logger.error("error in judgeAction");
        }
        return -1;
    }

    private boolean isInTaintedICFG(String sm) throws SQLException {
        ResultSet res = db.query("SELECT * FROM Methods WHERE name = '"
                + sm.toString().replace("'", "''")
                + "'"
                + "AND tainted = 1");
        if (res.next()) {
            return true;
        }
        return false;
    }

    private boolean isICC(String sm) throws SQLException {
        ResultSet res = db.query("SELECT * FROM Methods WHERE name = '"
                + sm.toString().replace("'", "''")
                + "'"
                + "AND icc = 1");
        if (res.next()) {
            return true;
        }
        return false;
    }

    public int calculateScore(UINode node, String action, Map<String, HashSet<SootMethod>> resourceIdToMethodMap) {
        //先处理click
        if (action.equals("click")) {
            String resourceId = node.getResourceId();
            if (resourceIdToMethodMap.get(resourceId) == null) {
                logger.error(resourceId + " not found in resource map");
                return -1;
            }
            HashSet<SootMethod> sms = resourceIdToMethodMap.get(resourceId);

        }
        return -1;
    }

    public void loadSeeds() {
        //TODO
    }

    public boolean isTriggered() {
        //TODO
        return false;
    }


}

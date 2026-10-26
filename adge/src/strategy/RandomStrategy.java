package strategy;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ui.UIVisited;
import ui.data.Action;
import ui.data.UINode;
import utils.DBHelper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Map;
import java.util.Random;

public class RandomStrategy implements IStrategy {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private UIVisited visited;
    private DBHelper db;

    public RandomStrategy(UIVisited visited, DBHelper db) {
        this.visited = visited;
        this.db = db;
    }

    public Action getNext(HashSet<UINode> operables) {
        // get next action
        Action res = null;
        ArrayList<Action> actions = new ArrayList<Action>();
        ArrayList<Action> actions2 = new ArrayList<Action>();
        for (UINode node : operables) {
            for (String action : node.getActions()) {
                Action tmp = new Action(node, action);
                if (visited.shouldDrop(tmp)) {
                    continue;
                } else {
                    if (judgeAction(tmp)) {
                        actions.add(tmp);
                    } else {
                        actions2.add(tmp);
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
        }
        // if node is None and action is None:
        // TODO, press back button or restart app
        // raise Exception("No operable node found")
        if (res != null) {
            visited.record(res);
        }
        return res;
    }

    public boolean judgeAction(Action action) {
        //TODO
        int res = -1;
        String resourceId = action.getNode().getResourceId();
        String className = action.getNode().getClassName();
//        HashMap<String, HashSet<SootMethod>> resourceIdToMethodMap = analyzer.getResourceIdToMethodMap();
        Map<String, HashSet<String>> resourceIdToMethodMap = db.getResourceIdToMethodMap();

        if (resourceIdToMethodMap.get(resourceId) == null) return false;
//        HashSet<SootMethod> sms = resourceIdToMethodMap.get(resourceId);
        HashSet<String> sms = resourceIdToMethodMap.get(resourceId);

        try {
            //查询数据库，看methods有没有在TaintedICFG上的
            for (String sm : sms) {
                if (isInTaintedICFG(sm)) {
                    return true;
                }
            }
            //查询数据库看是否有ICC
            for (String sm : sms) {
                if (isICC(sm)) {
                    return true;
                }
            }

            return false;
        } catch (SQLException e) {
            logger.error("error in judgeAction");
        }
        return false;
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
}

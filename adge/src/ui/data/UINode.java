package ui.data;

import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import utils.CMDHelper;

import java.util.HashSet;
import java.util.Random;

public class UINode {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Element node;
    private HashSet<String> actions;

    public UINode(Element node) {
        this.node = node;
        this.actions = new HashSet<String>();
    }

    public Element getNode() {
        return node;
    }

    public void init() {
        parseNode();
    }

    private void parseNode() {
        if (this.node.attr("checkable").equals("true")) this.actions.add("check");
        if (this.node.attr("clickable").equals("true")) this.actions.add("click");
        if (this.node.attr("long-clickable").equals("true")) this.actions.add("long-click");
        if (this.node.attr("scrollable").equals("true")) this.actions.add("scroll");
    }

    public Boolean isClickable() {
        return (this.node.attr("clickable").equals("true")
                || this.node.attr("checkable").equals("true")
                || this.node.attr("long-clickable").equals("true")
                || this.node.attr("scrollable").equals("true"));
    }

    public String getResourceId() {
//        if (this.node.attr("resource-id").contains("/"))
//            return this.node.attr("resource-id").split("/")[1];
//        else
//            return this.node.attr("resource-id");
        return this.node.attr("resource-id");
    }

    public String getClassName() {
        return this.node.attr("class");
    }

    public HashSet<String> getActions() {
        return actions;
    }

    public void performAction(String action) {
        // TODO: Integrate uiautomator2
        logger.info(String.format("perform action: %s on %s-%s", action, this.node.attributes(), this.node.attr("resource-id")));
        if (action.equals("click")) {
            String[] leftPos = this.node.attr("bounds").split("]\\[")[0].split("\\[")[1].split(",");
            String[] rigehtPos = this.node.attr("bounds").split("]\\[")[1].split("]")[0].split(",");
            String[] cmd = new String[]{"adb", "shell", "input", "tap",
                    String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                    String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 2))};
            CMDHelper.execute(cmd);
            return;
        } else if (action.equals("long-click")) {
            String[] leftPos = this.node.attr("bounds").split("]\\[")[0].split("\\[")[1].split(",");
            String[] rigehtPos = this.node.attr("bounds").split("]\\[")[1].split("]")[0].split(",");
            String[] cmd = new String[]{"adb", "shell", "input", "swipe",
                    String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                    String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 2)),
                    String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                    String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 2)),
                    "1000"};
            CMDHelper.execute(cmd);
            return;
        } else if (action.equals("scroll")) {
            //todo
//            logger.error("todo");
            Random random = new Random();
            String[] leftPos = this.node.attr("bounds").split("]\\[")[0].split("\\[")[1].split(",");
            String[] rigehtPos = this.node.attr("bounds").split("]\\[")[1].split("]")[0].split(",");
            if (random.nextBoolean()) {
                String[] cmd = new String[]{"adb", "shell", "input", "swipe",
                        String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                        String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 3)),
                        String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                        String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 3) * 2),
                        "100"};
                CMDHelper.execute(cmd);
            } else {
                String[] cmd = new String[]{"adb", "shell", "input", "swipe",
                        String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                        String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 3) * 2),
                        String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                        String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 3)),
                        "100"};
                CMDHelper.execute(cmd);
            }
            return;
        } else if (action.equals("check")) {
            String[] leftPos = this.node.attr("bounds").split("]\\[")[0].split("\\[")[1].split(",");
            String[] rigehtPos = this.node.attr("bounds").split("]\\[")[1].split("]")[0].split(",");
            String[] cmd = new String[]{"adb", "shell", "input", "tap",
                    String.valueOf(((Integer.parseInt(leftPos[0]) + Integer.parseInt(rigehtPos[0])) / 2)),
                    String.valueOf(((Integer.parseInt(leftPos[1]) + Integer.parseInt(rigehtPos[1])) / 2))};
            CMDHelper.execute(cmd);
            return;
        } else {
            logger.info("action not supported");
        }
    }


}

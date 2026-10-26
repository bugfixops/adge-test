package ui;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import ui.data.Action;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Stack;


public class UIRecorder {
    private final Logger logger = LoggerFactory.getLogger(getClass());
    private Stack<Action> actions = new Stack<Action>();

    public void record(Action action) {
        this.actions.push(action);
    }

    public boolean shouldDrop(Action action) {
        if (actions.contains(action)) return true;
        else return false;
    }

    public void delete() {
        if(!actions.isEmpty()){
            actions.pop();
        }
        else {
            logger.warn("no action to delete");
        }
    }

    public Action getTopAction() {
        try {
            return actions.pop();
        }catch (Exception e){
            logger.error("no action to get");
            return null;
        }
    }
    public boolean isEmpty() {
        return actions.isEmpty();
    }

    public boolean isTrigger() {
        //TODO
        return false;
    }

    public boolean canPressBack() {
        //TODO
        return true;
    }

    public void writeToFile(String appName, String activity) {
        try {
            File file = new File("results" + File.separator + appName);
            if (!file.exists()) {
                file.mkdirs(); // 创建目录
            }
            // the activity name comes from the app; keep it to safe file name characters
            String safeActivity = activity.replaceAll("[^A-Za-z0-9._$-]", "_");
            String path = "results" + File.separator + appName + File.separator + "path-" + safeActivity + ".txt";

            FileWriter fileWriter = new FileWriter(path);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);
            Stack<Action> tmp = (Stack<Action>) actions.clone();

            while(!tmp.isEmpty()){
                Action action = tmp.pop();
                bufferedWriter.write(String.format("perform action: %s on %s-%s", action.getAction(), action.getNode().getNode().attributes(), action.getNode().getNode().attr("resource-id")) + "\n");
            }
            bufferedWriter.close();
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }

}

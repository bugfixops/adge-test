package ui;

import ui.data.Action;

import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;

public class UIVisited {
    //TODO 改成callstack的hash来判断
    private HashSet<Action> actions = new HashSet<Action>();

    public void record(Action action) {
        this.actions.add(action);
    }

    public boolean shouldDrop(Action action) {
        if (
                action.getNode().getNode().attr("resource-id").contains("com.fsck.k9:id/floating_action_button") ||
        action.getNode().getNode().attr("resource-id").contains("biz.gyrus.yaab:id/btnDonate"))
            return true;
        if (actions.contains(action)) return true;
        else return false;
    }

    public void writeToFile(String path) {
        try {
            FileWriter fileWriter = new FileWriter(path);
            BufferedWriter bufferedWriter = new BufferedWriter(fileWriter);

            for (Action action : actions) {
                bufferedWriter.write(action.getNode() + " " + action.getAction() + "\n");
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

    }
}

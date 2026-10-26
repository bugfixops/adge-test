package strategy;

import ui.data.Action;
import ui.data.UINode;

import java.util.HashSet;

public interface IStrategy {
    public Action getNext(HashSet<UINode> operables);
}

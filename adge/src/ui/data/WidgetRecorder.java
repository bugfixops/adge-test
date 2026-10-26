package ui.data;

import ui.UIRecorder;

public class WidgetRecorder {
    private int cnt;
//    private Action widgetSavedAction;

    private UIRecorder widgetSavedRecorder;

    public WidgetRecorder() {
        this.cnt = 2;
    }

    public int getCnt() {
        return cnt;
    }

    public void setCnt(int cnt) {
        this.cnt = cnt;
    }

//    public Action getWidgetSavedAction() {
//        return widgetSavedAction;
//    }
//
//    public void setWidgetSavedAction(Action widgetSavedAction) {
//        this.widgetSavedAction = widgetSavedAction;
//    }

    public UIRecorder getWidgetSavedRecorder() {
        return widgetSavedRecorder;
    }

    public void setWidgetSavedRecorder(UIRecorder widgetSavedRecorder) {
        this.widgetSavedRecorder = widgetSavedRecorder;
    }
}

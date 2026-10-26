package ui.data;

public class Action {
    private String action;
    private UINode node;

    public Action(UINode node, String action){
        this.node = node;
        this.action = action;
    }

    public String getAction() {
        return action;
    }

    public UINode getNode() {
        return node;
    }

    @Override
    public int hashCode(){
//        int res = 0;
//        res += this.node.getNode().attr("resource-id").hashCode();
//        res += this.node.getNode().attr("text").hashCode();
//        res += this.node.getNode().attr("package").hashCode();
        int res = 0;
        res = this.node.getNode().outerHtml().hashCode();
        res = res * 31 + this.action.hashCode();
        return res;
    }

    @Override
    public boolean equals(Object obj) {
        return this.hashCode() == obj.hashCode();
    }
}

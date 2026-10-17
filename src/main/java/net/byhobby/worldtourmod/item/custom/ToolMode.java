package net.byhobby.worldtourmod.item.custom;

public enum ToolMode {
    CREATE_PATH("create_path"),
    CREATE_TRANSITION("create_transition");

    public final String id;

    ToolMode(String id) { this.id = id; }

    public ToolMode next() {
        return values()[(ordinal() + 1) % values().length];
    }

    public static ToolMode byOrdinal(int i) {
        ToolMode[] all = values();
        return all[Math.floorMod(i, all.length)];
    }
}
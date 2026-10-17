package net.byhobby.worldtourmod.data;

import java.util.List;

public record PathData(String id, List<Pos> points) {
    public Pos first() { return points.getFirst(); }
    public Pos last()  { return points.getLast(); }
}

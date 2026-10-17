package net.byhobby.worldtourmod.data;

import java.util.List;

/** points = the nodes you clicked; blocks = every block of the route (same dimension as the first point). */
public record PathData(String id, List<Pos> points, List<int[]> blocks) {
    public Pos first() { return points.getFirst(); }
    public Pos last()  { return points.getLast(); }
}
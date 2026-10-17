package net.byhobby.worldtourmod.data;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

import java.util.*;

public final class PathRouter {
    private static final Direction[] HORIZONTAL = {Direction.NORTH, Direction.EAST, Direction.SOUTH, Direction.WEST};
    private static final int NONE = 4;

    private static final int MARGIN_XZ = 12;        // search box around the two endpoints
    private static final int MARGIN_Y = 6;
    private static final int MAX_EXPANSIONS = 30_000;

    private static final double SLOPE_COST = 0.25;
    private static final double TURN_COST = 0.5;
    private static final double VERTICAL_COST = 4.0; // vertical columns are a last resort

    private record State(BlockPos pos, int dir, boolean flat) {}
    private record Node(State state, double g, double f, Node parent) {}
    public record Result(List<BlockPos> blocks, boolean complete) {}

    private PathRouter() {}

    /** A block is blocked if it is used, or sits directly above/below a used block. */
    public static boolean isBlocked(BlockPos c, Set<BlockPos> occ, BlockPos ignoreClearanceOf) {
        if (occ.contains(c)) return true;
        BlockPos up = c.above(), down = c.below();
        if (occ.contains(up) && !up.equals(ignoreClearanceOf)) return true;
        return occ.contains(down) && !down.equals(ignoreClearanceOf);
    }

    /**
     * Routes through all nodes in order. Returns every block (nodes included).
     * Stops at the first segment that can't be routed (complete = false).
     */
    public static Result routeAll(List<BlockPos> nodes, Set<BlockPos> saved) {
        List<BlockPos> out = new ArrayList<>();
        if (nodes.isEmpty()) return new Result(out, true);

        Set<BlockPos> occ = new HashSet<>(saved);
        BlockPos first = nodes.getFirst();
        if (isBlocked(first, occ, null)) return new Result(out, false);
        occ.add(first);
        out.add(first);

        for (int i = 1; i < nodes.size(); i++) {
            List<BlockPos> seg = findSegment(nodes.get(i - 1), nodes.get(i), occ);
            if (seg == null) return new Result(out, false);
            out.addAll(seg);
            occ.addAll(seg);
        }
        return new Result(out, true);
    }

    /** Blocks after `a` up to and including `b`, or null when no route exists. */
    public static List<BlockPos> findSegment(BlockPos a, BlockPos b, Set<BlockPos> occ) {
        if (a.equals(b) || isBlocked(b, occ, a)) return null;

        int minX = Math.min(a.getX(), b.getX()) - MARGIN_XZ, maxX = Math.max(a.getX(), b.getX()) + MARGIN_XZ;
        int minZ = Math.min(a.getZ(), b.getZ()) - MARGIN_XZ, maxZ = Math.max(a.getZ(), b.getZ()) + MARGIN_XZ;
        int minY = Math.min(a.getY(), b.getY()) - MARGIN_Y,  maxY = Math.max(a.getY(), b.getY()) + MARGIN_Y;

        PriorityQueue<Node> open = new PriorityQueue<>(Comparator.comparingDouble(Node::f));
        Map<State, Double> best = new HashMap<>();
        State start = new State(a, NONE, true);
        open.add(new Node(start, 0, heuristic(a, b), null));
        best.put(start, 0.0);

        int expanded = 0;
        while (!open.isEmpty() && expanded++ < MAX_EXPANSIONS) {
            Node cur = open.poll();
            if (cur.g() > best.getOrDefault(cur.state(), Double.MAX_VALUE)) continue;

            BlockPos p = cur.state().pos();
            if (p.equals(b)) return rebuild(cur);

            List<Node> candidates = new ArrayList<>();

            // Horizontal steps, each of them flat or +-1 in height
            for (int d = 0; d < 4; d++) {
                boolean turning = cur.state().dir() != NONE && cur.state().dir() != d;
                if (turning && !cur.state().flat()) continue; // corners need a flat approach
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos n = p.relative(HORIZONTAL[d]).offset(0, dy, 0);
                    double cost = 1 + (dy != 0 ? SLOPE_COST : 0) + (turning ? TURN_COST : 0);
                    candidates.add(new Node(new State(n, d, dy == 0), cur.g() + cost, 0, cur));
                }
            }
            // Vertical column steps
            for (int dy : new int[]{-1, 1}) {
                BlockPos n = p.offset(0, dy, 0);
                candidates.add(new Node(new State(n, NONE, true), cur.g() + VERTICAL_COST, 0, cur));
            }

            for (Node c : candidates) {
                BlockPos n = c.state().pos();
                if (n.getX() < minX || n.getX() > maxX || n.getZ() < minZ || n.getZ() > maxZ
                        || n.getY() < minY || n.getY() > maxY) continue;
                if (isBlocked(n, occ, a)) continue;
                if (c.g() >= best.getOrDefault(c.state(), Double.MAX_VALUE)) continue;
                best.put(c.state(), c.g());
                open.add(new Node(c.state(), c.g(), c.g() + heuristic(n, b), cur));
            }
        }
        return null;
    }

    private static double heuristic(BlockPos p, BlockPos b) {
        return Math.abs(p.getX() - b.getX()) + Math.abs(p.getZ() - b.getZ());
    }

    private static List<BlockPos> rebuild(Node end) {
        List<BlockPos> out = new ArrayList<>();
        for (Node n = end; n.parent() != null; n = n.parent()) out.add(n.state().pos());
        Collections.reverse(out);
        return out;
    }
}
package net.byhobby.worldtourmod.tour;

import net.byhobby.worldtourmod.data.*;
import net.minecraft.core.BlockPos;

import java.io.IOException;
import java.util.*;

public record TourRoute(List<Segment> segments, boolean reachesFinish, String problem) {

    public record Segment(String pathId, String dim, List<BlockPos> blocks) {}

    /** Follows start path -> active transition -> next path ... until the finish node. */
    public static TourRoute build() throws IOException {
        TourBounds tb = TourStore.get();
        if (tb.start() == null || tb.finish() == null) {
            return new TourRoute(List.of(), false, "Set the tour start and finish first.");
        }

        List<PathData> paths = PathStore.loadPaths().stream()
                .filter(p -> p.points() != null && !p.points().isEmpty())
                .toList();
        List<TransitionData> transitions = PathStore.loadTransitions();

        List<Segment> segs = new ArrayList<>();
        Set<String> visited = new HashSet<>();
        PathData cur = startingAt(paths, tb.start());
        if (cur == null) return new TourRoute(List.of(), false, "No path starts at the tour start.");

        while (cur != null) {
            if (!visited.add(cur.id())) {
                return new TourRoute(segs, false, "The tour loops back on itself.");
            }
            List<BlockPos> blocks = PathStore.blocksOf(cur);
            segs.add(new Segment(cur.id(), cur.first().dim(), blocks));

            Pos end = cur.last();
            if (end.equals(tb.finish())) return new TourRoute(segs, true, null);

            TransitionData t = transitions.stream()
                    .filter(x -> x.active && end.equals(x.from))
                    .findFirst().orElse(null);
            if (t == null) {
                return new TourRoute(segs, false, "No active transition at the end of " + cur.id() + ".");
            }
            cur = startingAt(paths, t.to);
        }
        return new TourRoute(segs, false, "A transition leads to no path.");
    }

    private static PathData startingAt(List<PathData> paths, Pos pos) {
        for (PathData p : paths) if (p.first().equals(pos)) return p;
        return null;
    }
}
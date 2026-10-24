package net.byhobby.worldtourmod.client;

import net.byhobby.worldtourmod.data.PathData;
import net.byhobby.worldtourmod.data.PathStore;
import net.byhobby.worldtourmod.data.Pos;
import net.byhobby.worldtourmod.data.TransitionData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class PortalMarker {
    private static final BlockState ACTIVE_FILL = Blocks.NETHER_PORTAL.defaultBlockState();
    private static final BlockState ACTIVE_FRAME = Blocks.EMERALD_BLOCK.defaultBlockState();
    private static final BlockState INACTIVE_FILL = Blocks.GLASS_PANE.defaultBlockState();
    private static final BlockState INACTIVE_FRAME = Blocks.REDSTONE_BLOCK.defaultBlockState();

    /** One portal: `node` is the path end (exit) or path start (entry) it belongs to. */
    public record Portal(Pos node, boolean exit, boolean active, Map<BlockPos, BlockState> blocks) {}

    public record Layout(List<Portal> portals, Map<String, Map<BlockPos, BlockState>> all) {
        /** Every portal in the dimension, for players holding the tool. */
        public Map<BlockPos, BlockState> forHolder(String dim) {
            return all.getOrDefault(dim, Map.of());
        }

        public Portal find(Pos node, boolean exit) {
            for (Portal p : portals) if (p.exit() == exit && p.node().equals(node)) return p;
            return null;
        }
    }

    private static Layout layout = null;

    private PortalMarker() {}

    public static void invalidate() { layout = null; }

    public static Layout layout() {
        if (layout == null) layout = build();
        return layout;
    }

    private static Layout build() {
        List<Portal> portals = new ArrayList<>();
        Map<String, Map<BlockPos, BlockState>> all = new HashMap<>();
        try {
            List<PathData> paths = PathStore.loadPaths();
            for (TransitionData t : PathStore.loadTransitions()) {
                Direction exitDir = headingAt(paths, t.from, false);
                Direction entryDir = headingAt(paths, t.to, true);
                if (exitDir == null) exitDir = entryDir;
                if (entryDir == null) entryDir = exitDir;
                if (exitDir == null) { exitDir = Direction.SOUTH; entryDir = Direction.SOUTH; }

                addPortal(portals, all, t.from, exitDir, true, t.active);
                addPortal(portals, all, t.to, entryDir, false, t.active);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Layout(portals, all);
    }

    private static Direction headingAt(List<PathData> paths, Pos pos, boolean start) {
        for (PathData p : paths) {
            if (p.points() == null || p.points().isEmpty()) continue;
            if ((start ? p.first() : p.last()).equals(pos)) {
                List<BlockPos> blocks = PathStore.blocksOf(p);
                if (!blocks.isEmpty()) return TourMarker.heading(blocks, start);
            }
        }
        return null;
    }

    private static void addPortal(List<Portal> portals, Map<String, Map<BlockPos, BlockState>> all,
                                  Pos at, Direction heading, boolean exit, boolean active) {
        BlockPos node = new BlockPos(at.x(), at.y(), at.z());
        BlockPos c = exit ? node.relative(heading) : node.relative(heading.getOpposite());

        Direction lateral = heading.getClockWise();
        BlockState fill = active
                ? ACTIVE_FILL.setValue(BlockStateProperties.HORIZONTAL_AXIS, lateral.getAxis())
                : INACTIVE_FILL;
        BlockState frame = active ? ACTIVE_FRAME : INACTIVE_FRAME;

        int open0 = TourMarker.Y_OFFSET, open1 = TourMarker.Y_OFFSET + 2;
        int bottom = open0 - 1, top = open1 + 1;

        Map<BlockPos, BlockState> blocks = new HashMap<>();
        for (int lat = -2; lat <= 2; lat++) {
            for (int row = bottom; row <= top; row++) {
                boolean corner = Math.abs(lat) == 2 && (row == bottom || row == top);
                if (corner) continue;
                boolean opening = Math.abs(lat) <= 1 && row >= open0 && row <= open1;
                blocks.put(c.relative(lateral, lat).above(row), opening ? fill : frame);
            }
        }

        portals.add(new Portal(at, exit, active, blocks));
        all.computeIfAbsent(at.dim(), k -> new HashMap<>()).putAll(blocks);
    }
}
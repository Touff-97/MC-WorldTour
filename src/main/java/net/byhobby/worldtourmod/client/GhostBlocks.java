package net.byhobby.worldtourmod.client; // or any package you like

import net.byhobby.worldtourmod.data.PathData;
import net.byhobby.worldtourmod.data.PathStore;
import net.byhobby.worldtourmod.data.Pos;
import net.byhobby.worldtourmod.component.ModDataComponents;
import net.byhobby.worldtourmod.item.custom.ComplexTestItem;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.io.IOException;
import java.util.*;

public final class GhostBlocks {
    private static final int RADIUS = 64;          // only show ghosts this close to the player
    private static final int INTERVAL = 5;         // ticks between refreshes
    private static final int RESEND_EVERY = 8;     // full resend every N refreshes (repairs reverted ghosts)
    private static final int Y_OFFSET = 1;         // 1 = draw on top of the clicked block, 0 = replace it visually

    private static final BlockState NODE = Blocks.GOLD_BLOCK.defaultBlockState();
    private static final BlockState LINK = Blocks.IRON_BLOCK.defaultBlockState();

    private record Sent(String dim, Map<BlockPos, BlockState> blocks) {}

    private static final Map<UUID, Sent> SENT = new HashMap<>();
    private static Map<String, Map<BlockPos, BlockState>> savedCache = null; // dim -> ghosts
    private static int tick = 0;

    private GhostBlocks() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(GhostBlocks::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                SENT.remove(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SENT.clear();
            savedCache = null;
        });
    }

    /** Call after saving or deleting a path so the cache is rebuilt. */
    public static void invalidate() {
        savedCache = null;
    }

    // ---------- Tick ----------

    private static void onTick(MinecraftServer server) {
        if (++tick % INTERVAL != 0) return;
        boolean full = (tick / INTERVAL) % RESEND_EVERY == 0;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                update(player, full);
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static void update(ServerPlayer player, boolean full) {
        Level level = player.level();
        String dim = level.dimension().identifier().toString();

        Sent prev = SENT.get(player.getUUID());
        if (prev != null && !prev.dim().equals(dim)) {
            SENT.remove(player.getUUID()); // changed dimension: old ghosts are gone with the old level
            prev = null;
        }
        Map<BlockPos, BlockState> old = prev == null ? Map.of() : prev.blocks();

        ItemStack tool = heldTool(player);
        Map<BlockPos, BlockState> desired = tool == null ? Map.of() : buildDesired(player, tool, dim);

        // Restore real blocks where ghosts disappeared
        for (BlockPos pos : old.keySet()) {
            if (!desired.containsKey(pos)) {
                send(player, pos, level.getBlockState(pos));
            }
        }
        // Send new / changed ghosts (everything, on a full resend)
        for (var e : desired.entrySet()) {
            if (full || !e.getValue().equals(old.get(e.getKey()))) {
                send(player, e.getKey(), e.getValue());
            }
        }

        if (desired.isEmpty()) SENT.remove(player.getUUID());
        else SENT.put(player.getUUID(), new Sent(dim, desired));
    }

    private static void send(ServerPlayer player, BlockPos pos, BlockState state) {
        player.connection.send(new ClientboundBlockUpdatePacket(pos, state));
    }

    private static ItemStack heldTool(ServerPlayer player) {
        ItemStack main = player.getMainHandItem();
        if (main.getItem() instanceof ComplexTestItem) return main;
        ItemStack off = player.getOffhandItem();
        if (off.getItem() instanceof ComplexTestItem) return off;
        return null;
    }

    // ---------- Building the ghost map ----------

    private static Map<BlockPos, BlockState> buildDesired(ServerPlayer player, ItemStack tool, String dim) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        BlockPos center = player.blockPosition();
        double max = (double) RADIUS * RADIUS;

        for (var e : savedFor(dim).entrySet()) {
            if (e.getKey().distSqr(center) <= max) out.put(e.getKey(), e.getValue());
        }

        // Path currently being drawn on this stack
        List<BlockPos> draft = tool.get(ModDataComponents.PATH_POINTS);
        if (draft != null && !draft.isEmpty()) {
            merge(out, rasterize(draft));
        }
        return out;
    }

    private static Map<BlockPos, BlockState> savedFor(String dim) {
        if (savedCache == null) {
            savedCache = new HashMap<>();
            try {
                for (PathData p : PathStore.loadPaths()) {
                    if (p.points().isEmpty()) continue;
                    String pathDim = p.points().getFirst().dim();
                    List<BlockPos> nodes = new ArrayList<>();
                    for (Pos pt : p.points()) nodes.add(new BlockPos(pt.x(), pt.y(), pt.z()));
                    merge(savedCache.computeIfAbsent(pathDim, d -> new HashMap<>()), rasterize(nodes));
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
        return savedCache.getOrDefault(dim, Map.of());
    }

    private static void merge(Map<BlockPos, BlockState> target, Map<BlockPos, BlockState> source) {
        // gold (node) always wins over iron (link)
        source.forEach((k, v) -> target.merge(k, v, (a, b) -> a.equals(NODE) ? a : b));
    }

    // ---------- Rasterizing ----------

    static Map<BlockPos, BlockState> rasterize(List<BlockPos> nodes) {
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int i = 1; i < nodes.size(); i++) {
            for (BlockPos p : route(nodes.get(i - 1), nodes.get(i))) {
                out.put(p.above(Y_OFFSET), LINK);
            }
        }
        for (BlockPos n : nodes) {
            out.put(n.above(Y_OFFSET), NODE);
        }
        return out;
    }

    /** Blocks strictly between a and b, following rail rules. */
    static List<BlockPos> route(BlockPos a, BlockPos b) {
        List<BlockPos> out = new ArrayList<>();
        int dx = b.getX() - a.getX();
        int dz = b.getZ() - a.getZ();
        int dy = b.getY() - a.getY();

        // Horizontal steps: all X first, then all Z (a single corner)
        List<Direction> dirs = new ArrayList<>();
        for (int i = 0; i < Math.abs(dx); i++) dirs.add(dx > 0 ? Direction.EAST : Direction.WEST);
        for (int i = 0; i < Math.abs(dz); i++) dirs.add(dz > 0 ? Direction.SOUTH : Direction.NORTH);

        BlockPos cur = a;
        for (int i = 0; i < dirs.size(); i++) {
            Direction d = dirs.get(i);
            cur = cur.relative(d);

            // A block that is a corner (next step turns) must stay flat.
            boolean canSlope = i == dirs.size() - 1 || dirs.get(i + 1) == d;
            if (dy != 0 && canSlope) {
                int s = Integer.signum(dy);
                cur = cur.offset(0, s, 0);
                dy -= s;
            }
            out.add(cur);
        }

        // Leftover height: vertical column
        while (dy != 0) {
            int s = Integer.signum(dy);
            cur = cur.offset(0, s, 0);
            dy -= s;
            out.add(cur);
        }

        // The last block is b itself; it gets drawn as a node
        if (!out.isEmpty() && out.getLast().equals(b)) {
            out.removeLast();
        }
        return out;
    }
}
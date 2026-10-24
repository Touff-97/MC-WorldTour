package net.byhobby.worldtourmod.client;

import net.byhobby.worldtourmod.component.ModDataComponents;
import net.byhobby.worldtourmod.data.PathData;
import net.byhobby.worldtourmod.data.PathRouter;
import net.byhobby.worldtourmod.data.PathStore;
import net.byhobby.worldtourmod.data.Pos;
import net.byhobby.worldtourmod.item.custom.ComplexTestItem;
import net.byhobby.worldtourmod.tour.TourRide;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.*;

public final class GhostBlocks {
    private static final int TOUR_RADIUS = 128; // matches minecart tracking range
    private static final int RADIUS = 64;
    private static final int INTERVAL = 5;
    private static final int RESEND_EVERY = 8;
    private static final int Y_OFFSET = 0; // 0 = ghosts replace the clicked block visually

    private static final BlockState NODE = Blocks.GOLD_BLOCK.defaultBlockState();
    private static final BlockState LINK = Blocks.IRON_BLOCK.defaultBlockState();

    private record Sent(String dim, Map<BlockPos, BlockState> blocks) {}
    private record DraftCache(List<BlockPos> nodes, int version, Map<BlockPos, BlockState> ghosts) {}

    private static final Map<UUID, Sent> SENT = new HashMap<>();
    private static final Map<UUID, DraftCache> DRAFTS = new HashMap<>();

    private static Map<String, Map<BlockPos, BlockState>> savedCache = null; // dim -> ghosts
    private static Map<String, Set<BlockPos>> occupiedCache = null;          // dim -> used blocks
    private static int version = 0;
    private static int tick = 0;

    private GhostBlocks() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(GhostBlocks::onTick);
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            SENT.remove(handler.getPlayer().getUUID());
            DRAFTS.remove(handler.getPlayer().getUUID());
        });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SENT.clear();
            DRAFTS.clear();
            invalidate();
        });
    }

    /** Call after saving or deleting a path. */
    public static void invalidate() {
        TourMarker.invalidate();
        PortalMarker.invalidate();
        savedCache = null;
        occupiedCache = null;
        version++;
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
            SENT.remove(player.getUUID());
            prev = null;
        }
        Map<BlockPos, BlockState> old = prev == null ? Map.of() : prev.blocks();

        ItemStack tool = heldTool(player);
        Map<BlockPos, BlockState> desired = new HashMap<>();
        if (tool != null) desired.putAll(buildDesired(player, tool, dim));
        else DRAFTS.remove(player.getUUID());

        // Tour rails: the static start rails when idle, a window around the cart during a tour
        Map<BlockPos, BlockState> tourRails = TourRide.hasSession()
                ? TourRide.railsFor(dim)
                : TourMarker.layout().rails().getOrDefault(dim, Map.of());
        addNear(desired, tourRails, player.blockPosition(), (double) TOUR_RADIUS * TOUR_RADIUS);
        // Portals win over everything else; tool holders also see inactive ones
        Map<BlockPos, BlockState> portals = tool != null
                ? PortalMarker.layout().forHolder(dim)
                : TourRide.portalsFor(dim);
        addNear(desired, portals, player.blockPosition(), (double) TOUR_RADIUS * TOUR_RADIUS);

        for (BlockPos pos : old.keySet()) {
            if (!desired.containsKey(pos)) send(player, pos, level.getBlockState(pos));
        }
        for (var e : desired.entrySet()) {
            if (full || !e.getValue().equals(old.get(e.getKey()))) send(player, e.getKey(), e.getValue());
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
        ensureCache();
        Map<BlockPos, BlockState> out = new HashMap<>();
        BlockPos center = player.blockPosition();
        double max = (double) RADIUS * RADIUS;

        addNear(out, savedCache.getOrDefault(dim, Map.of()), center, max);

        List<BlockPos> draft = tool.get(ModDataComponents.PATH_POINTS);
        if (draft != null && !draft.isEmpty()) {
            addNear(out, draftGhosts(player.getUUID(), draft, dim), center, max);
        } else {
            DRAFTS.remove(player.getUUID());
        }
        return out;
    }

    private static void addNear(Map<BlockPos, BlockState> target, Map<BlockPos, BlockState> source,
                                BlockPos center, double maxDistSqr) {
        for (var e : source.entrySet()) {
            if (e.getKey().distSqr(center) <= maxDistSqr) target.put(e.getKey(), e.getValue());
        }
    }

    /** Routes the in-progress path; cached until the draft or the saved paths change. */
    private static Map<BlockPos, BlockState> draftGhosts(UUID id, List<BlockPos> nodes, String dim) {
        DraftCache c = DRAFTS.get(id);
        if (c != null && c.version() == version && c.nodes().equals(nodes)) return c.ghosts();

        PathRouter.Result r = PathRouter.routeAll(nodes, occupiedCache.getOrDefault(dim, Set.of()));
        Map<BlockPos, BlockState> g = new HashMap<>();
        for (BlockPos b : r.blocks()) g.put(b.above(Y_OFFSET), LINK);
        for (BlockPos n : nodes) g.put(n.above(Y_OFFSET), NODE);

        DRAFTS.put(id, new DraftCache(List.copyOf(nodes), version, g));
        return g;
    }

    private static void ensureCache() {
        if (savedCache != null) return;
        savedCache = new HashMap<>();
        occupiedCache = new HashMap<>();
        try {
            for (PathData p : PathStore.loadPaths()) {
                if (p.points() == null || p.points().isEmpty()) continue;
                String d = p.first().dim();
                Map<BlockPos, BlockState> ghosts = savedCache.computeIfAbsent(d, k -> new HashMap<>());
                Set<BlockPos> occ = occupiedCache.computeIfAbsent(d, k -> new HashSet<>());

                for (BlockPos b : PathStore.blocksOf(p)) {
                    ghosts.put(b.above(Y_OFFSET), LINK);
                    occ.add(b);
                }
                for (Pos pt : p.points()) {
                    ghosts.put(new BlockPos(pt.x(), pt.y(), pt.z()).above(Y_OFFSET), NODE);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
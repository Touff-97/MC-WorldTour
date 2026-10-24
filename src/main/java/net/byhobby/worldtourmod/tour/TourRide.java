package net.byhobby.worldtourmod.tour;

import net.byhobby.worldtourmod.client.PortalMarker;
import net.byhobby.worldtourmod.client.TourMarker;
import net.byhobby.worldtourmod.data.Pos;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TourRide {
    private static final double YAW_OFFSET = 0.0;
    private static final int RAILS_AHEAD = 8;
    private static final int EXIT_BLOCKS = 12;
    private static final int PROMPT_INTERVAL = 20;

    // Dimension hopping
    private static final int PRELOAD_BLOCKS = 40;    // start loading the destination this far before the portal
    private static final int PRELOAD_RADIUS = 2;     // chunks around the destination (2 = 5x5)
    private static final int MAX_WAIT_TICKS = 100;   // give up waiting for the destination after this
    private static final int ARRIVAL_HOLD_TICKS = 20; // cart waits after arriving so the client can load

    private static final double PORTAL_SHOW_AHEAD = 20.0;  // portal appears this many blocks before the cart reaches it
    private static final double PORTAL_SHOW_BEHIND = 8.0;  // and stays this many blocks after the cart emerges

    private enum Phase { RIDING, ARRIVED, LEAVING }

    private record Forced(ServerLevel level, int cx, int cz) {}

    private static final class Session {
        Entity cart;
        final List<TourRoute.Segment> segments;
        Phase phase = Phase.RIDING;
        int seg = 0, idx = 0;
        double t = 0;
        double travelled = 0;
        long ticksRiding = 0;
        int promptTicks = 0;
        int hold = 0;              // arrival hold, in ticks
        int waited = 0;            // ticks spent waiting at a portal for the destination
        int preloadedFor = -1;     // segment index whose destination is already being loaded
        final List<Forced> forced = new ArrayList<>();
        float yaw;
        Vec3 last;

        Session(Entity cart, TourRoute route) {
            this.cart = cart;
            this.segments = new ArrayList<>(route.segments());
            this.last = cart.position();
            this.yaw = cart.getYRot();
        }
    }

    private static Session session = null;
    private static MinecraftServer server = null;

    private TourRide() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TourRide::tick);
    }

    public static boolean hasSession() { return session != null; }

    public static boolean isActiveCart(Entity e) {
        return session != null && session.cart.getUUID().equals(e.getUUID());
    }

    // ---------- Boarding ----------

    public static void tryBoard(ServerPlayer player, Entity cart) {
        if (player.isPassenger() || cart.isVehicle()) return;
        if (session != null && (!isActiveCart(cart) || session.phase != Phase.RIDING)) return;

        if (session == null) {
            TourRoute route;
            try {
                route = TourRoute.build();
            } catch (Exception e) {
                e.printStackTrace();
                say(player, "Could not read the tour data.", true);
                return;
            }
            if (route.segments().isEmpty()) {
                say(player, route.problem(), true);
                return;
            }
            session = new Session(cart, route);
            if (!route.reachesFinish()) {
                say(player, "Warning, the tour can't reach the finish: " + route.problem(), false);
            }
        }
        player.startRiding(cart);
    }

    // ---------- Tick ----------

    private static void tick(MinecraftServer srv) {
        server = srv;
        Session s = session;
        if (s == null) return;
        try {
            if (s.cart.isRemoved()) { releaseForced(s); session = null; return; }

            switch (s.phase) {
                case RIDING -> {
                    if (s.cart.isVehicle()) {                 // nobody inside = paused
                        if (s.hold > 0) {
                            if (--s.hold == 0) releaseForced(s);
                        } else {
                            advance(s);
                        }
                    }
                }
                case ARRIVED -> {
                    if (!s.cart.isVehicle()) beginLeaving(s);
                    else if (s.promptTicks++ % PROMPT_INTERVAL == 0) promptRiders(s);
                }
                case LEAVING -> advance(s);
            }
            if (session == s) place(s);
        } catch (Exception e) {
            e.printStackTrace();
            releaseForced(s);
            session = null;
        }
    }

    private static void advance(Session s) {
        s.ticksRiding++;
        preloadAhead(s);
        List<TourRoute.Segment> segments = s.segments;

        TourRoute.Segment first = segments.get(s.seg);
        double remaining = TourSpeed.blocksPerTick(new TourSpeed.Progress(
                first.pathId(), s.seg, s.idx, first.blocks().size(), s.travelled, s.ticksRiding))
                * portalEase(s);

        int guard = 0;
        while (remaining > 0 && guard++ < 10_000) {
            List<BlockPos> b = segments.get(s.seg).blocks();

            if (s.idx >= b.size() - 1) {                       // end of this path
                if (s.seg + 1 >= segments.size()) { onRouteEnd(s); return; }

                TourRoute.Segment next = segments.get(s.seg + 1);
                if (!next.dim().equals(segments.get(s.seg).dim())) {
                    crossDimension(s, next);                   // may wait, hop or abort
                    return;
                }
                s.seg++; s.idx = 0; s.t = 0;                   // same-dimension transition
                continue;
            }

            double len = Vec3.atCenterOf(b.get(s.idx)).distanceTo(Vec3.atCenterOf(b.get(s.idx + 1)));
            double left = len * (1 - s.t);
            if (remaining >= left) {
                remaining -= left; s.travelled += left;
                s.idx++; s.t = 0;
            } else {
                s.t += remaining / len; s.travelled += remaining;
                remaining = 0;
            }
        }
    }

    // ---------- Portal easing and visibility ----------

    /** The cart leaves this segment through a portal (the appended exit stretch has none). */
    private static boolean portalAfter(Session s, int seg) {
        return seg + 1 < s.segments.size() && !"exit".equals(s.segments.get(seg + 1).pathId());
    }

    /** The cart entered this segment through a portal. */
    private static boolean portalBefore(Session s, int seg) {
        return seg > 0 && !"exit".equals(s.segments.get(seg).pathId());
    }

    private static double distToEnd(Session s) {
        int n = s.segments.get(s.seg).blocks().size();
        return Math.max(0.0, (n - 1 - s.idx) - s.t);
    }

    private static double distFromStart(Session s) {
        return s.idx + s.t;
    }

    /** 0..1 multiplier: slows into a portal, speeds up out of one. */
    private static double portalEase(Session s) {
        double d = Double.MAX_VALUE;
        if (portalAfter(s, s.seg)) d = Math.min(d, distToEnd(s));
        if (portalBefore(s, s.seg)) d = Math.min(d, distFromStart(s));
        return TourSpeed.portalFactor(d);
    }

    /** Portals everyone should see right now: the one ahead and the one just left. */
    public static Map<BlockPos, BlockState> portalsFor(String dim) {
        Session s = session;
        if (s == null) return Map.of();
        TourRoute.Segment cur = s.segments.get(s.seg);
        if (!cur.dim().equals(dim)) return Map.of();

        Map<BlockPos, BlockState> out = new HashMap<>();
        List<BlockPos> b = cur.blocks();

        if (portalAfter(s, s.seg) && distToEnd(s) <= PORTAL_SHOW_AHEAD) {
            BlockPos end = b.get(b.size() - 1);
            PortalMarker.Portal p = PortalMarker.layout().find(new Pos(dim, end.getX(), end.getY(), end.getZ()), true);
            if (p != null) out.putAll(p.blocks());
        }
        if (portalBefore(s, s.seg) && distFromStart(s) <= PORTAL_SHOW_BEHIND) {
            BlockPos start = b.get(0);
            PortalMarker.Portal p = PortalMarker.layout().find(new Pos(dim, start.getX(), start.getY(), start.getZ()), false);
            if (p != null) out.putAll(p.blocks());
        }
        return out;
    }

    // ---------- Dimension hopping ----------

    private static ServerLevel levelOf(String dim) {
        return server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(dim)));
    }

    /** Starts loading the destination when a dimension change is close. */
    private static void preloadAhead(Session s) {
        if (s.preloadedFor == s.seg || s.seg + 1 >= s.segments.size()) return;
        TourRoute.Segment cur = s.segments.get(s.seg), next = s.segments.get(s.seg + 1);
        if (next.dim().equals(cur.dim())) return;
        if (cur.blocks().size() - 1 - s.idx > PRELOAD_BLOCKS) return;

        ServerLevel target = levelOf(next.dim());
        if (target == null) return;
        BlockPos dest = next.blocks().get(0);
        int cx = dest.getX() >> 4, cz = dest.getZ() >> 4;
        for (int dx = -PRELOAD_RADIUS; dx <= PRELOAD_RADIUS; dx++) {
            for (int dz = -PRELOAD_RADIUS; dz <= PRELOAD_RADIUS; dz++) {
                target.setChunkForced(cx + dx, cz + dz, true);
                s.forced.add(new Forced(target, cx + dx, cz + dz));
            }
        }
        s.preloadedFor = s.seg;
    }

    private static void releaseForced(Session s) {
        for (Forced f : s.forced) f.level().setChunkForced(f.cx(), f.cz(), false);
        s.forced.clear();
    }

    private static void crossDimension(Session s, TourRoute.Segment next) {
        ServerLevel target = levelOf(next.dim());
        if (target == null) { abort(s, "dimension " + next.dim() + " is not loaded"); return; }

        preloadAhead(s);
        BlockPos start = next.blocks().get(0);

        // Wait at the portal until the destination is ready (or we give up waiting)
        if (!target.hasChunkAt(start) && s.waited++ < MAX_WAIT_TICKS) return;
        s.waited = 0;
        target.getChunk(start.getX() >> 4, start.getZ() >> 4); // makes sure it really is loaded

        BlockPos second = next.blocks().size() > 1 ? next.blocks().get(1) : start;
        int dx = second.getX() - start.getX(), dz = second.getZ() - start.getZ();
        float yaw = (dx != 0 || dz != 0) ? (float) (Math.toDegrees(Math.atan2(dz, dx)) + YAW_OFFSET) : s.yaw;
        Vec3 pos = new Vec3(start.getX() + 0.5, start.getY() + TourMarker.Y_OFFSET + 0.0625, start.getZ() + 0.5);

        List<ServerPlayer> riders = new ArrayList<>();
        for (Entity e : s.cart.getPassengers()) if (e instanceof ServerPlayer sp) riders.add(sp);

        Entity newCart = TourMarker.spawnCart(target, pos, yaw);
        if (newCart == null) { abort(s, "could not create the cart in the new dimension"); return; }

        Entity old = s.cart;
        for (ServerPlayer sp : riders) {
            sp.stopRiding();
            sp.teleportTo(target, pos.x, pos.y, pos.z, Set.of(), yaw, 0.0f, true);
            sp.startRiding(newCart);
        }
        old.discard();

        s.cart = newCart;
        s.seg++; s.idx = 0; s.t = 0;
        s.last = pos;
        s.yaw = yaw;
        s.hold = ARRIVAL_HOLD_TICKS;
    }

    private static void abort(Session s, String why) {
        for (Entity passenger : new ArrayList<>(s.cart.getPassengers())) {
            if (passenger instanceof ServerPlayer sp) say(sp, "Tour stopped: " + why, false);
            passenger.stopRiding();
        }
        releaseForced(s);
        s.cart.discard();
        session = null;
    }

    // ---------- End of route ----------

    private static void onRouteEnd(Session s) {
        if (s.phase == Phase.LEAVING) {
            s.cart.discard();
            releaseForced(s);
            session = null;
            return;
        }
        s.phase = Phase.ARRIVED;
        s.promptTicks = 0;
        for (Entity passenger : s.cart.getPassengers()) {
            if (passenger instanceof ServerPlayer sp) say(sp, "Tour finished!", false);
        }
    }

    private static void promptRiders(Session s) {
        for (Entity passenger : s.cart.getPassengers()) {
            if (passenger instanceof ServerPlayer sp) say(sp, "Sneak to get off", true);
        }
    }

    private static void beginLeaving(Session s) {
        TourRoute.Segment lastSeg = s.segments.get(s.segments.size() - 1);
        List<BlockPos> blocks = lastSeg.blocks();
        BlockPos end = blocks.get(blocks.size() - 1);
        Direction dir = headingAtEnd(blocks);

        List<BlockPos> exit = new ArrayList<>();
        exit.add(end);
        for (int i = 1; i <= EXIT_BLOCKS; i++) exit.add(end.relative(dir, i));

        s.segments.add(new TourRoute.Segment("exit", lastSeg.dim(), exit));
        s.seg = s.segments.size() - 1;
        s.idx = 0;
        s.t = 0;
        s.phase = Phase.LEAVING;
    }

    private static Direction headingAtEnd(List<BlockPos> blocks) {
        for (int i = blocks.size() - 1; i >= 1; i--) {
            BlockPos a = blocks.get(i - 1), b = blocks.get(i);
            int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ();
            if (dz == 0 && Math.abs(dx) == 1) return dx > 0 ? Direction.EAST : Direction.WEST;
            if (dx == 0 && Math.abs(dz) == 1) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
        return Direction.SOUTH;
    }

    private static void place(Session s) {
        List<BlockPos> b = s.segments.get(s.seg).blocks();
        BlockPos a = b.get(s.idx);
        BlockPos n = s.idx + 1 < b.size() ? b.get(s.idx + 1) : a;

        double x = lerp(a.getX(), n.getX(), s.t) + 0.5;
        double y = lerp(a.getY(), n.getY(), s.t) + TourMarker.Y_OFFSET + 0.0625;
        double z = lerp(a.getZ(), n.getZ(), s.t) + 0.5;
        Vec3 target = new Vec3(x, y, z);

        int dx = n.getX() - a.getX(), dz = n.getZ() - a.getZ();
        if (dx != 0 || dz != 0) {
            s.yaw = (float) (Math.toDegrees(Math.atan2(dz, dx)) + YAW_OFFSET);
        }

        s.cart.setPos(target);
        s.cart.setYRot(s.yaw);
        s.cart.setDeltaMovement(target.subtract(s.last));
        s.last = target;
    }

    // ---------- Ghost rails that travel with the cart ----------

    public static Map<BlockPos, BlockState> railsFor(String dim) {
        Session s = session;
        if (s == null) return Map.of();
        TourRoute.Segment cur = s.segments.get(s.seg);
        if (!cur.dim().equals(dim)) return Map.of();

        BlockPos before = s.idx > 0 ? cur.blocks().get(s.idx - 1) : null;

        // Walk forward across segments, but never into another dimension
        List<BlockPos> line = new ArrayList<>();
        int si = s.seg, bi = s.idx;
        while (line.size() < RAILS_AHEAD + 2 && si < s.segments.size()) {
            if (!s.segments.get(si).dim().equals(dim)) break;
            List<BlockPos> b = s.segments.get(si).blocks();
            if (bi < b.size()) line.add(b.get(bi++));
            else { si++; bi = 0; }
        }

        Map<BlockPos, BlockState> out = new HashMap<>();
        int emit = Math.min(line.size(), RAILS_AHEAD + 1);
        for (int i = 0; i < emit; i++) {
            BlockPos prev = i > 0 ? line.get(i - 1) : before;
            BlockPos next = i + 1 < line.size() ? line.get(i + 1) : null;
            out.put(line.get(i).above(TourMarker.Y_OFFSET - 1), Blocks.STONE_BRICKS.defaultBlockState());
            out.put(line.get(i).above(TourMarker.Y_OFFSET), RailShapes.rail(prev, line.get(i), next));
        }
        return out;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static void say(ServerPlayer player, String text, boolean actionBar) {
        player.sendSystemMessage(Component.literal(text), actionBar);
    }
}
package net.byhobby.worldtourmod.tour;

import net.byhobby.worldtourmod.client.TourMarker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class TourRide {
    private static final double YAW_OFFSET = 0.0;   // set to 90 if the cart looks sideways
    private static final int RAILS_AHEAD = 8;       // ghost rails shown in front of the cart
    private static final int EXIT_BLOCKS = 12;      // how far the empty cart rolls on after the finish
    private static final int PROMPT_INTERVAL = 20;  // ticks between "get off" prompts

    private enum Phase { RIDING, ARRIVED, LEAVING }

    private static final class Session {
        final Entity cart;
        final List<TourRoute.Segment> segments;   // own copy: the exit stretch gets appended
        Phase phase = Phase.RIDING;
        int seg = 0, idx = 0;
        double t = 0;              // 0..1 between block idx and idx+1
        double travelled = 0;
        long ticksRiding = 0;
        int promptTicks = 0;
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

    private static void tick(MinecraftServer server) {
        Session s = session;
        if (s == null) return;
        try {
            if (s.cart.isRemoved()) { session = null; return; }

            switch (s.phase) {
                case RIDING -> { if (s.cart.isVehicle()) advance(s); }   // nobody inside = paused
                case ARRIVED -> {
                    if (!s.cart.isVehicle()) beginLeaving(s);            // rider got off
                    else if (s.promptTicks++ % PROMPT_INTERVAL == 0) promptRiders(s);
                }
                case LEAVING -> advance(s);
            }
            if (session == s) place(s);
        } catch (Exception e) {
            e.printStackTrace();
            session = null;
        }
    }

    private static void advance(Session s) {
        s.ticksRiding++;
        List<TourRoute.Segment> segments = s.segments;

        TourRoute.Segment first = segments.get(s.seg);
        double remaining = TourSpeed.blocksPerTick(new TourSpeed.Progress(
                first.pathId(), s.seg, s.idx, first.blocks().size(), s.travelled, s.ticksRiding));

        int guard = 0;
        while (remaining > 0 && guard++ < 10_000) {
            List<BlockPos> b = segments.get(s.seg).blocks();

            if (s.idx >= b.size() - 1) {                       // end of this path
                if (s.seg + 1 >= segments.size()) { onRouteEnd(s); return; }
                s.seg++; s.idx = 0; s.t = 0;                   // transition: jump to the next path
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

    private static void onRouteEnd(Session s) {
        if (s.phase == Phase.LEAVING) {      // finished rolling away: the cart is gone
            s.cart.discard();
            session = null;                  // TourMarker puts a fresh cart at the start
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

    /** Appends a straight stretch past the final block and sets the cart rolling along it. */
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

    /** Rails from the cart's current block to RAILS_AHEAD blocks ahead (nothing behind it). */
    public static Map<BlockPos, BlockState> railsFor(String dim) {
        Session s = session;
        if (s == null) return Map.of();
        TourRoute.Segment cur = s.segments.get(s.seg);
        if (!cur.dim().equals(dim)) return Map.of();

        BlockPos before = s.idx > 0 ? cur.blocks().get(s.idx - 1) : null; // shape context only

        // Walk forward across segments (+1 block of context for the last rail's shape)
        List<BlockPos> line = new ArrayList<>();
        int si = s.seg, bi = s.idx;
        while (line.size() < RAILS_AHEAD + 2 && si < s.segments.size()) {
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
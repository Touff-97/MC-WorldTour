package net.byhobby.worldtourmod.tour;

import net.byhobby.worldtourmod.client.TourMarker;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;

import java.util.List;

public final class TourRide {
    /** Rotate the cart if it sits sideways on its heading. */
    private static final double YAW_OFFSET = 0.0;

    private static final class Session {
        final Entity cart;
        final TourRoute route;
        int seg = 0, idx = 0;
        double t = 0;              // 0..1 between block idx and idx+1
        double travelled = 0;
        long ticksRiding = 0;
        float yaw;
        Vec3 last;

        Session(Entity cart, TourRoute route) {
            this.cart = cart;
            this.route = route;
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

    // ---------- Boarding (called from TourMarker's use-entity hook) ----------

    public static void tryBoard(ServerPlayer player, Entity cart) {
        System.out.println("[tour] tryBoard: passenger=" + player.isPassenger() + " vehicle=" + cart.isVehicle()
                + " session=" + (session != null) + " activeCart=" + isActiveCart(cart));
        if (player.isPassenger() || cart.isVehicle()) return;
        if (session != null && !isActiveCart(cart)) {
            System.out.println("[tour] blocked: a session exists for a different cart");
            return;
        }

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
        boolean ok = player.startRiding(cart);
        System.out.println("[tour] startRiding -> " + ok);
    }

    // ---------- Tick ----------

    private static void tick(MinecraftServer server) {
        Session s = session;
        if (s == null) return;
        try {
            if (s.cart.isRemoved()) { session = null; return; }
            if (s.cart.isVehicle()) advance(s);   // nobody inside = paused
            place(s);                              // always hold the cart where it should be
        } catch (Exception e) {
            e.printStackTrace();
            session = null;
        }
    }

    private static void advance(Session s) {
        s.ticksRiding++;
        List<TourRoute.Segment> segments = s.route.segments();

        TourRoute.Segment first = segments.get(s.seg);
        double remaining = TourSpeed.blocksPerTick(new TourSpeed.Progress(
                first.pathId(), s.seg, s.idx, first.blocks().size(), s.travelled, s.ticksRiding));

        int guard = 0;
        while (remaining > 0 && guard++ < 10_000) {
            List<BlockPos> b = segments.get(s.seg).blocks();

            if (s.idx >= b.size() - 1) {                       // end of this path
                if (s.seg + 1 >= segments.size()) { finish(s); return; }
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

    private static void finish(Session s) {
        for (Entity passenger : s.cart.getPassengers()) {
            if (passenger instanceof ServerPlayer sp) say(sp, "Tour finished!", false);
        }
        s.cart.ejectPassengers();
        session = null; // TourMarker puts a fresh cart back at the start
    }

    private static void place(Session s) {
        List<BlockPos> b = s.route.segments().get(s.seg).blocks();
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
        s.cart.setDeltaMovement(target.subtract(s.last)); // lets clients extrapolate
        s.last = target;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static void say(ServerPlayer player, String text, boolean actionBar) {
        player.sendSystemMessage(Component.literal(text), actionBar);
    }
}
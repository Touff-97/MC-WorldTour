package net.byhobby.worldtourmod.client;

import net.byhobby.worldtourmod.data.PathData;
import net.byhobby.worldtourmod.data.PathStore;
import net.byhobby.worldtourmod.data.Pos;
import net.byhobby.worldtourmod.data.TourBounds;
import net.byhobby.worldtourmod.data.TourStore;
import net.byhobby.worldtourmod.tour.TourRide;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntitySpawnReason;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.RailShape;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.*;

public final class TourMarker {
    /** 1 = rails sit on top of the clicked block (no rubber-banding). 0 = replace it visually. */
    public static final int Y_OFFSET = 1;

    private static final String TAG = "worldtour_start_cart";
    private static final int INTERVAL = 5;

    public record Layout(Map<String, Map<BlockPos, BlockState>> rails, Pos cartPos, float cartYaw) {}

    private static Layout layout = null;
    private static int tick = 0;

    private static final Identifier MINECART_ID = Identifier.parse("minecraft:minecart");

    private static EntityType<?> minecartType() {
        return BuiltInRegistries.ENTITY_TYPE.getValue(MINECART_ID);
    }

    private TourMarker() {}

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(TourMarker::onTick);

        UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> {
            if (!isMarker(entity)) return InteractionResult.PASS;
            if (hand == InteractionHand.MAIN_HAND && !level.isClientSide() && player instanceof ServerPlayer sp) {
                TourRide.tryBoard(sp, entity);
            }
            return InteractionResult.SUCCESS;
        });

        AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) ->
                isMarker(entity) ? InteractionResult.FAIL : InteractionResult.PASS);
    }

    public static void invalidate() { layout = null; }

    public static Layout layout() {
        if (layout == null) layout = build();
        return layout;
    }

    // ---------- Cart upkeep ----------

    private static boolean isMarker(Entity e) {
        return MINECART_ID.equals(BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()))
                && e.entityTags().contains(TAG);
    }

    private static Vec3 cartVec(Pos p) {
        return new Vec3(p.x() + 0.5, p.y() + Y_OFFSET + 0.0625, p.z() + 0.5);
    }

    private static void onTick(MinecraftServer server) {
        if (++tick % INTERVAL != 0) return;
        try {
            Layout l = layout();

            // 1. Remove stray marker carts near players (old start, duplicates, wrong dimension)
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                Level level = player.level();
                String dim = level.dimension().identifier().toString();
                AABB box = player.getBoundingBox().inflate(160);
                for (Entity e : level.getEntities((Entity) null, box, TourMarker::isMarker)) {
                    if (TourRide.isActiveCart(e)) continue;
                    boolean keep = l.cartPos() != null && l.cartPos().dim().equals(dim)
                            && e.position().distanceToSqr(cartVec(l.cartPos())) < 9.0;
                    if (!keep) e.discard();
                }
            }

            if (TourRide.hasSession()) return;

            // 2. Make sure exactly one cart sits at the start
            Pos cp = l.cartPos();
            if (cp == null) return;
            ServerLevel level = server.getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(cp.dim())));
            if (level == null) return;
            BlockPos bp = new BlockPos(cp.x(), cp.y() + Y_OFFSET, cp.z());
            if (!level.hasChunkAt(bp)) return;

            Vec3 target = cartVec(cp);
            List<Entity> found = new ArrayList<>(level.getEntities((Entity) null, new AABB(bp).inflate(3), TourMarker::isMarker));
            System.out.println("[tour] tick: found markers=" + found.size() + " session=" + TourRide.hasSession());
            if (found.isEmpty()) {
                Entity cart = minecartType().create(level, EntitySpawnReason.COMMAND);
                if (cart == null) return;
                cart.setPos(target);
                cart.setYRot(l.cartYaw());
                cart.setNoGravity(true);
                cart.addTag(TAG);
                level.addFreshEntity(cart);
            } else {
                for (int i = 0; i < found.size(); i++) {
                    Entity e = found.get(i);
                    if (i > 0) { e.discard(); continue; }
                    if (e.position().distanceToSqr(target) > 0.0001) {   // pushed? put it back
                        e.setPos(target);
                        e.setDeltaMovement(Vec3.ZERO);
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ---------- Layout (rails + cart position) ----------

    private static Layout build() {
        Map<String, Map<BlockPos, BlockState>> rails = new HashMap<>();
        Pos cartPos = null;
        float yaw = 0;

        TourBounds tb = TourStore.get();
        try {
            List<PathData> paths = PathStore.loadPaths();

            List<BlockPos> startBlocks = tb.start() == null ? null : blocksFor(paths, tb.start(), true);
            if (startBlocks != null) {
                Direction dir = heading(startBlocks, true);
                rails.computeIfAbsent(tb.start().dim(), k -> new HashMap<>()).putAll(railLine(startBlocks, true, dir));
                cartPos = tb.start();
                yaw = (float) Math.toDegrees(Math.atan2(dir.getStepZ(), dir.getStepX())); // vanilla convention
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return new Layout(rails, cartPos, yaw);
    }

    /** Route blocks of the path whose first (start) or last (finish) node is `pos`. */
    private static List<BlockPos> blocksFor(List<PathData> paths, Pos pos, boolean start) {
        for (PathData p : paths) {
            if (p.points() == null || p.points().isEmpty()) continue;
            Pos node = start ? p.first() : p.last();
            if (node.equals(pos)) {
                List<BlockPos> blocks = PathStore.blocksOf(p);
                if (!blocks.isEmpty()) return blocks;
            }
        }
        return null;
    }

    /** Travel direction at the start (first horizontal step) or finish (last horizontal step). */
    private static Direction heading(List<BlockPos> blocks, boolean atStart) {
        int n = blocks.size();
        if (atStart) {
            for (int i = 1; i < n; i++) {
                Direction d = step(blocks.get(i - 1), blocks.get(i));
                if (d != null) return d;
            }
        } else {
            for (int i = n - 1; i >= 1; i--) {
                Direction d = step(blocks.get(i - 1), blocks.get(i));
                if (d != null) return d;
            }
        }
        return Direction.SOUTH;
    }

    private static Direction step(BlockPos a, BlockPos b) {
        int dx = b.getX() - a.getX(), dz = b.getZ() - a.getZ(), dy = b.getY() - a.getY();
        if (Math.abs(dy) > 1) return null;
        if (dz == 0 && Math.abs(dx) == 1) return dx > 0 ? Direction.EAST : Direction.WEST;
        if (dx == 0 && Math.abs(dz) == 1) return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        return null;
    }

    /** Three rails: one block back, the node, one block forward (in travel direction). */
    private static Map<BlockPos, BlockState> railLine(List<BlockPos> blocks, boolean atStart, Direction dir) {
        int n = blocks.size();
        BlockPos node, back, forward;
        if (atStart) {
            node = blocks.get(0);
            back = node.relative(dir.getOpposite());
            BlockPos next = n > 1 ? blocks.get(1) : null;
            forward = (next != null && dir.equals(step(node, next))) ? next : node.relative(dir);
        } else {
            node = blocks.get(n - 1);
            forward = node.relative(dir);
            BlockPos prev = n > 1 ? blocks.get(n - 2) : null;
            back = (prev != null && dir.equals(step(prev, node))) ? prev : node.relative(dir.getOpposite());
        }

        List<BlockPos> line = List.of(back, node, forward);
        Map<BlockPos, BlockState> out = new HashMap<>();
        for (int i = 0; i < 3; i++) {
            BlockPos p = line.get(i);
            BlockPos prev = i > 0 ? line.get(i - 1) : null;
            BlockPos next = i < 2 ? line.get(i + 1) : null;

            RailShape shape;
            if (next != null && next.getY() > p.getY()) shape = ascending(dir);
            else if (prev != null && prev.getY() > p.getY()) shape = ascending(dir.getOpposite());
            else shape = dir.getAxis() == Direction.Axis.X ? RailShape.EAST_WEST : RailShape.NORTH_SOUTH;

            out.put(p.above(Y_OFFSET - 1), Blocks.STONE_BRICKS.defaultBlockState());
            out.put(p.above(Y_OFFSET), Blocks.RAIL.defaultBlockState().setValue(BlockStateProperties.RAIL_SHAPE, shape));
        }
        return out;
    }

    private static RailShape ascending(Direction d) {
        return switch (d) {
            case NORTH -> RailShape.ASCENDING_NORTH;
            case SOUTH -> RailShape.ASCENDING_SOUTH;
            case EAST -> RailShape.ASCENDING_EAST;
            default -> RailShape.ASCENDING_WEST;
        };
    }
}
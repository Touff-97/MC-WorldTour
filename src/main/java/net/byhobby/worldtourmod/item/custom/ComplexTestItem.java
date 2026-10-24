package net.byhobby.worldtourmod.item.custom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.byhobby.worldtourmod.client.GhostBlocks;
import net.byhobby.worldtourmod.client.TourMarker;
import net.byhobby.worldtourmod.component.DraftPoint;
import net.byhobby.worldtourmod.component.ModDataComponents;
import net.byhobby.worldtourmod.data.*;
import net.byhobby.worldtourmod.tour.TourRide;
import net.byhobby.worldtourmod.tour.TourRoute;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

import net.minecraft.core.Direction;

import java.io.IOException;
import java.util.*;
import java.util.ArrayList;
import java.util.List;


public class ComplexTestItem extends Item {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public ComplexTestItem(Properties properties) {
        super(properties);
    }

    // Tool Mode
    public static ToolMode getMode(ItemStack stack) {
        return ToolMode.byOrdinal(stack.getOrDefault(ModDataComponents.TOOL_MODE, 0));
    }

    private static void cycleMode(Level level, Player player, ItemStack stack) {
        if (level.isClientSide()) return;
        ToolMode next = getMode(stack).next();
        stack.set(ModDataComponents.TOOL_MODE, next.ordinal());
        say(player, "Mode: " + next.id, true);
    }

    // ---------- Right click ----------

    // Air
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (player.isShiftKeyDown()) {
            cycleMode(level, player, stack);
            return InteractionResult.SUCCESS;
        }
        if (getMode(stack) == ToolMode.TELEPORT_PATHS) {
            if (!level.isClientSide()) cyclePath(player, +1);
            return InteractionResult.SUCCESS;
        }
        return super.use(level, player, hand);
    }

    // Block
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        ItemStack stack = context.getItemInHand();
        if (player == null) return InteractionResult.PASS;

        if (player.isShiftKeyDown()) {
            cycleMode(level, player, stack);
            return InteractionResult.SUCCESS;
        }

        if (!level.isClientSide()) {
            BlockPos pos = context.getClickedPos();
            switch (getMode(stack)) {
                case CREATE_PATH -> addPathPoint(level, player, stack, pos);
                case CREATE_TRANSITION -> addTransitionPoint(level, player, stack, pos);
                case CREATE_START -> setTourBound(level, player, pos, true);
                case CREATE_FINISH -> setTourBound(level, player, pos, false);
                case ERASE -> erasePath(level, player, pos);
                case TELEPORT_PATHS -> cyclePath(player, +1);
            }
        }
        return InteractionResult.SUCCESS;
    }

    // ---------- create_path ----------

    private static void addPathPoint(Level level, Player player, ItemStack stack, BlockPos pos) {
        List<BlockPos> path = new ArrayList<>(stack.getOrDefault(ModDataComponents.PATH_POINTS, List.of()));

        try {
            String error = checkNext(path, pos, PathStore.occupied(dimensionOf(level)));
            if (error != null) {
                say(player, error, true);
                return;
            }
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Could not read saved paths", true);
            return;
        }

        path.add(pos.immutable());
        stack.set(ModDataComponents.PATH_POINTS, List.copyOf(path));
        say(player, path.size() == 1
                ? "Path started at " + pos.toShortString()
                : "Point " + path.size() + " added: " + pos.toShortString(), true);
    }

    /** null = `next` can be appended to `nodes`; otherwise the reason it can't. */
    private static String checkNext(List<BlockPos> nodes, BlockPos next, Set<BlockPos> saved) {
        Set<BlockPos> occ = new HashSet<>(saved);
        occ.addAll(PathRouter.routeAll(nodes, saved).blocks()); // saved paths + the draft so far
        BlockPos last = nodes.isEmpty() ? null : nodes.get(nodes.size() - 1);

        if (PathRouter.isBlocked(next, occ, last)) {
            return "Blocked: already part of a path, or directly above/below one.";
        }
        if (last != null && PathRouter.findSegment(last, next, occ) == null) {
            return "No valid route to that block.";
        }
        return null;
    }

    private static void finishPath(Level level, Player player, ItemStack stack, BlockPos lastPos) {
        List<BlockPos> existing = stack.get(ModDataComponents.PATH_POINTS);
        if (existing == null || existing.isEmpty()) {
            say(player, "No active path. Right click a block first.", true);
            return;
        }

        String dim = dimensionOf(level);
        try {
            Set<BlockPos> saved = PathStore.occupied(dim);
            String error = checkNext(existing, lastPos, saved);
            if (error != null) {
                say(player, error, true);
                return;
            }

            List<BlockPos> nodes = new ArrayList<>(existing);
            nodes.add(lastPos.immutable());
            PathRouter.Result route = PathRouter.routeAll(nodes, saved);
            if (!route.complete()) {
                say(player, "Route no longer valid. Sneak + left click to cancel the path.", true);
                return;
            }

            List<Pos> points = nodes.stream().map(p -> new Pos(dim, p.getX(), p.getY(), p.getZ())).toList();
            List<int[]> blocks = route.blocks().stream().map(p -> new int[]{p.getX(), p.getY(), p.getZ()}).toList();
            PathData data = new PathData(newId("path"), points, blocks);

            PathStore.save(data);
            GhostBlocks.invalidate();
            stack.remove(ModDataComponents.PATH_POINTS);
            say(player, "Path saved (" + points.size() + " nodes, " + blocks.size() + " blocks): " + data.id(), false);
            reportRelink(player);
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Failed to save path!", false); // draft stays on the stack
        }
    }

    // ---------- create_transition ----------

    private static void addTransitionPoint(Level level, Player player, ItemStack stack, BlockPos pos) {
        List<DraftPoint> draft = new ArrayList<>(stack.getOrDefault(ModDataComponents.TRANSITION_DRAFT, List.of()));
        draft.add(new DraftPoint(dimensionOf(level), pos.immutable()));

        if (draft.size() < 2) {
            stack.set(ModDataComponents.TRANSITION_DRAFT, List.copyOf(draft));
            say(player, "Transition exit set at " + pos.toShortString() + " (" + dimensionOf(level)
                    + "). Now click the entrance, in any dimension.", true);
            return;
        }

        DraftPoint a = draft.get(0), b = draft.get(1);
        TransitionData t = new TransitionData();
        t.id = newId("transition");
        t.from = new Pos(a.dim(), a.pos().getX(), a.pos().getY(), a.pos().getZ());
        t.to = new Pos(b.dim(), b.pos().getX(), b.pos().getY(), b.pos().getZ());
        t.active = false;

        try {
            PathStore.save(t);
            stack.remove(ModDataComponents.TRANSITION_DRAFT);
            List<TransitionData> activated = PathStore.relink();
            GhostBlocks.invalidate();
            boolean nowActive = activated.stream().anyMatch(x -> x.id.equals(t.id));
            say(player, "Transition " + t.id + (nowActive ? " saved and ACTIVE" : " saved (inactive, waiting for paths)"), false);
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Failed to save transition!", false);
        }
    }

    // ---------- create_start / create_finish ----------

    private static void setTourBound(Level level, Player player, BlockPos pos, boolean start) {
        String dim = dimensionOf(level);
        Pos at = new Pos(dim, pos.getX(), pos.getY(), pos.getZ());
        try {
            boolean valid = PathStore.loadPaths().stream()
                    .filter(p -> p.points() != null && !p.points().isEmpty())
                    .anyMatch(p -> (start ? p.first() : p.last()).equals(at));
            if (!valid) {
                say(player, start ? "The start must be the FIRST node of a saved path."
                        : "The finish must be the LAST node of a saved path.", true);
                return;
            }

            TourBounds old = TourStore.get();
            TourStore.save(start ? new TourBounds(at, old.finish()) : new TourBounds(old.start(), at));
            GhostBlocks.invalidate();
            say(player, (start ? "Tour start" : "Tour finish") + " set at " + pos.toShortString(), true);
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Failed to save tour bounds!", false);
        }
    }

    // ---------- Left click on block ----------

    public static InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand,
                                                  BlockPos pos, Direction direction) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ComplexTestItem)) return InteractionResult.PASS;

        if (!level.isClientSide()) {
            switch (getMode(stack)) {
                case CREATE_PATH -> {
                    if (player.isShiftKeyDown()) {
                        if (stack.has(ModDataComponents.PATH_POINTS)) {
                            stack.remove(ModDataComponents.PATH_POINTS);
                            say(player, "Path cancelled", true);
                        }
                    } else {
                        finishPath(level, player, stack, pos);
                    }
                }
                case CREATE_TRANSITION -> {
                    if (stack.has(ModDataComponents.TRANSITION_DRAFT)) {
                        stack.remove(ModDataComponents.TRANSITION_DRAFT);
                        say(player, "Transition cancelled", true);
                    }
                }
                case ERASE -> {
                    if (PENDING.remove(player.getUUID()) != null) say(player, "Erase cancelled", true);
                }
                case TELEPORT_PATHS -> cyclePath(player, -1);
            }
        }
        return InteractionResult.SUCCESS; // never break blocks with this item
    }

    // ---------- erase ----------

    private record PendingErase(String pathId, long time) {}
    private static final Map<UUID, PendingErase> PENDING = new HashMap<>();
    private static final long CONFIRM_TICKS = 100; // 5 seconds

    private static void erasePath(Level level, Player player, BlockPos pos) {
        if (TourRide.hasSession()) {
            say(player, "Can't erase while a tour is in progress.", true);
            return;
        }

        String dim = dimensionOf(level);
        try {
            PathData target = null;
            for (PathData p : PathStore.loadPaths()) {
                if (p.points() == null || p.points().isEmpty() || !p.first().dim().equals(dim)) continue;
                if (PathStore.blocksOf(p).contains(pos)) { target = p; break; }
            }
            if (target == null) {
                PENDING.remove(player.getUUID());
                say(player, "No path at this block.", true);
                return;
            }

            final PathData path = target;
            List<TransitionData> linked = PathStore.loadTransitions().stream()
                    .filter(t -> t.from.equals(path.last()) || t.to.equals(path.first())
                            || path.id().equals(t.fromPath) || path.id().equals(t.toPath))
                    .toList();

            long now = level.getGameTime();
            PendingErase pending = PENDING.get(player.getUUID());
            if (pending == null || !pending.pathId().equals(path.id()) || now - pending.time() > CONFIRM_TICKS) {
                PENDING.put(player.getUUID(), new PendingErase(path.id(), now));
                say(player, "Erase " + path.id() + " and " + linked.size()
                        + " transition(s)? Right click again to confirm.", true);
                return;
            }
            PENDING.remove(player.getUUID());

            PathStore.delete(path);
            for (TransitionData t : linked) PathStore.delete(t);

            TourBounds tb = TourStore.get();
            Pos start = path.first().equals(tb.start()) ? null : tb.start();
            Pos finish = path.last().equals(tb.finish()) ? null : tb.finish();
            if (!java.util.Objects.equals(start, tb.start()) || !java.util.Objects.equals(finish, tb.finish())) {
                TourStore.save(new TourBounds(start, finish));
            }

            GhostBlocks.invalidate(); // also refreshes the start cart and portals
            say(player, "Erased " + path.id() + " and " + linked.size() + " transition(s).", false);
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Failed to erase!", false);
        }
    }

    // ---------- teleport_paths ----------

    private static final Map<UUID, String> LAST_VISITED = new HashMap<>();

    private static void cyclePath(Player player, int step) {
        if (!(player instanceof ServerPlayer sp)) return;
        if (sp.isPassenger()) {
            say(player, "Get out of the cart first.", true);
            return;
        }

        try {
            List<PathData> paths = orderedPaths();
            if (paths.isEmpty()) {
                say(player, "No paths saved yet.", true);
                return;
            }

            String last = LAST_VISITED.get(sp.getUUID());
            int cur = -1;
            for (int i = 0; i < paths.size(); i++) {
                if (paths.get(i).id().equals(last)) { cur = i; break; }
            }
            int next = cur == -1 ? (step > 0 ? 0 : paths.size() - 1)
                    : Math.floorMod(cur + step, paths.size());

            PathData p = paths.get(next);
            Pos start = p.first();
            ServerLevel target = sp.level().getServer()
                    .getLevel(ResourceKey.create(Registries.DIMENSION, Identifier.parse(start.dim())));
            if (target == null) {
                say(player, "Dimension " + start.dim() + " is not loaded.", true);
                return;
            }

            List<BlockPos> blocks = PathStore.blocksOf(p);
            float yaw = blocks.isEmpty() ? sp.getYRot() : TourMarker.heading(blocks, true).toYRot();

            sp.teleportTo(target, start.x() + 0.5, start.y() + TourMarker.Y_OFFSET, start.z() + 0.5,
                    Set.of(), yaw, 0.0f, true);
            LAST_VISITED.put(sp.getUUID(), p.id());
            say(player, "Path " + (next + 1) + "/" + paths.size() + ": " + p.id() + " (" + start.dim() + ")", true);
        } catch (Exception e) {
            e.printStackTrace();
            say(player, "Could not read the saved paths.", true);
        }
    }

    /** Paths on the tour route first (in tour order), then the rest sorted by id. */
    private static List<PathData> orderedPaths() throws IOException {
        List<PathData> all = PathStore.loadPaths().stream()
                .filter(p -> p.points() != null && !p.points().isEmpty())
                .sorted(Comparator.comparing(PathData::id))
                .toList();

        List<PathData> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        try {
            for (TourRoute.Segment seg : TourRoute.build().segments()) {
                for (PathData p : all) {
                    if (p.id().equals(seg.pathId()) && seen.add(p.id())) out.add(p);
                }
            }
        } catch (Exception ignored) {
            // no valid route yet: fall back to id order
        }
        for (PathData p : all) if (seen.add(p.id())) out.add(p);
        return out;
    }

    // ---------- Helpers ----------

    private static void reportRelink(Player player) {
        try {
            List<TransitionData> activated = PathStore.relink();
            GhostBlocks.invalidate();
            for (TransitionData t : activated) {
                say(player, "Transition " + t.id + " is now ACTIVE", false);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String dimensionOf(Level level) {
        return level.dimension().identifier().toString(); // was .location() before 26.x
    }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void say(Player player, String text, boolean actionBar) {
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(text), actionBar);
        }
    }
}
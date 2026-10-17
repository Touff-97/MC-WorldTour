package net.byhobby.worldtourmod.item.custom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.byhobby.worldtourmod.component.ModDataComponents;
import net.byhobby.worldtourmod.data.PathData;
import net.byhobby.worldtourmod.data.PathStore;
import net.byhobby.worldtourmod.data.Pos;
import net.byhobby.worldtourmod.data.TransitionData;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
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
        if (player.isShiftKeyDown()) {
            cycleMode(level, player, player.getItemInHand(hand));
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
                case CREATE_PATH -> addPathPoint(player, stack, pos);
                case CREATE_TRANSITION -> addTransitionPoint(level, player, stack, pos);
            }
        }
        return InteractionResult.SUCCESS;
    }

    // ---------- create_path ----------

    private static void addPathPoint(Player player, ItemStack stack, BlockPos pos) {
        List<BlockPos> path = new ArrayList<>(stack.getOrDefault(ModDataComponents.PATH_POINTS, List.of()));
        path.add(pos.immutable());
        stack.set(ModDataComponents.PATH_POINTS, List.copyOf(path));

        say(player, path.size() == 1
                ? "Path started at " + pos.toShortString()
                : "Point " + path.size() + " added: " + pos.toShortString(), true);
    }

    private static void finishPath(Level level, Player player, ItemStack stack, BlockPos lastPos) {
        List<BlockPos> existing = stack.get(ModDataComponents.PATH_POINTS);
        if (existing == null || existing.isEmpty()) {
            say(player, "No active path. Right click a block first.", true);
            return;
        }

        String dim = dimensionOf(level);
        List<Pos> points = new ArrayList<>();
        for (BlockPos p : existing) points.add(new Pos(dim, p.getX(), p.getY(), p.getZ()));
        points.add(new Pos(dim, lastPos.getX(), lastPos.getY(), lastPos.getZ()));

        PathData data = new PathData(newId("path"), points);
        try {
            PathStore.save(data);
            stack.remove(ModDataComponents.PATH_POINTS);
            say(player, "Path saved (" + points.size() + " points): " + data.id(), false);
            reportRelink(player);
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Failed to save path!", false); // path stays on the stack
        }
    }

    // ---------- create_transition ----------

    private static void addTransitionPoint(Level level, Player player, ItemStack stack, BlockPos pos) {
        List<BlockPos> draft = new ArrayList<>(stack.getOrDefault(ModDataComponents.TRANSITION_DRAFT, List.of()));
        draft.add(pos.immutable());

        if (draft.size() < 2) {
            stack.set(ModDataComponents.TRANSITION_DRAFT, List.copyOf(draft));
            say(player, "Transition exit set at " + pos.toShortString() + ". Now click the entrance.", true);
            return;
        }

        String dim = dimensionOf(level); // see implementation, currently does nothing
        BlockPos a = draft.get(0), b = draft.get(1);

        TransitionData t = new TransitionData();
        t.id = newId("transition");
        t.from = new Pos(dimensionOfDraftStart(dim), a.getX(), a.getY(), a.getZ());
        t.to = new Pos(dim, b.getX(), b.getY(), b.getZ());
        t.active = false;

        try {
            PathStore.save(t);
            stack.remove(ModDataComponents.TRANSITION_DRAFT);
            List<TransitionData> activated = PathStore.relink();
            boolean nowActive = activated.stream().anyMatch(x -> x.id.equals(t.id));
            say(player, "Transition " + t.id + (nowActive ? " saved and ACTIVE" : " saved (inactive, waiting for paths)"), false);
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Failed to save transition!", false);
        }
    }

    // ---------- Left click on block ----------

    public static InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand,
                                                  BlockPos pos, Direction direction) {
        ItemStack stack = player.getItemInHand(hand);
        if (!(stack.getItem() instanceof ComplexTestItem)) return InteractionResult.PASS;

        if (!level.isClientSide()) {
            switch (getMode(stack)) {
                case CREATE_PATH -> finishPath(level, player, stack, pos);
                case CREATE_TRANSITION -> {
                    if (stack.has(ModDataComponents.TRANSITION_DRAFT)) {
                        stack.remove(ModDataComponents.TRANSITION_DRAFT);
                        say(player, "Transition cancelled", true);
                    }
                }
            }
        }
        return InteractionResult.SUCCESS; // never break blocks with this item
    }

    // ---------- Helpers ----------

    private static void reportRelink(Player player) {
        try {
            List<TransitionData> activated = PathStore.relink();
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

    // Placeholder, more work is needed to get cross dimension travel
    private static String dimensionOfDraftStart(String currentDim) { return currentDim; }

    private static String newId(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static void say(Player player, String text, boolean actionBar) {
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(text), actionBar);
        }
    }
}
package net.byhobby.worldtourmod.item.custom;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.byhobby.worldtourmod.WorldTourMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.Direction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;


public class ComplexTestItem extends Item {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    // One in-progress path per player (server side only)
    private static final Map<UUID, List<BlockPos>> ACTIVE_PATHS = new HashMap<>();

    public ComplexTestItem(Properties properties) {
        super(properties);
    }

    private static void say(Player player, String text, boolean actionBar) {
        if (player instanceof ServerPlayer sp) {
            sp.sendSystemMessage(Component.literal(text), actionBar);
        }
    }

    // Right click on air
    @Override
    public InteractionResult use(Level level, Player player, InteractionHand hand) {
        say(player, "Right-click", true);
        return super.use(level, player, hand);
    }

    // Right click on block: start the path / add a point
    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();

        say(player, "Right-click on", true);

        if (!level.isClientSide() && player != null) {
            BlockPos pos = context.getClickedPos();
            List<BlockPos> path = ACTIVE_PATHS.computeIfAbsent(player.getUUID(), id -> new ArrayList<>());
            path.add(pos.immutable());

            WorldTourMod.LOGGER.info("Added a point to path: " + path);

            String msg = path.size() == 1
                    ? "Path started at " + pos.toShortString()
                    : "Point " + path.size() + " added: " + pos.toShortString();
            say(player, msg, true);
        }
        return InteractionResult.SUCCESS;
    }

    // Left click on block: add last point, close and save
    public static InteractionResult onAttackBlock(Player player, Level level, InteractionHand hand, BlockPos pos, Direction direction) {
        say(player, "Left-click", true);

        if (hand != InteractionHand.MAIN_HAND
                || !(player.getMainHandItem().getItem() instanceof ComplexTestItem)) {
            return InteractionResult.PASS; // normal behavior for other items
        }

        if (!level.isClientSide()) {
            List<BlockPos> path = ACTIVE_PATHS.remove(player.getUUID());
            WorldTourMod.LOGGER.info("Getting ready to close path: " + path);
            if (path == null || path.isEmpty()) {
                say(player, "No active path. Right-click a block first.", true);
            } else {
                path.add(pos.immutable());
                savePath(level.getServer(), path, player);
                WorldTourMod.LOGGER.info("Final path is: " + path);
            }
        }
        return InteractionResult.SUCCESS; // cancels block breaking
    }

    private static void savePath(MinecraftServer server, List<BlockPos> path, Player player) {
        if (server == null) return;

        List<int[]> data = path.stream().map(p -> new int[]{p.getX(), p.getY(), p.getZ()}).toList();
        String name = "path_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")) + ".json";
        Path dir = server.getWorldPath(LevelResource.ROOT).resolve("worldtour_paths");

        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(name), GSON.toJson(data));
            say(player, "Path saved (" + path.size() + " points): " + name, true);
        } catch (IOException e) {
            e.printStackTrace();
            say(player, "Failed to save path!", true);
        }
    }
}
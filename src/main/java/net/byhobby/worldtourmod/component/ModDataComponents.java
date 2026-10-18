package net.byhobby.worldtourmod.component;

import com.mojang.serialization.Codec;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.core.component.DataComponentType;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.resources.Identifier;

import java.util.List;

public class ModDataComponents {
    // Path being drawn (create_path mode)
    public static final DataComponentType<List<BlockPos>> PATH_POINTS = register("path_points",
            DataComponentType.<List<BlockPos>>builder()
                    .persistent(BlockPos.CODEC.listOf())
                    .networkSynchronized(BlockPos.STREAM_CODEC.apply(ByteBufCodecs.list()))
                    .build());

    // Transition being drawn: 0 or 1 points (create_transition mode)
    public static final DataComponentType<List<DraftPoint>> TRANSITION_DRAFT = register("transition_draft",
            DataComponentType.<List<DraftPoint>>builder()
                    .persistent(DraftPoint.CODEC.listOf())
                    .networkSynchronized(DraftPoint.STREAM_CODEC.apply(ByteBufCodecs.list()))
                    .build());

    // Current ToolMode ordinal
    public static final DataComponentType<Integer> TOOL_MODE = register("tool_mode",
            DataComponentType.<Integer>builder()
                    .persistent(Codec.INT)
                    .networkSynchronized(ByteBufCodecs.VAR_INT)
                    .build());

    private static <T> DataComponentType<T> register(String name, DataComponentType<T> type) {
        return Registry.register(BuiltInRegistries.DATA_COMPONENT_TYPE,
                Identifier.fromNamespaceAndPath("worldtourmod", name), type);
    }

    public static void init() {}
}
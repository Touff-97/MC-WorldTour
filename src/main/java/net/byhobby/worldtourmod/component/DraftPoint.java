package net.byhobby.worldtourmod.component;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

public record DraftPoint(String dim, BlockPos pos) {
    public static final Codec<DraftPoint> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.STRING.fieldOf("dim").forGetter(DraftPoint::dim),
            BlockPos.CODEC.fieldOf("pos").forGetter(DraftPoint::pos)
    ).apply(i, DraftPoint::new));

    public static final StreamCodec<ByteBuf, DraftPoint> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, DraftPoint::dim,
            BlockPos.STREAM_CODEC, DraftPoint::pos,
            DraftPoint::new);
}
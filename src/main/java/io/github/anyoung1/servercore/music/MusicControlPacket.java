package io.github.anyoung1.servercore.music;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;
import java.util.*;

/** 客户端控制意图；服务端仍须校验玩家身份、距离、播放模式和队列长度。 */
public record MusicControlPacket(BlockPos pos, String action, long song, long playlist,
                                 List<Long> queue, int mode) implements CustomPacketPayload {
    public static final Type<MusicControlPacket> TYPE = new Type<>(Identifier.fromNamespaceAndPath("server-core", "music_control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MusicControlPacket> CODEC = StreamCodec.of(
        (b, p) -> {
            b.writeBlockPos(p.pos); b.writeUtf(p.action, 16); b.writeLong(p.song); b.writeLong(p.playlist);
            if (p.queue.size() > 100) throw new IllegalArgumentException("Queue too large");
            b.writeVarInt(p.queue.size()); for (long id : p.queue) b.writeLong(id); b.writeInt(p.mode);
        }, b -> {
            BlockPos pos = b.readBlockPos(); String action = b.readUtf(16); long song = b.readLong(), playlist = b.readLong();
            int count = b.readVarInt();
            if (count < 0 || count > 100) throw new IllegalArgumentException("Queue too large");
            List<Long> queue = new ArrayList<>(); for (int i = 0; i < count; i++) queue.add(b.readLong());
            return new MusicControlPacket(pos, action, song, playlist, queue, b.readInt());
        });
    @Override public Type<MusicControlPacket> type() { return TYPE; }
}

package io.github.anyoung1.servercore.music;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** 服务端发给客户端的播放快照：song=0 表示停止；revision 标识播放状态变化。不得携带 Cookie。 */
public record MusicPacket(BlockPos pos, long song, String title, int durationMs,
                          long revision, long elapsedMs, boolean paused, int mode) implements CustomPacketPayload {
    public static final Type<MusicPacket> TYPE = new Type<>(
        net.minecraft.resources.Identifier.fromNamespaceAndPath("server-core", "music"));
    public static final StreamCodec<RegistryFriendlyByteBuf, MusicPacket> CODEC = StreamCodec.of(
        (b, p) -> {
            b.writeBlockPos(p.pos); b.writeLong(p.song); b.writeUtf(p.title, 120);
            b.writeInt(p.durationMs); b.writeLong(p.revision); b.writeLong(p.elapsedMs);
            b.writeBoolean(p.paused); b.writeInt(p.mode);
        }, b -> new MusicPacket(b.readBlockPos(), b.readLong(), b.readUtf(120),
                                b.readInt(), b.readLong(), b.readLong(), b.readBoolean(), b.readInt()));
    @Override public Type<MusicPacket> type() { return TYPE; }
    public static MusicPacket stop(BlockPos pos) { return new MusicPacket(pos, 0, "", 0, 0, 0, false, 0); }
}

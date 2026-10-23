package io.github.anyoung1.servercore.music;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/** C2S: song=0 stops; S2C: revision identifies a particular playback. No credentials or URLs. */
public record MusicPacket(BlockPos pos, long song, String title, int durationMs,
                          long revision, long elapsedMs) implements CustomPacketPayload {
    // createType(String) applies the mod namespace itself in Minecraft 26.3; the argument is a path.
    public static final Type<MusicPacket> TYPE = CustomPacketPayload.createType("music");
    public static final StreamCodec<RegistryFriendlyByteBuf, MusicPacket> CODEC = StreamCodec.of(
        (b, p) -> {
            b.writeBlockPos(p.pos); b.writeLong(p.song); b.writeUtf(p.title, 120);
            b.writeInt(p.durationMs); b.writeLong(p.revision); b.writeLong(p.elapsedMs);
        }, b -> new MusicPacket(b.readBlockPos(), b.readLong(), b.readUtf(120),
                                b.readInt(), b.readLong(), b.readLong()));
    @Override public Type<MusicPacket> type() { return TYPE; }
    public static MusicPacket stop(BlockPos pos) { return new MusicPacket(pos, 0, "", 0, 0, 0); }
}

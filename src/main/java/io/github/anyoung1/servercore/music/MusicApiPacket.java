package io.github.anyoung1.servercore.music;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** 网易云请求、响应与扫码状态协议；仅传递操作参数和结果，不传递 Cookie。 */
public final class MusicApiPacket {
    private MusicApiPacket() {}
    public record Login(long session, String url, String message, boolean complete) implements CustomPacketPayload {
        public static final Type<Login> TYPE = new Type<>(Identifier.fromNamespaceAndPath("server-core", "music_login"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Login> CODEC = StreamCodec.of(
            (b, p) -> { b.writeLong(p.session); b.writeUtf(p.url, 512); b.writeUtf(p.message, 200); b.writeBoolean(p.complete); },
            b -> new Login(b.readLong(), b.readUtf(512), b.readUtf(200), b.readBoolean()));
        @Override public Type<Login> type() { return TYPE; }
    }
    public record Request(int requestId, String action, String query, long id, int page) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(Identifier.fromNamespaceAndPath("server-core", "music_api_request"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> CODEC = StreamCodec.of(
            (b, p) -> { b.writeInt(p.requestId); b.writeUtf(p.action, 16); b.writeUtf(p.query, 2048); b.writeLong(p.id); b.writeInt(p.page); },
            b -> new Request(b.readInt(), b.readUtf(16), b.readUtf(2048), b.readLong(), b.readInt()));
        @Override public Type<Request> type() { return TYPE; }
    }
    public record Response(int requestId, String json, String error) implements CustomPacketPayload {
        public static final Type<Response> TYPE = new Type<>(Identifier.fromNamespaceAndPath("server-core", "music_api_response"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Response> CODEC = StreamCodec.of(
            (b, p) -> { b.writeInt(p.requestId); b.writeUtf(p.json, 16000); b.writeUtf(p.error, 200); },
            b -> new Response(b.readInt(), b.readUtf(16000), b.readUtf(200)));
        @Override public Type<Response> type() { return TYPE; }
    }
}

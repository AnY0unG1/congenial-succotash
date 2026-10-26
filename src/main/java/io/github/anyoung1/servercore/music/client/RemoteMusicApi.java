package io.github.anyoung1.servercore.music.client;

import com.google.gson.Gson;
import io.github.anyoung1.servercore.music.MusicApiPacket;
import io.github.anyoung1.servercore.music.api.CloudApi;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** 客户端通过数据包请求元数据和授权音源地址；Cookie 始终留在服务器。 */
final class RemoteMusicApi extends CloudApi {
    private static final Gson GSON = new Gson();
    private final AtomicInteger sequence = new AtomicInteger();
    private final ConcurrentMap<Integer, CompletableFuture<String>> pending = new ConcurrentHashMap<>();
    private volatile int generation;

    void receive(MusicApiPacket.Response response) {
        var future = pending.remove(response.requestId());
        if (future == null) return;
        if (response.error().isEmpty()) future.complete(response.json());
        else future.completeExceptionally(new IOException(response.error()));
    }
    void disconnect() {
        generation++;
        pending.values().forEach(f -> f.completeExceptionally(new IOException("与服务器的连接已断开")));
        pending.clear();
    }
    private <T> T call(String action, String query, long id, int page, Class<T> type) throws IOException {
        // 请求编号关联响应，连接代次防止断线后将旧请求发送到新服务器。
        int requestId = sequence.incrementAndGet(), session = generation;
        var future = new CompletableFuture<String>();
        pending.put(requestId, future);
        Minecraft.getInstance().execute(() -> {
            if (session != generation || !ClientPlayNetworking.canSend(MusicApiPacket.Request.TYPE)) {
                future.completeExceptionally(new IOException("服务器不支持共享网易云账号")); return;
            }
            ClientPlayNetworking.send(new MusicApiPacket.Request(requestId, action, query, id, page));
        });
        try { return GSON.fromJson(future.get(100, TimeUnit.SECONDS), type); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IOException("音乐请求已取消"); }
        catch (TimeoutException e) { throw new IOException("服务器音乐请求超时，请重试"); }
        catch (ExecutionException e) {
            if (e.getCause() instanceof IOException failure) throw failure;
            throw new IOException("服务器音乐请求失败");
        } finally { pending.remove(requestId); }
    }
    @Override public SearchPage search(String keyword, int page) throws IOException {
        return call("search", keyword, 0, page, SearchPage.class);
    }
    @Override public Song songFromLink(String link) throws IOException { return call("link", link, 0, 0, Song.class); }
    @Override public URI songUrl(long id) throws IOException { return mediaUri(call("url", "", id, 0, String.class)); }
    @Override public PlaylistPage playlists(int page) throws IOException { return call("playlists", "", 0, page, PlaylistPage.class); }
    @Override public SearchPage playlistSongs(long id, int page) throws IOException { return call("tracks", "", id, page, SearchPage.class); }
}

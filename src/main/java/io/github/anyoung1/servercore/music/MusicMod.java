package io.github.anyoung1.servercore.music;

import io.github.anyoung1.servercore.auth.AuthManager;
import io.github.anyoung1.servercore.music.api.CloudApi;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

/** 共享唱片机入口：主线程管理队列和同步状态，后台线程请求网易云。 */
public final class MusicMod implements ModInitializer {
    public static final int RANGE = 32;
    private record Location(ResourceKey<Level> dimension, BlockPos pos) {}
    private static final class Playing {
        MusicQueue queue;
        CloudApi.Song song;
        PlaybackClock clock = new PlaybackClock(System.nanoTime());
        long revision;
        boolean loading;
        boolean failed;
        ServerPlayer owner;
    }
    private final Map<Location, Playing> playing = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private final MusicApiServer apiServer = new MusicApiServer();
    private long revision;
    private int ticks;

    @Override public void onInitialize() {
        apiServer.register();
        PayloadTypeRegistry.serverboundPlay().register(MusicControlPacket.TYPE, MusicControlPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MusicPacket.TYPE, MusicPacket.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MusicControlPacket.TYPE, (packet, context) -> {
            ServerPlayer player = context.player();
            if (context.server().isDedicatedServer() && !AuthManager.isAuthenticated(player.getUUID())) return;
            if (player.isSpectator() || player.position().distanceToSqr(Vec3.atCenterOf(packet.pos())) > 64
                || !player.level().hasChunkAt(packet.pos()) || !player.level().getBlockState(packet.pos()).is(Blocks.JUKEBOX)) return;
            long now = System.nanoTime();
            if (now - cooldown.getOrDefault(player.getUUID(), 0L) < 250_000_000L) return;
            cooldown.put(player.getUUID(), now);
            Location loc = new Location(player.level().dimension(), packet.pos().immutable());
            Playing state = playing.get(loc);
            if (packet.action().equals("stop")) { playing.remove(loc); return; }
            if (packet.action().equals("play")) {
                if (packet.song() <= 0 || packet.playlist() < 0 || packet.mode() < 0 || packet.mode() > 3) return;
                if (state != null && state.loading) { tell(player, "正在准备歌曲，请稍候或停止播放"); return; }
                if (playing.size() >= 128 && state == null) return;
                Playing selected = new Playing(); selected.owner = player; selected.loading = true;
                selected.revision = ++revision; playing.put(loc, selected);
                CloudApi api = apiServer.currentApi();
                Thread.startVirtualThread(() -> {
                    try {
                        List<Long> ids = packet.playlist() > 0 ? api.playlistIds(packet.playlist())
                            : packet.queue().isEmpty() ? List.of(packet.song()) : packet.queue();
                        MusicQueue queue = new MusicQueue(ids, packet.song(), packet.mode());
                        CloudApi.Song song = prepareSong(api, packet.song());
                        context.server().execute(() -> {
                            // 忽略停止、换歌或拆除唱片机后到达的旧请求。
                            if (playing.get(loc) != selected) return;
                            selected.queue = queue; ready(selected, song);
                        });
                    } catch (Exception e) {
                        context.server().execute(() -> {
                            // 忽略停止、换歌或拆除唱片机后到达的旧请求。
                            if (playing.get(loc) != selected) return;
                            playing.remove(loc); tell(player, "歌曲加载失败，请检查账号播放权限或更换歌曲");
                        });
                    }
                });
                tell(player, "正在准备播放列表…"); return;
            }
            if (state == null || state.queue == null) return;
            if (packet.action().equals("mode")) {
                if (packet.mode() >= 0 && packet.mode() <= 3) state.queue.mode(packet.mode());
                return;
            }
            if (state.loading) { tell(player, "正在切换歌曲，请稍候"); return; }
            switch (packet.action()) {
                case "pause" -> {
                    if (state.failed) loadCurrent(context.server(), loc, state);
                    else { state.clock.toggle(now); state.revision = ++revision; }
                }
                case "next" -> advance(context.server(), loc, state, true, false);
                case "previous" -> advance(context.server(), loc, state, true, true);
                default -> { }
            }
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 5 != 0) return;
            long now = System.nanoTime();
            playing.entrySet().removeIf(e -> {
                var level = server.getLevel(e.getKey().dimension());
                return level == null || !level.hasChunkAt(e.getKey().pos()) || !level.getBlockState(e.getKey().pos()).is(Blocks.JUKEBOX);
            });
            for (var entry : new ArrayList<>(playing.entrySet())) {
                Playing state = entry.getValue();
                if (!state.loading && state.song != null && !state.clock.paused() && state.clock.elapsed(now) >= state.song.duration())
                    advance(server, entry.getKey(), state, false, false);
            }
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!ServerPlayNetworking.canSend(player, MusicPacket.TYPE)) continue;
                Playing nearest = null; Location location = null;
                double distance = RANGE * RANGE;
                for (var entry : playing.entrySet()) {
                    if (!entry.getKey().dimension().equals(player.level().dimension()) || entry.getValue().song == null) continue;
                    double d = player.position().distanceToSqr(Vec3.atCenterOf(entry.getKey().pos()));
                    if (d < distance) { nearest = entry.getValue(); location = entry.getKey(); distance = d; }
                }
                if (nearest == null || (server.isDedicatedServer() && !AuthManager.isAuthenticated(player.getUUID())))
                    ServerPlayNetworking.send(player, MusicPacket.stop(BlockPos.ZERO));
                else {
                    var song = nearest.song;
                    ServerPlayNetworking.send(player, new MusicPacket(location.pos(), song.id(), song.name(), song.duration(),
                        nearest.revision, nearest.clock.elapsed(now), nearest.clock.paused() || nearest.loading, nearest.queue.mode()));
                }
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cooldown.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { playing.clear(); cooldown.clear(); ticks = 0; });
    }
    private static CloudApi.Song prepareSong(CloudApi api, long id) throws java.io.IOException {
        CloudApi.Song song = api.song(id);
        if (song.duration() < 1000 || song.duration() > 3_600_000) throw new java.io.IOException("歌曲时长不支持");
        api.songUrl(id);
        return new CloudApi.Song(song.id(), song.name().replaceAll("[\\p{Cntrl}§]", ""), song.duration());
    }
    private void ready(Playing state, CloudApi.Song song) {
        state.song = song; state.clock = new PlaybackClock(System.nanoTime());
        state.loading = false; state.failed = false; state.revision = ++revision;
    }
    private void advance(MinecraftServer server, Location loc, Playing state, boolean manual, boolean previous) {
        if (previous) state.queue.previous();
        else if (!state.queue.next(manual)) { playing.remove(loc); return; }
        loadCurrent(server, loc, state);
    }
    private void loadCurrent(MinecraftServer server, Location loc, Playing state) {
        state.loading = true; state.revision = ++revision;
        // 在服务端线程捕获目标歌曲，后台线程只请求数据，不修改播放队列。
        long songId = state.queue.current();
        CloudApi api = apiServer.currentApi();
        Thread.startVirtualThread(() -> {
            try {
                CloudApi.Song song = prepareSong(api, songId);
                server.execute(() -> { if (playing.get(loc) == state) ready(state, song); });
            } catch (Exception e) {
                server.execute(() -> {
                    if (playing.get(loc) != state) return;
                    // 加载失败时保留队列，允许用户跳过不可用歌曲，不丢失整个歌单。
                    state.loading = false;
                    state.failed = true;
                    if (!state.clock.paused()) state.clock.toggle(System.nanoTime());
                    state.revision = ++revision;
                    tell(state.owner, "切歌失败，已暂停；可点下一首跳过或重新选择歌曲");
                });
            }
        });
    }
    private static void tell(ServerPlayer player, String message) {
        if (player != null) player.sendSystemMessage(Component.literal(message));
    }
}

package io.github.anyoung1.servercore.music;

import io.github.anyoung1.servercore.auth.AuthManager;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import java.util.*;

public final class MusicMod implements DedicatedServerModInitializer {
    public static final int RANGE = 32;
    private record Location(ResourceKey<Level> dimension, BlockPos pos) {}
    private record Playing(MusicPacket packet, long started) {}
    private final Map<Location, Playing> playing = new HashMap<>();
    private final Map<UUID, Long> cooldown = new HashMap<>();
    private long revision;
    private int ticks;

    @Override public void onInitializeServer() {
        PayloadTypeRegistry.serverboundPlay().register(MusicPacket.TYPE, MusicPacket.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MusicPacket.TYPE, MusicPacket.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(MusicPacket.TYPE, (packet, context) -> {
            ServerPlayer p = context.player();
            // Match the existing dedicated server's login protection. Single player needs no server login.
            if (context.server().isDedicatedServer() && !AuthManager.isAuthenticated(p.getUUID())) return;
            if (p.isSpectator() || p.position().distanceToSqr(Vec3.atCenterOf(packet.pos())) > 64
                || !p.level().hasChunkAt(packet.pos())
                || !p.level().getBlockState(packet.pos()).is(Blocks.JUKEBOX)) return;
            long now = System.nanoTime();
            if (now - cooldown.getOrDefault(p.getUUID(), 0L) < 1_000_000_000L) return;
            cooldown.put(p.getUUID(), now);
            Location loc = new Location(p.level().dimension(), packet.pos());
            if (packet.song() == 0) { playing.remove(loc); return; }
            if (packet.song() < 0 || packet.durationMs() < 1000 || packet.durationMs() > 3_600_000) return;
            if (playing.size() >= 128 && !playing.containsKey(loc)) return;
            // Ignore client supplied revision/elapsed; the server owns the playback clock.
            String title = packet.title().replaceAll("[\\p{Cntrl}§]", "");
            playing.put(loc, new Playing(new MusicPacket(packet.pos(), packet.song(), title,
                packet.durationMs(), ++revision, 0), now));
        });
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (++ticks % 20 != 0) return;
            long now = System.nanoTime();
            playing.entrySet().removeIf(e -> {
                var level = server.getLevel(e.getKey().dimension());
                return level == null || !level.hasChunkAt(e.getKey().pos())
                    || !level.getBlockState(e.getKey().pos()).is(Blocks.JUKEBOX)
                    || (now - e.getValue().started()) / 1_000_000 >= e.getValue().packet().durationMs();
            });
            for (ServerPlayer p : server.getPlayerList().getPlayers()) {
                if (!ServerPlayNetworking.canSend(p, MusicPacket.TYPE)) continue;
                Playing nearest = null;
                double distance = RANGE * RANGE;
                for (var e : playing.entrySet()) {
                    if (!e.getKey().dimension().equals(p.level().dimension())) continue;
                    double d = p.position().distanceToSqr(Vec3.atCenterOf(e.getKey().pos()));
                    if (d < distance) { nearest = e.getValue(); distance = d; }
                }
                if (nearest == null || (server.isDedicatedServer() && !AuthManager.isAuthenticated(p.getUUID()))) {
                    ServerPlayNetworking.send(p, MusicPacket.stop(BlockPos.ZERO));
                } else {
                    MusicPacket s = nearest.packet();
                    ServerPlayNetworking.send(p, new MusicPacket(s.pos(), s.song(), s.title(), s.durationMs(),
                        s.revision(), (now - nearest.started()) / 1_000_000));
                }
            }
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> cooldown.remove(handler.player.getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { playing.clear(); cooldown.clear(); ticks = 0; });
    }
}

package io.github.anyoung1.servercore.music.client;

import io.github.anyoung1.servercore.music.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;
import net.minecraft.sounds.SoundSource;
import net.minecraft.core.BlockPos;

/** 客户端同步入口：根据服务端 revision 切换播放，按距离衰减并在断线时清理。 */
public final class MusicClient implements ClientModInitializer {
    static final RemoteMusicApi API = new RemoteMusicApi();
    private static final MusicAudio AUDIO = new MusicAudio();
    static volatile String status = "等待唱片机播放";
    private static MusicPacket playing;
    private static long lastPacket;
    private static long loginSession;
    static MusicLoginScreen loginScreen;
    static void retry() { playing = null; AUDIO.stop(); }
    static MusicPacket state(BlockPos pos) { return playing != null && playing.pos().equals(pos) ? playing : null; }
    @Override public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(MusicApiPacket.Response.TYPE, (packet, context) -> API.receive(packet));
        ClientPlayNetworking.registerGlobalReceiver(MusicApiPacket.Login.TYPE, (packet, context) -> {
            var client = Minecraft.getInstance();
            if (loginScreen != null && loginScreen.session == packet.session()) loginScreen.update(packet);
            else if (!packet.complete() && loginSession != packet.session()) {
                loginSession = packet.session();
                var screen = new MusicLoginScreen(packet);
                client.setScreenAndShow(screen); loginScreen = screen;
            }
        });
        ClientPlayNetworking.registerGlobalReceiver(MusicPacket.TYPE, (packet, context) -> {
            lastPacket = System.nanoTime();
            if (packet.song() == 0) {
                if (playing != null) { playing = null; AUDIO.stop(); status = "等待唱片机播放"; }
            } else {
                boolean changed = playing == null || playing.revision() != packet.revision() || !playing.pos().equals(packet.pos());
                playing = packet;
                if (changed) {
                    if (packet.paused()) { AUDIO.stop(); status = "已暂停：" + packet.title(); }
                    else { status = "正在获取音源：" + packet.title(); AUDIO.play(API, packet, text -> status = text); }
                }
            }
        });
        UseBlockCallback.EVENT.register((player, level, hand, hit) -> {
            if (!level.isClientSide() || hand != InteractionHand.MAIN_HAND || !player.isShiftKeyDown()
                || !level.getBlockState(hit.getBlockPos()).is(Blocks.JUKEBOX)) return InteractionResult.PASS;
            if (!ClientPlayNetworking.canSend(MusicControlPacket.TYPE)) {
                player.sendOverlayMessage(net.minecraft.network.chat.Component.literal("服务器未安装共享唱片机模组"));
                return InteractionResult.FAIL;
            }
            Minecraft.getInstance().setScreenAndShow(new MusicScreen(hit.getBlockPos()));
            return InteractionResult.SUCCESS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (playing == null) return;
            if (client.level == null || client.player == null || System.nanoTime() - lastPacket > 5_000_000_000L
                || !client.level.getBlockState(playing.pos()).is(Blocks.JUKEBOX)) {
                retry(); return;
            }
            double distance = client.player.position().distanceTo(Vec3.atCenterOf(playing.pos()));
            float attenuation = (float) Math.max(0, 1 - distance / MusicMod.RANGE);
            AUDIO.volume(client.options.getFinalSoundSourceVolume(SoundSource.RECORDS) * attenuation);
        });
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            API.disconnect();
            loginSession = 0;
            loginScreen = null;
            retry(); status = "等待唱片机播放";
        });
    }
}

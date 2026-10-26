package io.github.anyoung1.servercore.music;

import io.github.anyoung1.servercore.music.api.CloudApi;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** 全服只保留一个扫码会话，防止多个管理员的确认结果相互覆盖。 */
final class MusicQrLogin {
    @FunctionalInterface interface SaveCookie { void save(String cookie) throws IOException; }
    private final SaveCookie save;
    private volatile Session current;
    private long sequence;
    private static final class Session {
        final long id;
        final CommandSourceStack source;
        final ServerPlayer player;
        Thread worker;
        Session(long id, CommandSourceStack source) { this.id = id; this.source = source; this.player = source.getPlayer(); }
    }
    MusicQrLogin(SaveCookie save) { this.save = save; }

    int start(CommandSourceStack source) {
        var player = source.getPlayer();
        if (player == null || !ServerPlayNetworking.canSend(player, MusicApiPacket.Login.TYPE)) {
            source.sendFailure(Component.literal("请在安装新版模组的游戏客户端执行 /music login")); return 0;
        }
        cancel("已开始新的扫码登录");
        Session session = new Session(++sequence, source); current = session;
        send(session, "", "正在获取二维码…", false);
        session.worker = Thread.ofVirtual().start(() -> {
            CloudApi loginApi = new CloudApi();
            try {
                String key = loginApi.qrKey();
                String url = "https://music.163.com/login?codekey=" + URLEncoder.encode(key, StandardCharsets.UTF_8);
                update(session, url, "请用网易云音乐 APP 扫码，并在手机上确认（3 分钟内）", false);
                long deadline = System.nanoTime() + 180_000_000_000L;
                while (current == session && System.nanoTime() < deadline) {
                    Thread.sleep(2000);
                    var result = loginApi.qrStatus(key);
                    if (result.code() == 803) {
                        source.getServer().execute(() -> {
                            if (!valid(session)) return;
                            try {
                                save.save(result.cookie());
                                finish(session, "登录成功，Cookie 已保存，全服共用此账号");
                            } catch (IOException e) { finish(session, "登录成功但保存失败，请检查服务器配置文件权限"); }
                        });
                        return;
                    }
                    if (result.code() == 800) { update(session, "", "二维码已过期，请重新执行 /music login", true); return; }
                    update(session, "", result.code() == 802 ? "已扫码，请在手机上确认登录" : "等待网易云音乐 APP 扫码", false);
                }
                update(session, "", "二维码已过期，请重新执行 /music login", true);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            catch (Exception e) { update(session, "", "扫码登录请求失败，请重新执行 /music login", true); }
        });
        return 1;
    }
    // 异步请求返回后再次检查会话、连接和权限，过期结果不得覆盖当前账号。
    private boolean valid(Session session) {
        if (current != session) return false;
        if (session.source.getServer().getPlayerList().getPlayer(session.player.getUUID()) != session.player
            || !MusicApiServer.canManage(session.player.createCommandSourceStack())) {
            cancel("扫码登录已取消：连接断开或管理员权限失效"); return false;
        }
        return true;
    }
    private void update(Session session, String url, String message, boolean done) {
        session.source.getServer().execute(() -> {
            if (!valid(session)) return;
            if (done) finish(session, message); else send(session, url, message, false);
        });
    }
    private void finish(Session session, String message) {
        if (current != session) return;
        current = null; send(session, "", message, true);
    }
    void cancel(String message) {
        Session session = current; current = null;
        if (session == null) return;
        if (session.worker != null) session.worker.interrupt();
        if (session.source.getServer().getPlayerList().getPlayer(session.player.getUUID()) == session.player)
            send(session, "", message, true);
    }
    void cancel(ServerPlayer player, long sessionId) {
        Session session = current;
        if (session != null && session.player == player && session.id == sessionId) cancel("扫码登录已取消");
    }
    void disconnected(ServerPlayer player) {
        Session session = current;
        if (session != null && session.player == player) cancel("扫码登录已取消");
    }
    private static void send(Session session, String url, String message, boolean done) {
        ServerPlayNetworking.send(session.player, new MusicApiPacket.Login(session.id, url, message, done));
    }
}

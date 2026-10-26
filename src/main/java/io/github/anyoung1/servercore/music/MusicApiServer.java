package io.github.anyoung1.servercore.music;

import com.google.gson.Gson;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.anyoung1.servercore.auth.AuthManager;
import io.github.anyoung1.servercore.music.api.CloudApi;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.Semaphore;

/** 服务端网易云请求代理：检查玩家登录、限制并发，并集中管理共享账号。 */
public final class MusicApiServer {
    private static final Gson GSON = new Gson();
    private final MusicConfig config = new MusicConfig(FabricLoader.getInstance().getConfigDir().resolve("server-core-music.json"));
    private volatile CloudApi api = new CloudApi();
    CloudApi currentApi() { return api; }
    private final MusicQrLogin qrLogin = new MusicQrLogin(this::saveCookie);
    private final Map<net.minecraft.server.level.ServerPlayer, Integer> active = new HashMap<>();
    private final Semaphore capacity = new Semaphore(32);

    public void register() {
        PayloadTypeRegistry.serverboundPlay().register(MusicApiPacket.Request.TYPE, MusicApiPacket.Request.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MusicApiPacket.Response.TYPE, MusicApiPacket.Response.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MusicApiPacket.Login.TYPE, MusicApiPacket.Login.CODEC);
        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            try { config.load(); api = new CloudApi(config.cookie()); }
            catch (IOException e) { LoggerFactory.getLogger("server-core").error("无法加载音乐配置，请检查 server-core-music.json"); }
        });
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> { active.clear(); api = new CloudApi(); });
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> qrLogin.cancel("服务器正在关闭"));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> qrLogin.disconnected(handler.player));
        ServerPlayNetworking.registerGlobalReceiver(MusicApiPacket.Request.TYPE, (request, context) -> {
            var player = context.player();
            var server = context.server();
            if (request.action().equals("cancel_login")) {
                qrLogin.cancel(player, request.id()); return;
            }
            if (!ServerPlayNetworking.canSend(player, MusicApiPacket.Response.TYPE)) return;
            if (server.isDedicatedServer() && !AuthManager.isAuthenticated(player.getUUID())) {
                reply(player, request.requestId(), "", "请先登录服务器"); return;
            }
            if (active.getOrDefault(player, 0) >= 3 || !capacity.tryAcquire()) {
                reply(player, request.requestId(), "", "音乐服务繁忙，请稍后重试"); return;
            }
            active.merge(player, 1, Integer::sum);
            // 固定本次请求使用的账号；若管理员中途切换账号，丢弃旧账号结果。
            CloudApi snapshot = api;
            Thread.startVirtualThread(() -> {
                String json = "", error = "";
                try {
                    json = execute(snapshot, request);
                    if (json.length() > 16000) throw new IOException("音乐响应过大");
                } catch (IOException e) {
                    // 网络异常可能包含请求详情，只向玩家展示受控错误文案，避免泄露凭据。
                    error = e.getMessage() != null && e.getMessage().matches("[\\p{IsHan}].*")
                        ? e.getMessage() : "网易云请求失败，请稍后重试";
                } catch (Exception e) { error = "网易云返回异常，请稍后重试"; }
                finally { capacity.release(); }
                String result = json, failure = error;
                server.execute(() -> {
                    active.computeIfPresent(player, (key, count) -> count <= 1 ? null : count - 1);
                    if (server.getPlayerList().getPlayer(player.getUUID()) != player) return;
                    if (server.isDedicatedServer() && !AuthManager.isAuthenticated(player.getUUID())) return;
                    if (snapshot != api) reply(player, request.requestId(), "", "服务器网易云账号已更新，请重试");
                    else reply(player, request.requestId(), result, failure);
                });
            });
        });
        CommandRegistrationCallback.EVENT.register((dispatcher, registry, environment) -> dispatcher.register(
            Commands.literal("music").requires(MusicApiServer::canManage)
                .then(Commands.literal("login").executes(ctx -> qrLogin.start(ctx.getSource()))
                    .then(Commands.literal("cancel").executes(ctx -> { qrLogin.cancel("管理员取消了扫码登录"); return 1; })))
                .then(Commands.literal("cookie")
                    .then(Commands.literal("set").then(Commands.argument("value", StringArgumentType.greedyString())
                        .executes(ctx -> change(ctx.getSource(), StringArgumentType.getString(ctx, "value"), false))))
                    .then(Commands.literal("clear").executes(ctx -> change(ctx.getSource(), "", false)))
                    .then(Commands.literal("reload").executes(ctx -> change(ctx.getSource(), "", true)))
                    .then(Commands.literal("status").executes(ctx -> {
                        ctx.getSource().sendSuccess(() -> Component.literal(config.cookie().isBlank()
                            ? "网易云 Cookie：未配置" : "网易云 Cookie：已配置（在账号歌单中验证登录状态）"), false);
                        return 1;
                    })) )));
    }

    static boolean canManage(CommandSourceStack source) {
        return source.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
            && (!source.isPlayer() || !source.getServer().isDedicatedServer() || AuthManager.isAuthenticated(source.getPlayer().getUUID()));
    }
    private void saveCookie(String cookie) throws IOException {
        config.setCookie(cookie); api = new CloudApi(config.cookie());
    }

    static String execute(CloudApi api, MusicApiPacket.Request request) throws IOException {
        if (request.page() < 0 || request.page() > 100000) throw new IOException("页码无效");
        return switch (request.action()) {
            case "search" -> {
                if (request.query().isBlank() || request.query().length() > 100) throw new IOException("搜索关键词无效");
                yield GSON.toJson(api.search(request.query(), request.page()));
            }
            case "link" -> GSON.toJson(api.songFromLink(request.query()));
            case "url" -> GSON.toJson(api.songUrl(request.id()).toString());
            case "playlists" -> GSON.toJson(api.playlists(request.page()));
            case "tracks" -> GSON.toJson(api.playlistSongs(request.id(), request.page()));
            default -> throw new IOException("不支持的音乐请求");
        };
    }

    private int change(CommandSourceStack source, String cookie, boolean reload) {
        try {
            if (reload) config.load(); else config.setCookie(cookie);
            api = new CloudApi(config.cookie());
            qrLogin.cancel("Cookie 已由管理员修改，本次扫码登录已取消");
            source.sendSuccess(() -> Component.literal("服务器网易云 Cookie 已更新并保存；所有玩家的新请求立即生效"), false);
            return 1;
        } catch (IOException e) { source.sendFailure(Component.literal("Cookie 更新失败，请检查格式及配置文件权限")); return 0; }
    }
    private static void reply(net.minecraft.server.level.ServerPlayer player, int id, String json, String error) {
        ServerPlayNetworking.send(player, new MusicApiPacket.Response(id, json, error));
    }
}

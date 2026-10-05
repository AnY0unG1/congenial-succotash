package io.github.anyoung1.servercore.auth;

import io.github.anyoung1.servercore.ServerCore;
import io.github.anyoung1.servercore.storage.AccountStorage;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.network.chat.Component;

public final class AuthEvents {

    private AuthEvents() {
    }

    public static void register() {

        ServerPlayConnectionEvents.JOIN.register(
                (handler, sender, server) -> {

                    var player = handler.getPlayer();

                    String username =
                            player.getGameProfile().name();

                    boolean registered =
                            AccountStorage.exists(username);

                    AuthSession session =
                            AuthManager.createSession(
                                    player.getUUID(),
                                    username,
                                    registered,
                                    player.getX(),
                                    player.getY(),
                                    player.getZ()
                            );

                    ServerCore.LOGGER.info(
                            "Created authentication session for {}: {}",
                            username,
                            session.getState()
                    );

                    if (registered) {
                        player.sendSystemMessage(
                                Component.literal(
                                        "§e请输入 §f/login <密码> §e登录服务器。"
                                )
                        );
                    } else {
                        player.sendSystemMessage(
                                Component.literal(
                                        "§e欢迎首次进入服务器！"
                                )
                        );

                        player.sendSystemMessage(
                                Component.literal(
                                        "§e请使用 §f/register <密码> <确认密码> §e注册。"
                                )
                        );
                    }
                }
        );

        ServerPlayConnectionEvents.DISCONNECT.register(
                (handler, server) -> {

                    var player = handler.getPlayer();

                    AuthManager.removeSession(
                            player.getUUID()
                    );

                    ServerCore.LOGGER.info(
                            "Removed authentication session for {}",
                            player.getGameProfile().name()
                    );
                }
        );
    }
}
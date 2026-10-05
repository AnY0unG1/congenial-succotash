package io.github.anyoung1.servercore.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.anyoung1.servercore.auth.AuthManager;
import io.github.anyoung1.servercore.auth.AuthSession;
import io.github.anyoung1.servercore.auth.AuthState;
import io.github.anyoung1.servercore.storage.AccountStorage;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class LoginCommand {

    private static final int MAX_ATTEMPTS = 5;

    private static final Map<UUID, Integer> FAILED_ATTEMPTS =
            new ConcurrentHashMap<>();

    private LoginCommand() {
    }

    public static void register() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {

                    dispatcher.register(
                            Commands.literal("login")
                                    .then(
                                            Commands.argument(
                                                            "password",
                                                            StringArgumentType.word()
                                                    )
                                                    .executes(context -> {

                                                        var source =
                                                                context.getSource();

                                                        var player =
                                                                source.getPlayerOrException();

                                                        AuthSession session =
                                                                AuthManager.getSession(
                                                                        player.getUUID()
                                                                );

                                                        if (session == null) {
                                                            source.sendFailure(
                                                                    Component.literal(
                                                                            "§c认证会话不存在，请重新连接服务器。"
                                                                    )
                                                            );

                                                            return 0;
                                                        }

                                                        if (session.isAuthenticated()) {
                                                            source.sendFailure(
                                                                    Component.literal(
                                                                            "§c你已经登录了。"
                                                                    )
                                                            );

                                                            return 0;
                                                        }

                                                        if (session.getState()
                                                                != AuthState.NEEDS_LOGIN) {

                                                            source.sendFailure(
                                                                    Component.literal(
                                                                            "§c你还没有注册，请先使用 /register。"
                                                                    )
                                                            );

                                                            return 0;
                                                        }

                                                        String password =
                                                                StringArgumentType.getString(
                                                                        context,
                                                                        "password"
                                                                );

                                                        char[] passwordChars =
                                                                password.toCharArray();

                                                        boolean valid;

                                                        try {
                                                            valid =
                                                                    AccountStorage.verify(
                                                                            session.getUsername(),
                                                                            passwordChars
                                                                    );
                                                        } finally {
                                                            Arrays.fill(
                                                                    passwordChars,
                                                                    '\0'
                                                            );
                                                        }

                                                        if (!valid) {

                                                            int attempts =
                                                                    FAILED_ATTEMPTS.merge(
                                                                            player.getUUID(),
                                                                            1,
                                                                            Integer::sum
                                                                    );

                                                            int remaining =
                                                                    MAX_ATTEMPTS - attempts;

                                                            if (remaining <= 0) {

                                                                FAILED_ATTEMPTS.remove(
                                                                        player.getUUID()
                                                                );

                                                                player.connection.disconnect(
                                                                        Component.literal(
                                                                                "登录失败次数过多，请重新连接后再试。"
                                                                        )
                                                                );

                                                                return 0;
                                                            }

                                                            source.sendFailure(
                                                                    Component.literal(
                                                                            "§c密码错误。剩余尝试次数："
                                                                                    + remaining
                                                                    )
                                                            );

                                                            return 0;
                                                        }

                                                        FAILED_ATTEMPTS.remove(
                                                                player.getUUID()
                                                        );

                                                        AuthManager.authenticate(
                                                                player.getUUID()
                                                        );

                                                        source.sendSuccess(
                                                                () -> Component.literal(
                                                                        "§a登录成功！"
                                                                ),
                                                                false
                                                        );

                                                        return Command.SINGLE_SUCCESS;
                                                    })
                                    )
                    );
                }
        );
    }
}
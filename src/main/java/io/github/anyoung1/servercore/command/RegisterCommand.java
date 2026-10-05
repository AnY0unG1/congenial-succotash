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

public final class RegisterCommand {

    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 128;

    private RegisterCommand() {
    }

    public static void register() {

        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {

                    dispatcher.register(
                            Commands.literal("register")
                                    .then(
                                            Commands.argument(
                                                            "password",
                                                            StringArgumentType.word()
                                                    )
                                                    .then(
                                                            Commands.argument(
                                                                            "confirmPassword",
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

                                                                        if (session.getState()
                                                                                != AuthState.NEEDS_REGISTER) {

                                                                            source.sendFailure(
                                                                                    Component.literal(
                                                                                            "§c你已经注册过账号。"
                                                                                    )
                                                                            );

                                                                            return 0;
                                                                        }

                                                                        String password =
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "password"
                                                                                );

                                                                        String confirmPassword =
                                                                                StringArgumentType.getString(
                                                                                        context,
                                                                                        "confirmPassword"
                                                                                );

                                                                        if (!password.equals(confirmPassword)) {

                                                                            source.sendFailure(
                                                                                    Component.literal(
                                                                                            "§c两次输入的密码不一致。"
                                                                                    )
                                                                            );

                                                                            return 0;
                                                                        }

                                                                        if (password.length()
                                                                                < MIN_PASSWORD_LENGTH) {

                                                                            source.sendFailure(
                                                                                    Component.literal(
                                                                                            "§c密码至少需要 8 个字符。"
                                                                                    )
                                                                            );

                                                                            return 0;
                                                                        }

                                                                        if (password.length()
                                                                                > MAX_PASSWORD_LENGTH) {

                                                                            source.sendFailure(
                                                                                    Component.literal(
                                                                                            "§c密码不能超过 128 个字符。"
                                                                                    )
                                                                            );

                                                                            return 0;
                                                                        }

                                                                        char[] passwordChars =
                                                                                password.toCharArray();

                                                                        try {
                                                                            boolean registered =
                                                                                    AccountStorage.register(
                                                                                            session.getUsername(),
                                                                                            passwordChars
                                                                                    );

                                                                            if (!registered) {
                                                                                source.sendFailure(
                                                                                        Component.literal(
                                                                                                "§c这个账号已经注册。"
                                                                                        )
                                                                                );

                                                                                return 0;
                                                                            }

                                                                            AuthManager.authenticate(
                                                                                    player.getUUID()
                                                                            );

                                                                            source.sendSuccess(
                                                                                    () -> Component.literal(
                                                                                            "§a注册成功，你已经登录！"
                                                                                    ),
                                                                                    false
                                                                            );

                                                                            return Command.SINGLE_SUCCESS;

                                                                        } finally {
                                                                            Arrays.fill(
                                                                                    passwordChars,
                                                                                    '\0'
                                                                            );
                                                                        }
                                                                    })
                                                    )
                                    )
                    );
                }
        );
    }
}
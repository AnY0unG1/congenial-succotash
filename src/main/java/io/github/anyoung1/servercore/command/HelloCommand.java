package io.github.anyoung1.servercore.command;

import com.mojang.brigadier.Command;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

public final class HelloCommand {

    private HelloCommand() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> {
                    dispatcher.register(
                            Commands.literal("hello")
                                    .executes(context -> {
                                        context.getSource().sendSuccess(
                                                () -> Component.literal("Hello! Server Core is running."),
                                                false
                                        );

                                        return Command.SINGLE_SUCCESS;
                                    })
                    );
                }
        );
    }
}
package io.github.anyoung1.servercore.protection;

import io.github.anyoung1.servercore.auth.AuthManager;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.minecraft.network.chat.Component;

public final class CommunicationProtection {

    private CommunicationProtection() {
    }

    public static void register() {

        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register(
                (message, sender, boundChatType) -> {

                    if (AuthManager.isAuthenticated(sender.getUUID())) {
                        return true;
                    }

                    sender.sendSystemMessage(
                            Component.literal(
                                    "§c登录后才能发送聊天消息。"
                            )
                    );

                    return false;
                }
        );
    }
}
package io.github.anyoung1.servercore.mixin;

import io.github.anyoung1.servercore.auth.AuthManager;
import io.github.anyoung1.servercore.auth.AuthSession;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundChatCommandPacket;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.ServerGamePacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import net.minecraft.network.protocol.game.ServerboundContainerClickPacket;
import net.minecraft.network.protocol.game.ServerboundPlayerActionPacket;
import net.minecraft.network.protocol.game.ServerboundSetCreativeModeSlotPacket;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;

import java.util.Locale;

@Mixin(ServerGamePacketListenerImpl.class)
public abstract class ServerGamePacketListenerImplMixin {

    @Shadow
    public ServerPlayer player;

    /*
     * 未认证玩家只能使用 /login 和 /register。
     */
    @Inject(
            method = "handleChatCommand",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serverCore$blockUnauthenticatedCommands(
            ServerboundChatCommandPacket packet,
            CallbackInfo ci
    ) {
        if (AuthManager.isAuthenticated(player.getUUID())) {
            return;
        }

        String command = packet.command().trim();

        String rootCommand = command
                .split("\\s+", 2)[0]
                .toLowerCase(Locale.ROOT);

        if (rootCommand.equals("login")
                || rootCommand.equals("register")) {
            return;
        }

        player.sendSystemMessage(
                Component.literal(
                        "§c登录后才能使用其他命令。"
                )
        );

        ci.cancel();
    }

    /*
     * 未认证玩家不能改变自己的位置。
     *
     * 允许视角旋转，但如果客户端请求改变 XYZ，
     * 就把玩家同步回进入服务器时记录的位置。
     */
    @Inject(
            method = "handleMovePlayer",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serverCore$lockUnauthenticatedMovement(
            ServerboundMovePlayerPacket packet,
            CallbackInfo ci
    ) {
        AuthSession session =
                AuthManager.getSession(player.getUUID());

        if (session == null || session.isAuthenticated()) {
            return;
        }

        if (!packet.hasPosition()) {
            return;
        }

        double requestedX =
                packet.getX(player.getX());

        double requestedY =
                packet.getY(player.getY());

        double requestedZ =
                packet.getZ(player.getZ());

        double dx =
                requestedX - session.getLockX();

        double dy =
                requestedY - session.getLockY();

        double dz =
                requestedZ - session.getLockZ();

        double distanceSquared =
                dx * dx + dy * dy + dz * dz;

        if (distanceSquared <= 0.0001) {
            return;
        }

        player.teleportTo(
                session.getLockX(),
                session.getLockY(),
                session.getLockZ()
        );

        ci.cancel();
    }
    @Inject(
            method = "handleContainerClick",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serverCore$blockUnauthenticatedContainerClick(
            ServerboundContainerClickPacket packet,
            CallbackInfo ci
    ) {
        if (AuthManager.isAuthenticated(player.getUUID())) {
            return;
        }

        /*
         * 客户端可能已经预测了背包变化。
         * 重新发送服务端真实状态，把客户端恢复回来。
         */
        player.containerMenu.sendAllDataToRemote();

        ci.cancel();
    }

    @Inject(
            method = "handleSetCreativeModeSlot",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serverCore$blockUnauthenticatedCreativeSlot(
            ServerboundSetCreativeModeSlotPacket packet,
            CallbackInfo ci
    ) {
        if (AuthManager.isAuthenticated(player.getUUID())) {
            return;
        }

        player.inventoryMenu.sendAllDataToRemote();

        ci.cancel();
    }

    @Inject(
            method = "handlePlayerAction",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serverCore$blockUnauthenticatedPlayerAction(
            ServerboundPlayerActionPacket packet,
            CallbackInfo ci
    ) {
        if (AuthManager.isAuthenticated(player.getUUID())) {
            return;
        }

        switch (packet.getAction()) {
            case DROP_ITEM:
            case DROP_ALL_ITEMS:
            case SWAP_ITEM_WITH_OFFHAND:
            case STAB:
                ci.cancel();
                break;

            case START_DESTROY_BLOCK:
            case CHANGE_DESTROY_DIRECTION:
            case ABORT_DESTROY_BLOCK:
            case STOP_DESTROY_BLOCK:
                /*
                 * 未认证玩家不能修改世界。
                 *
                 * 客户端会预测方块破坏，因此不能只 cancel packet。
                 * 我们需要：
                 *
                 * 1. 不调用 Vanilla 的 handleBlockBreakAction()
                 * 2. ACK 客户端的 block-change sequence
                 * 3. 把服务端真实 BlockState 发回客户端
                 */

                ServerGamePacketListenerImpl listener =
                        (ServerGamePacketListenerImpl) (Object) this;

                listener.ackBlockChangesUpTo(
                        packet.getSequence()
                );

                BlockPos pos = packet.getPos();

                listener.send(
                        new ClientboundBlockUpdatePacket(
                                player.level(),
                                pos
                        )
                );

                /*
                 * 最后取消原版 handlePlayerAction，
                 * 防止它继续进入 handleBlockBreakAction，
                 * 真正修改服务器世界。
                 */
                ci.cancel();
                break;

            case RELEASE_USE_ITEM:
                /*
                 * 允许客户端通知服务器停止使用物品。
                 */
                break;
        }
    }
}
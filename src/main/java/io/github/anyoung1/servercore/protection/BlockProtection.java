package io.github.anyoung1.servercore.protection;

import io.github.anyoung1.servercore.auth.AuthManager;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.world.InteractionResult;

public final class BlockProtection {

    private BlockProtection() {
    }

    public static void register() {

        // 左键方块：阻止未登录玩家破坏方块
        AttackBlockCallback.EVENT.register(
                (player, world, hand, pos, direction) -> {

                    if (!AuthManager.isAuthenticated(player.getUUID())) {
                        return InteractionResult.FAIL;
                    }

                    return InteractionResult.PASS;
                }
        );

        // 右键方块：阻止开箱子、开门、按钮、拉杆、
        // 工作台、熔炉，以及大多数方块交互
        UseBlockCallback.EVENT.register(
                (player, world, hand, hitResult) -> {

                    if (!AuthManager.isAuthenticated(player.getUUID())) {
                        return InteractionResult.FAIL;
                    }

                    return InteractionResult.PASS;
                }
        );

        // 右键物品：
        // 这一项也很重要，因为放置方块最终涉及物品使用。
        // 同时会阻止未登录玩家使用食物、雪球等物品。
        UseItemCallback.EVENT.register(
                (player, world, hand) -> {

                    if (!AuthManager.isAuthenticated(player.getUUID())) {
                        return InteractionResult.FAIL;
                    }

                    return InteractionResult.PASS;
                }
        );
    }
}
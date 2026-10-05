package io.github.anyoung1.servercore.protection;

import io.github.anyoung1.servercore.auth.AuthManager;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.minecraft.world.InteractionResult;

public final class EntityProtection {

    private EntityProtection() {
    }

    public static void register() {

        // 阻止未认证玩家攻击任何实体
        AttackEntityCallback.EVENT.register(
                (player, world, hand, entity, hitResult) -> {

                    if (!AuthManager.isAuthenticated(player.getUUID())) {
                        return InteractionResult.FAIL;
                    }

                    return InteractionResult.PASS;
                }
        );

        // 阻止未认证玩家右键交互实体
        UseEntityCallback.EVENT.register(
                (player, world, hand, entity, hitResult) -> {

                    if (!AuthManager.isAuthenticated(player.getUUID())) {
                        return InteractionResult.FAIL;
                    }

                    return InteractionResult.PASS;
                }
        );
    }
}
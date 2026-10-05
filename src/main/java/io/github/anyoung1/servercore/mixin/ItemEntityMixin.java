package io.github.anyoung1.servercore.mixin;

import io.github.anyoung1.servercore.auth.AuthManager;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ItemEntity.class)
public abstract class ItemEntityMixin {

    @Inject(
            method = "playerTouch",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serverCore$blockUnauthenticatedPickup(
            Player player,
            CallbackInfo ci
    ) {
        if (!AuthManager.isAuthenticated(player.getUUID())) {
            ci.cancel();
        }
    }
}
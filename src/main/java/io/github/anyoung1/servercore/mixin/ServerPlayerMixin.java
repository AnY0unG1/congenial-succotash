package io.github.anyoung1.servercore.mixin;

import io.github.anyoung1.servercore.auth.AuthManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerMixin {

    @Inject(
            method = "hurtServer",
            at = @At("HEAD"),
            cancellable = true
    )
    private void serverCore$protectUnauthenticatedPlayer(
            ServerLevel level,
            DamageSource source,
            float damage,
            CallbackInfoReturnable<Boolean> cir
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;

        if (!AuthManager.isAuthenticated(player.getUUID())) {
            cir.setReturnValue(false);
        }
    }
}
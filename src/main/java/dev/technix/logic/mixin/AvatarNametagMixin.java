package dev.technix.logic.mixin;

import dev.technix.logic.client.LogicClient;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.entity.Avatar;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(AvatarRenderer.class)
public abstract class AvatarNametagMixin {
    @Inject(method = "shouldShowName(Lnet/minecraft/world/entity/Avatar;D)Z", at = @At("HEAD"), cancellable = true)
    private void logic$hideVanillaName(Avatar entity, double distanceSquared, CallbackInfoReturnable<Boolean> ci) {
        if (LogicClient.hideVanillaNametag(entity)) ci.setReturnValue(false);
    }
}

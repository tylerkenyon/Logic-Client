package dev.technix.logic.mixin;

import dev.technix.logic.client.LogicClient;
import net.minecraft.client.player.LocalPlayer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LocalPlayer.class)
public class LocalPlayerInputMixin {
    @Inject(method = "aiStep", at = @At(value = "INVOKE", shift = At.Shift.AFTER,
        target = "Lnet/minecraft/client/player/ClientInput;tick()V"))
    private void logic$afterPlayerInput(CallbackInfo ci) {
        LogicClient.afterPlayerInput((LocalPlayer) (Object) this);
    }
}

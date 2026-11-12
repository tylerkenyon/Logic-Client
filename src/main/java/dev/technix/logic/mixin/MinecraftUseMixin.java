package dev.technix.logic.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.impl.FastPlace;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Slice;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(Minecraft.class)
public class MinecraftUseMixin {
    @Shadow private int rightClickDelay;

    private static FastPlace logic$fastPlace() {
        return (FastPlace) LogicClient.MODULES.byName("FastPlace").orElse(null);
    }

    @Inject(method = "handleKeybinds", at = @At("HEAD"))
    private void logic$clearUseDelay(CallbackInfo ci) {
        LogicClient.beforeKeybinds();
        FastPlace module = logic$fastPlace();
        if (module != null && module.applicable()) rightClickDelay = 0;
    }

    @ModifyExpressionValue(method = "handleKeybinds", require = 1, allow = 1,
        slice = @Slice(from = @At(value = "FIELD", ordinal = 1,
            target = "Lnet/minecraft/client/Options;keyAttack:Lnet/minecraft/client/KeyMapping;")),
        at = @At(value = "INVOKE", ordinal = 0, target = "Lnet/minecraft/client/KeyMapping;consumeClick()Z"))
    private boolean logic$attackClick(boolean manualClick) {
        return LogicClient.attackClick(manualClick);
    }

    @Inject(method = "handleKeybinds", at = @At("RETURN"))
    private void logic$applySprintReset(CallbackInfo ci) {
        LogicClient.afterKeybinds();
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void logic$rateLimit(CallbackInfo ci) {
        if (LogicClient.running() && !LogicClient.allowUse()) ci.cancel();
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void logic$preventAttack(CallbackInfoReturnable<Boolean> ci) {
        if (LogicClient.blockAttack()) ci.setReturnValue(false);
    }

    @Inject(method = "startAttack", require = 2, allow = 2, at = {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;attack(Lnet/minecraft/world/entity/player/Player;Lnet/minecraft/world/entity/Entity;)V"),
        //? if <26.3 {
        @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;piercingAttack(Lnet/minecraft/world/item/component/PiercingWeapon;)V")
        //?} else {
        /*@At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/MultiPlayerGameMode;piercingAttack(Lnet/minecraft/world/item/component/SwingAnimation;Lnet/minecraft/world/item/component/PiercingWeapon;)V")
        *///?}
    })
    private void logic$attackDispatched(CallbackInfoReturnable<Boolean> ci) {
        LogicClient.attackDispatched();
    }
}

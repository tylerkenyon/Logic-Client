package dev.technix.logic.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.technix.logic.client.LogicClient;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(Minecraft.class)
public abstract class TimerMixin {
    @ModifyReturnValue(method = "getTickTargetMillis(F)F", at = @At("RETURN"))
    private float logic$tickDuration(float original) { return LogicClient.tickDuration(original); }
}

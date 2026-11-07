package dev.technix.logic.mixin;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.impl.Backtrack;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MultiPlayerGameMode.class)
public class AttackMixin {
    @Inject(method = "attack", at = @At("HEAD"))
    private void logic$recordAttack(Player player, Entity target, CallbackInfo ci) {
        if (!LogicClient.running()) return;
        dev.technix.logic.Logic.events().post(new dev.technix.logic.event.impl.AttackEvent(target));
        var module = (Backtrack) LogicClient.MODULES.byName("Backtrack").orElse(null);
        if (module != null && module.enabled()) module.recordAttack(target);
    }
}

package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.combat.CombatMath;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

public final class Triggerbot extends Module {
    private boolean attackClickSeen;
    private final NumberSetting cooldown = registerSetting(new NumberSetting(
        "Attack cooldown", "", 1, 0.9, 1, 0.01));
    private final NumberSetting range = registerSetting(new NumberSetting(
        "Range", "", 3, 1, 6, 0.1));
    private final BooleanSetting playersOnly = registerSetting(new BooleanSetting(
        "Players only", "", true));
    private final BooleanSetting ignoreTeams = registerSetting(new BooleanSetting(
        "Ignore teammates", "", true));

    public Triggerbot() {
        super("Triggerbot", "attacks a target with max damage.", Category.COMBAT);
    }

    public void beginKeybinds() { attackClickSeen = false; }

     
    public boolean attackClick(boolean manualClick) {
        if (manualClick) {
            attackClickSeen = true;
            return true;
        }
        if (!enabled() || attackClickSeen || !hasTarget()) return false;
        attackClickSeen = true;
        return true;
    }

    private boolean hasTarget() {
        var mc = mc();
        if (LogicClient.anchorBusy() || !CombatTargets.canAct(mc)
                || !CombatMath.cooldownReady(mc.player.getAttackStrengthScale(0), cooldown.get())
                || !(mc.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target)
                || !CombatTargets.valid(mc, target, playersOnly.get(), ignoreTeams.get(), true)) return false;

        double reach = Math.min(range.get(), mc.player.entityInteractionRange());
        var eye = mc.player.getEyePosition();
        var end = eye.add(mc.player.getViewVector(1).scale(reach));
        var intersection = target.getBoundingBox().clip(eye, end);
        return (target.getBoundingBox().contains(eye) || intersection.isPresent())
            && eye.distanceToSqr(hit.getLocation()) <= reach * reach;
    }
}

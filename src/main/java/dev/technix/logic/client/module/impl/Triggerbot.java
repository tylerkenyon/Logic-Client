package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.combat.CombatMath;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.RangeSetting;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

public final class Triggerbot extends Module {
    private boolean attackClickSeen;
    private LivingEntity observedTarget;
    private long readyAt;
    private final RangeSetting reaction = registerSetting(new RangeSetting(
        "Reaction time (ms)", "Delay after acquiring a target and between automatic attacks", 0, 0, 0, 1000, 10));
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

    public void beginKeybinds() {
        attackClickSeen = false;
        if (!enabled()) { observedTarget = null; return; }
        LivingEntity target = target();
        if (target != observedTarget) {
            observedTarget = target;
            if (target != null) schedule();
        }
    }

    private void schedule() { readyAt = System.nanoTime() + (long) (reaction.random() * 1_000_000); }

     
    public boolean attackClick(boolean manualClick) {
        if (manualClick) {
            markClickSeen();
            return true;
        }
        if (!automaticAttackReady()) return false;
        recordAutomaticAttack();
        return true;
    }

    public boolean automaticAttackReady() {
        return enabled() && !attackClickSeen && observedTarget != null && target() == observedTarget
            && System.nanoTime() >= readyAt
            && CombatMath.cooldownReady(mc().player.getAttackStrengthScale(0), cooldown.get());
    }

    public void recordAutomaticAttack() {
        markClickSeen();
        schedule();
    }

    public void markClickSeen() {
        attackClickSeen = true;
    }

    private LivingEntity target() {
        var mc = mc();
        if (LogicClient.anchorBusy() || !CombatTargets.canAct(mc)
                || !(mc.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target)
                || !CombatTargets.valid(mc, target, playersOnly.get(), ignoreTeams.get(), true)) return null;

        double reach = Math.min(range.get(), mc.player.entityInteractionRange());
        var eye = mc.player.getEyePosition();
        var end = eye.add(mc.player.getViewVector(1).scale(reach));
        var intersection = target.getBoundingBox().clip(eye, end);
        return (target.getBoundingBox().contains(eye) || intersection.isPresent())
            && eye.distanceToSqr(hit.getLocation()) <= reach * reach ? target : null;
    }
    @Override protected void onDisable() { observedTarget = null; readyAt = 0; }
}


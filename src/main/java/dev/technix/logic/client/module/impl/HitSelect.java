package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.combat.HitSelectTiming;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.RangeSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.EntityHitResult;

import java.util.concurrent.ThreadLocalRandom;

public final class HitSelect extends Module {
    private enum Preference { BURST, CRIT, COMBO, TIMER }

    private final ModeSetting preference = registerSetting(new ModeSetting(
        "Preference", "How hit windows are judged.", "BURST", "BURST", "CRIT", "COMBO", "TIMER"));
    private final BooleanSetting gateAutoCombat = registerSetting(new BooleanSetting(
        "Gate Auto Combat", "Apply hit selection to Triggerbot.", true));
    private final BooleanSetting cancelManual = registerSetting(new BooleanSetting(
        "Cancel Manual", "Cancel manual entity attacks in a weak trade window.", true));
    private final BooleanSetting requireCooldown = registerSetting(new BooleanSetting(
        "Require Cooldown", "Wait for vanilla attack cooldown.", true));
    private final NumberSetting cooldownThreshold = registerSetting(new NumberSetting(
        "Cooldown Threshold", "Minimum attack cooldown progress.", 0.92, 0, 1, 0.01));
    private final NumberSetting waitFirstHit = registerSetting(new NumberSetting(
        "Wait First Hit", "Maximum wait for an incoming hit, in milliseconds.", 0, 0, 3000, 25));
    private final NumberSetting tradeDelay = registerSetting(new NumberSetting(
        "Trade Delay", "Milliseconds to wait before hitting back after taking damage.", 0, 0, 3000, 25));
    private final NumberSetting unsafeChance = registerSetting(new NumberSetting(
        "Unsafe Chance", "Percent chance to attack in a weak mode window.", 0, 0, 100, 1));
    private final NumberSetting noTargetChance = registerSetting(new NumberSetting(
        "No Target Chance", "Percent chance to allow an automatic attack request without a target.", 0, 0, 100, 1));
    private final BooleanSetting fakeSwing = registerSetting(new BooleanSetting(
        "Fake Swing", "Play a local swing when an automatic attack is blocked.", false));
    private final NumberSetting fakeSwingDelay = registerSetting(new NumberSetting(
        "Fake Swing Delay", "Minimum milliseconds between fake swings.", 150, 0, 1000, 10));
    private final RangeSetting timerDelay = registerSetting(new RangeSetting(
        "Timer Delay", "Milliseconds between attacks in Timer mode.", 350, 450, 0, 1500, 10));
    private final NumberSetting burstHurtTime = registerSetting(new NumberSetting(
        "Burst HurtTime", "Maximum target hurt time for a burst.", 0, 0, 10, 1));
    private final BooleanSetting critDisableDuringKb = registerSetting(new BooleanSetting(
        "Crit Disable KB", "Skip airborne windows while taking damage.", false));
    private final BooleanSetting critOnlyDamaged = registerSetting(new BooleanSetting(
        "Crit Only Damaged", "Require an incoming hit before airborne windows.", false));
    private final BooleanSetting critRequireFalling = registerSetting(new BooleanSetting(
        "Crit Require Falling", "Require descent in airborne windows.", true));
    private final BooleanSetting comboRequireMove = registerSetting(new BooleanSetting(
        "Combo Require Move", "Require a movement key.", true));
    private final BooleanSetting comboRequireAir = registerSetting(new BooleanSetting(
        "Combo Require Air", "Require being airborne.", true));
    private final BooleanSetting comboRequireDamage = registerSetting(new BooleanSetting(
        "Combo Require Damage", "Require active hurt time.", true));

    private final HitSelectTiming timing = new HitSelectTiming();
    private ClientLevel level;
    private LocalPlayer player;
    private long lastFakeSwing = -1;
    private boolean attackedThisTick;

    public HitSelect() {
        super("Hit Select", "Only lets hits through on good trades.", Category.COMBAT);
        timerDelay.visibleWhen(() -> mode() == Preference.TIMER);
        burstHurtTime.visibleWhen(() -> mode() == Preference.BURST);
        critDisableDuringKb.visibleWhen(() -> mode() == Preference.CRIT);
        critOnlyDamaged.visibleWhen(() -> mode() == Preference.CRIT);
        critRequireFalling.visibleWhen(() -> mode() == Preference.CRIT);
        comboRequireMove.visibleWhen(() -> mode() == Preference.COMBO);
        comboRequireAir.visibleWhen(() -> mode() == Preference.COMBO);
        comboRequireDamage.visibleWhen(() -> mode() == Preference.COMBO);
        cooldownThreshold.visibleWhen(requireCooldown::get);
        fakeSwingDelay.visibleWhen(fakeSwing::get);
    }

    public void startTick() {
        if (!enabled()) return;
        var mc = mc();
        if (player != mc.player || level != mc.level) {
            reset();
            player = mc.player;
            level = mc.level;
        }
        attackedThisTick = false;
        if (player != null && level != null) {
            timing.observe(System.currentTimeMillis(), player.hurtTime, mc.hitResult instanceof EntityHitResult);
        }
    }

    public boolean allowManualAttack(Entity target) {
        return !enabled() || mc().player == null || !cancelManual.get()
            || evaluate(mc().player, target, System.currentTimeMillis());
    }

    public boolean allowAutomaticAttack(Entity target) {
        var localPlayer = mc().player;
        if (!enabled() || !gateAutoCombat.get() || localPlayer == null) return true;
        long now = System.currentTimeMillis();
        if (evaluate(localPlayer, target, now)) return true;
        if (fakeSwing.get() && (lastFakeSwing < 0 || now - lastFakeSwing >= fakeSwingDelay.get().intValue())) {
            //? if <26.3 {
            localPlayer.swing(InteractionHand.MAIN_HAND, false);
            //?} else {
            /*localPlayer.swing(InteractionHand.MAIN_HAND, net.minecraft.world.item.component.SwingAnimation.DEFAULT, false);
            *///?}
            lastFakeSwing = now;
        }
        return false;
    }

    public boolean attackedThisTick() {
        return enabled() && attackedThisTick;
    }

    public void attackDispatched() {
        if (!enabled()) return;
        attackedThisTick = true;
        timing.dispatched(System.currentTimeMillis(), timerDelay.randomInt());
    }

    private boolean evaluate(LocalPlayer localPlayer, Entity target, long now) {
        if (target == null) return rollChance(noTargetChance.get().intValue());
        timing.engage(now);
        if (requireCooldown.get() && localPlayer.getAttackStrengthScale(0.5F) < cooldownThreshold.get().floatValue()) return false;
        Preference mode = mode();
        if (timing.openingHit() && mode != Preference.TIMER) return true;
        if (!timing.tradeReady(now, waitFirstHit.get().intValue(), tradeDelay.get().intValue())) return false;

        boolean favorable = switch (mode) {
            case BURST -> burst(target);
            case CRIT -> crit(localPlayer, target);
            case COMBO -> combo(localPlayer);
            case TIMER -> timing.timerReady(now);
        };
        return favorable || rollChance(unsafeChance.get().intValue());
    }

    private boolean burst(Entity target) {
        return target instanceof LivingEntity living && living.hurtTime <= burstHurtTime.get().intValue();
    }

    private boolean crit(LocalPlayer localPlayer, Entity target) {
        double verticalSpeed = localPlayer.getDeltaMovement().y;
        if (verticalSpeed > 0) return false;
        if (localPlayer.onGround()) return burst(target);
        return (!critDisableDuringKb.get() || localPlayer.hurtTime == 0)
            && (!critOnlyDamaged.get() || timing.receivedHit())
            && (!critRequireFalling.get() || verticalSpeed < 0);
    }

    private boolean combo(LocalPlayer localPlayer) {
        var options = mc().options;
        return (!comboRequireDamage.get() || localPlayer.hurtTime > 0)
            && (!comboRequireAir.get() || !localPlayer.onGround())
            && (!comboRequireMove.get() || options.keyUp.isDown() || options.keyDown.isDown()
                || options.keyLeft.isDown() || options.keyRight.isDown());
    }

    private Preference mode() { return Preference.valueOf(preference.get()); }

    private static boolean rollChance(int percent) {
        return percent > 0 && (percent >= 100 || ThreadLocalRandom.current().nextInt(100) < percent);
    }

    private void reset() {
        timing.reset();
        lastFakeSwing = -1;
        attackedThisTick = false;
    }

    @Override protected void onEnable() { reset(); }

    @Override protected void onDisable() {
        reset();
        player = null;
        level = null;
    }
}

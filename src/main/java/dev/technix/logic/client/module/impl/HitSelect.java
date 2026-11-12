package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.combat.HitSelectTiming;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.StringSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.component.DataComponents;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

import java.util.function.BooleanSupplier;

public final class HitSelect extends Module {
    private final NumberSetting chargeThreshold = registerSetting(new NumberSetting(
        "Charge threshold", "Minimum attack strength.", 0.95, 0.10, 1.00, 0.01));
    private final BooleanSetting requireCritical = registerSetting(new BooleanSetting(
        "Require critical", "Wait for a falling critical hit. Sprinting and spear stabs prevent ordinary critical hits.", false));
    private final BooleanSetting requireSprint = registerSetting(new BooleanSetting(
        "Require sprint", "Attack only while already sprinting. Incompatible with Require critical.", false));
    private final BooleanSetting weaponRequired = registerSetting(new BooleanSetting(
        "Weapon required", "Require a melee weapon in your main hand.", true));
    private final NumberSetting delayTicks = registerSetting(new NumberSetting(
        "Delay ticks", "Minimum client ticks between attacks.", 4, 2, 40, 1));
    private final BooleanSetting players = registerSetting(new BooleanSetting("Players", "Allow player targets.", true));
    private final BooleanSetting hostiles = registerSetting(new BooleanSetting("Hostiles", "Allow hostile mobs.", true));
    private final BooleanSetting passive = registerSetting(new BooleanSetting("Passive", "Allow non-hostile mobs.", false));
    private final BooleanSetting excludeFriends = registerSetting(new BooleanSetting(
        "Exclude friends", "Skip players named in the Friends setting.", true));
    private final StringSetting friends = registerSetting(new StringSetting(
        "Friends", "Comma-separated Minecraft player names.", ""));

    private final HitSelectTiming timing = new HitSelectTiming();
    private ClientLevel level;
    private LocalPlayer player;
    private long tick;

    public HitSelect() {
        super("Hit Select", "Wait for a charged hit when holding attack or when Triggerbot is ready.", Category.COMBAT);
        friends.visibleWhen(excludeFriends::get);
    }

    public void startTick() {
        tick++;
        if (enabled()) updateSession();
    }

    public boolean managesEntityClick() {
        return enabled() && mc().hitResult instanceof EntityHitResult;
    }

    public boolean attackClick(boolean queuedClick, BooleanSupplier triggerReady) {
        var mc = mc();
        // Vanilla stops the click loop on false, so discard queued entity presses now.
        while (mc.options.keyAttack.consumeClick()) {}

        updateSession();
        if (!timing.beginCheck(tick, delayTicks.get().intValue()) || !CombatTargets.canAct(mc)
                || mc.gameMode.isSpectator() || !mc.mouseHandler.isMouseGrabbed() || player.isHandsBusy()
                || LogicClient.anchorBusy()
                || !(mc.hitResult instanceof EntityHitResult hit)
                || !(hit.getEntity() instanceof LivingEntity target) || target.level() != mc.level
                || !CombatTargets.valid(mc, target, false, false, true) || !allowsTarget(target)) return false;

        var weapon = player.getMainHandItem();
        if (weaponRequired.get() && !weapon.is(ItemTags.WEAPON_ENCHANTABLE) && !weapon.is(Items.TRIDENT)) return false;
        if (player.getAttackStrengthScale(0) < chargeThreshold.get().floatValue()) return false;
        if (requireSprint.get() && !player.isSprinting()) return false;
        if (requireCritical.get() && (weapon.has(DataComponents.PIERCING_WEAPON) || !criticalWindow(player))) return false;

        return timing.selectClick(MovementKeys.physicalDown(mc.options.keyAttack), queuedClick, triggerReady);
    }

    private boolean allowsTarget(LivingEntity target) {
        if (target instanceof Player opponent) {
            if (!players.get()) return false;
            if (excludeFriends.get()) {
                String name = opponent.getGameProfile().name();
                for (String friend : friends.get().split(",")) {
                    if (name.equalsIgnoreCase(friend.strip())) return false;
                }
            }
            return true;
        }
        return target instanceof Mob && (target instanceof Enemy ? hostiles.get() : passive.get());
    }

    private static boolean criticalWindow(LocalPlayer player) {
        return player.getAttackStrengthScale(0.5F) > 0.9F
            && !player.onGround() && player.fallDistance > 0 && player.getDeltaMovement().y < 0
            && !player.isInWater() && !player.onClimbable() && !player.isFallFlying()
            && !player.isMobilityRestricted() && !player.isPassenger() && !player.isSprinting();
    }

    public HitSelectTiming.AttackSource attackDispatched() {
        return timing.dispatched(tick);
    }

    public void clearClick() {
        timing.clearClick();
    }

    private void updateSession() {
        var mc = mc();
        if (level == mc.level && player == mc.player) return;
        level = mc.level;
        player = mc.player;
        timing.resetDelay();
    }

    @Override protected void onDisable() {
        level = null;
        player = null;
        timing.resetDelay();
    }
}

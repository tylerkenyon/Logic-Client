package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.combat.SprintResetTiming;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.RangeSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.AttackEvent;
import net.minecraft.world.entity.LivingEntity;
import java.util.concurrent.ThreadLocalRandom;

public final class SprintReset extends Module {
    private final ModeSetting mode = registerSetting(new ModeSetting(
        "Mode", "S-Tap moves backward, W-Tap releases forward, NoStop delays restarting sprint after a hit", "W-Tap", "S-Tap", "W-Tap", "NoStop"));
    private final NumberSetting chance = registerSetting(new NumberSetting(
        "Chance", "Chance to reset sprint on each eligible attack (%)", 100, 0, 100, 1));
    private final RangeSetting delay = registerSetting(new RangeSetting(
        "Reset delay", "Ticks to wait after attacking before resetting sprint", 0, 0, 0, 10, 1));
    private final RangeSetting duration = registerSetting(new RangeSetting(
        "Tap duration", "Ticks to hold the tap or NoStop sprint pause", 1, 1, 1, 10, 1));
    private final RangeSetting cooldown = registerSetting(new RangeSetting(
        "Cooldown", "Ticks after a reset before another attack can trigger it", 0, 0, 0, 40, 1));
    private final BooleanSetting onlyGround = registerSetting(new BooleanSetting(
        "Only on ground", "Require the player to stay grounded during the reset", false));
    private final SprintResetTiming timing = new SprintResetTiming();
    private boolean inputOverridden, movementOverride;
    private boolean forwardWasDown, backwardWasDown, sprintWasDown;
    private String resetMode;
    private net.minecraft.client.player.LocalPlayer owner;

    public SprintReset() { super("Sprint Reset", "Reset sprint after attacking an enemy.", Category.COMBAT); }

    @Subscribe private void onAttack(AttackEvent event) {
        var mc = mc();
        if (CombatTargets.canAct(mc) && event.target() instanceof LivingEntity enemy
                && CombatTargets.valid(mc, enemy, false, true, true)
                && mc.player.isSprinting() && mc.options.keyUp.isDown()
                && (!onlyGround.get() || mc.player.onGround())
                && timing.request(delay.randomInt(), duration.randomInt(), cooldown.randomInt(),
                    chance.get(), ThreadLocalRandom.current().nextDouble())) {
            resetMode = mode.get();
            owner = mc.player;
        }
    }

    public void afterKeybinds() {
        if (!enabled()) return;
        var mc = mc();
        if (!CombatTargets.canAct(mc) || mc.player != owner) {
            timing.reset(); owner = null;
            return;
        }
        if (!mc.options.keyUp.isDown() || onlyGround.get() && !mc.player.onGround()) {
            timing.reset();
            owner = null;
            return;
        }
        if (!timing.tick()) return;

         
         
        forwardWasDown = mc.options.keyUp.isDown();
        backwardWasDown = mc.options.keyDown.isDown();
        sprintWasDown = mc.options.keySprint.isDown();
        inputOverridden = true;
        movementOverride = !resetMode.equals("NoStop");
        if (movementOverride) {
            mc.options.keyUp.setDown(false);
            mc.options.keyDown.setDown(resetMode.equals("S-Tap"));
        }
        MovementKeys.setState(mc.options.keySprint, false, mc.options.toggleSprint().get());
    }

    public void finishMovementTick() {
        releaseInput();
        if (owner != null && (!CombatTargets.canAct(mc()) || mc().player != owner)) {
            timing.reset();
            owner = null;
        }
    }

    private void releaseInput() {
        if (!inputOverridden) return;
        var options = mc().options;
        if (movementOverride) {
            options.keyUp.setDown(forwardWasDown);
            options.keyDown.setDown(backwardWasDown);
        }
        MovementKeys.setState(options.keySprint, sprintWasDown, options.toggleSprint().get());
        inputOverridden = false;
    }

    @Override protected void onDisable() {
        releaseInput();
        timing.reset(); owner = null;
    }
}

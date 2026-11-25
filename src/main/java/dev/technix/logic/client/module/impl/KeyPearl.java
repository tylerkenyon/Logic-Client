package dev.technix.logic.client.module.impl;

import dev.technix.logic.Logic;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.RangeSetting;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;

public final class KeyPearl extends Module {
    private final RangeSetting swapDelay = registerSetting(new RangeSetting(
        "Swap delay", "Milliseconds before selecting a hotbar pearl.", 0, 25, 0, 1000, 1));
    private final RangeSetting throwDelay = registerSetting(new RangeSetting(
        "Throw delay", "Milliseconds between selecting a pearl and throwing it.", 0, 25, 0, 1000, 1));
    private final BooleanSetting restoreSlot = registerSetting(new BooleanSetting(
        "Restore slot", "Return to your original slot after throwing.", true));
    private final RangeSetting restoreDelay = registerSetting(new RangeSetting(
        "Restore delay", "Milliseconds before returning to your original slot.", 0, 25, 0, 1000, 1));

    private enum Phase { SWAP, THROW, RESTORE }
    private LocalPlayer player;
    private ClientLevel level;
    private ClientPacketListener connection;
    private Phase phase;
    private InteractionHand hand;
    private int originalSlot = -1, pearlSlot = -1, managedSlot = -1, scheduledTick;
    private long deadline;
    private boolean requested;

    public KeyPearl() {
        super("KeyPearl", "Press its bind to select and throw one pearl at your current aim.", Category.PLAYER);
        restoreDelay.visibleWhen(restoreSlot::get);
    }

    @Override public boolean actionOnly() { return true; }
    @Override public boolean bindable() { return true; }

    @Override public void onKeybind() {
        if (!enabled()) setEnabled(true);
    }

    @Override protected void onEnable() {
        var mc = mc();
        player = mc == null ? null : mc.player;
        level = mc == null ? null : mc.level;
        connection = mc == null ? null : mc.getConnection();
        requested = true;
    }

    public boolean busy() { return enabled(); }

    private boolean sameSession() {
        var mc = mc();
        return player != null && player == mc.player && level == mc.level && connection == mc.getConnection();
    }

    private boolean canAct() {
        var mc = mc();
        return sameSession() && CombatTargets.canAct(mc) && !player.isHandsBusy()
            && !mc.gameMode.isDestroying() && !mc.options.keyAttack.isDown()
            && !LogicClient.MODULES.get(Blink.class).enabled();
    }

    public void startTick() {
        if (enabled() && !canAct()) setEnabled(false);
    }

    public void beforeKeybinds() {
        if (!enabled()) return;
        if (!canAct()) { setEnabled(false); return; }
        if (requested) {
            if (LogicClient.anchorBusy() || LogicClient.shieldBreakerBusy()) return;
            requested = false;
            LogicClient.MODULES.get(AutoTool.class).yieldForInteraction();
            originalSlot = managedSlot = player.getInventory().getSelectedSlot();
            pearlSlot = findPearl();
            hand = InteractionHand.MAIN_HAND;
            if (pearlSlot < 0 && player.getOffhandItem().is(Items.ENDER_PEARL)) {
                hand = InteractionHand.OFF_HAND;
                pearlSlot = originalSlot;
            }
            if (pearlSlot < 0 || player.getCooldowns().isOnCooldown(
                    hand == InteractionHand.OFF_HAND ? player.getOffhandItem() : player.getInventory().getItem(pearlSlot))) {
                setEnabled(false);
                return;
            }
            phase = pearlSlot == managedSlot ? Phase.THROW : Phase.SWAP;
            schedule(phase == Phase.SWAP ? swapDelay : throwDelay);
            return;
        }
        if (player.getInventory().getSelectedSlot() != managedSlot) {

            originalSlot = -1;
            setEnabled(false);
            return;
        }
        if (System.nanoTime() < deadline || player.tickCount == scheduledTick) return;
        try {
            if (phase == Phase.SWAP) {
                if (!player.getInventory().getItem(pearlSlot).is(Items.ENDER_PEARL)) {
                    setEnabled(false);
                    return;
                }
                selectSlot(pearlSlot);
                phase = Phase.THROW;
                schedule(throwDelay);
            } else if (phase == Phase.THROW) {
                var stack = player.getItemInHand(hand);
                if (!stack.is(Items.ENDER_PEARL) || player.getCooldowns().isOnCooldown(stack)) {
                    setEnabled(false);
                    return;
                }
                LogicClient.MODULES.get(LagRange.class).suspend();
                var result = mc().gameMode.useItem(player, hand);
                if (result.consumesAction()) {
                    //? if <26.3 {
                    player.swing(hand);
                    //?} else {
                    /*player.swing(hand, net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
                    *///?}
                }
                phase = Phase.RESTORE;
                schedule(restoreDelay);
            } else {
                if (restoreSlot.get() && managedSlot != originalSlot) selectSlot(originalSlot);

                originalSlot = -1;
                setEnabled(false);
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logic.LOGGER.warn("KeyPearl could not complete its throw", failure);
            setEnabled(false);
        }
    }

    private int findPearl() {
        if (player.getMainHandItem().is(Items.ENDER_PEARL)) return originalSlot;
        for (int slot = 0; slot < 9; slot++)
            if (player.getInventory().getItem(slot).is(Items.ENDER_PEARL)) return slot;
        return -1;
    }

    private void selectSlot(int slot) throws ReflectiveOperationException {
        LogicClient.MODULES.get(LagRange.class).suspend();
        player.getInventory().setSelectedSlot(slot);
        managedSlot = slot;
        AutoTool.syncSelectedSlot(mc().gameMode);
    }

    private void schedule(RangeSetting delay) {
        deadline = System.nanoTime() + (long) (delay.random() * 1_000_000);
        scheduledTick = player.tickCount;
    }

    @Override protected void onDisable() {
        if (restoreSlot.get() && originalSlot >= 0 && sameSession()
                && player.getInventory().getSelectedSlot() == managedSlot)
            player.getInventory().setSelectedSlot(originalSlot);
        player = null;
        level = null;
        connection = null;
        phase = null;
        originalSlot = pearlSlot = managedSlot = -1;
        requested = false;
    }
}

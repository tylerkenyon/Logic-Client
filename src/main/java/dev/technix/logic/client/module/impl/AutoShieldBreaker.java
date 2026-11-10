package dev.technix.logic.client.module.impl;

import dev.technix.logic.Logic;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.combat.ShieldBreakTiming;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.RangeSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.EntityHitResult;

public final class AutoShieldBreaker extends Module {
    private final RangeSetting switchDelay = registerSetting(new RangeSetting("Switch delay",
        "Milliseconds to keep aiming at a raised shield before selecting an axe.", 50, 50, 0, 1000, 10));
    private final RangeSetting attackDelay = registerSetting(new RangeSetting("Attack delay",
        "Milliseconds to wait after selecting the axe before attacking.", 100, 100, 0, 1000, 10));
    private final RangeSetting restoreDelay = registerSetting(new RangeSetting("Restore delay",
        "Milliseconds to wait after the attack before restoring your original slot.", 100, 100, 0, 1000, 10));
    private final RangeSetting cooldown = registerSetting(new RangeSetting("Cooldown",
        "Milliseconds between completed or cancelled attempts.", 500, 500, 0, 3000, 50));
    private final NumberSetting range = registerSetting(new NumberSetting("Range",
        "Maximum attack reach, limited by your normal interaction range.", 3, 1, 6, 0.1));
    private final BooleanSetting ignoreTeams = registerSetting(new BooleanSetting("Ignore teammates",
        "Skip players on your team.", true));
    private final ShieldBreakTiming timing = new ShieldBreakTiming();
    private LocalPlayer player;
    private ClientLevel level;
    private ClientPacketListener connection;
    private Player target;
    private int originalSlot = -1, axeSlot = -1, managedSlot = -1;
    private boolean attackRequested;

    public AutoShieldBreaker() {
        super("Auto Shield Breaker", "Switch to a hotbar axe, attack a raised shield, then switch back.", Category.COMBAT);
    }

    public boolean busy() { return enabled() && timing.busy(); }

    private boolean sameSession() {
        var mc = mc();
        return player != null && player == mc.player && level == mc.level && connection == mc.getConnection();
    }

     
    public void beforeKeybinds() {
        if (!enabled()) return;
        attackRequested = false;
        var mc = mc();
        if (!sameSession()) {
            clear();
            player = mc.player;
            level = mc.level;
            connection = mc.getConnection();
        }
        if (player == null || level == null || connection == null) return;
        long now = System.nanoTime();
        try {
            if (timing.busy() && player.getInventory().getSelectedSlot() != (managedSlot >= 0 ? managedSlot : originalSlot)) {
                 
                forgetAttempt();
                timing.cancel(now, nanos(cooldown));
                return;
            }
            if (!CombatTargets.canAct(mc) || player.isHandsBusy() || LogicClient.anchorBusy()
                    || LogicClient.MODULES.get(Blink.class).enabled()) {
                if (timing.busy()) cancel(now);
                return;
            }
            Player aimed = shieldTarget();
            if (timing.busy() && !timing.restoring() && (aimed != target
                    || !player.getInventory().getItem(axeSlot).is(ItemTags.AXES))) {
                cancel(now);
                return;
            }
            if (!timing.busy()) {
                if (aimed == null || findAxe() < 0 || !timing.start(now, nanos(switchDelay))) return;
                LogicClient.MODULES.get(AutoTool.class).yieldForInteraction();
                originalSlot = player.getInventory().getSelectedSlot();
                axeSlot = findAxe();
                target = aimed;
                if (axeSlot < 0) { cancel(now); return; }
            }
            switch (timing.poll(now, nanos(attackDelay), nanos(restoreDelay), nanos(cooldown))) {
                case SWITCH -> { managedSlot = axeSlot; selectSlot(axeSlot); }
                case ATTACK -> {
                    LogicClient.MODULES.get(LagRange.class).suspend();
                    attackRequested = true;
                }
                case RESTORE -> { restore(); forgetAttempt(); }
                default -> { }
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logic.LOGGER.warn("Auto Shield Breaker could not complete its interaction", failure);
            setEnabled(false);
        }
    }

    public boolean attackClick() {
        boolean attack = attackRequested && shieldTarget() == target && player.getMainHandItem().is(ItemTags.AXES);
        attackRequested = false;
        return attack;
    }

    @Subscribe(priority = 150)
    private void tick(ClientTickEvent event) {
        if (!sameSession()) { clear(); return; }
        if (timing.busy() && (!CombatTargets.canAct(mc()) || LogicClient.anchorBusy()
                || LogicClient.MODULES.get(Blink.class).enabled())) {
            try { cancel(System.nanoTime()); }
            catch (ReflectiveOperationException | RuntimeException failure) {
                Logic.LOGGER.warn("Auto Shield Breaker could not cancel its interaction", failure);
                setEnabled(false);
            }
        }
    }

    private Player shieldTarget() {
        var mc = mc();
        if (!(mc.hitResult instanceof EntityHitResult hit) || !(hit.getEntity() instanceof Player opponent)
                || !opponent.isBlocking() || !opponent.getUseItem().is(Items.SHIELD)
                || !CombatTargets.valid(mc, opponent, true, ignoreTeams.get(), true)) return null;
        double reach = Math.min(range.get(), player.entityInteractionRange());
        var eye = player.getEyePosition();
        var end = eye.add(player.getViewVector(1).scale(reach));
        return eye.distanceToSqr(hit.getLocation()) <= reach * reach
            && (opponent.getBoundingBox().contains(eye) || opponent.getBoundingBox().clip(eye, end).isPresent()) ? opponent : null;
    }

    private int findAxe() {
        int selected = player.getInventory().getSelectedSlot();
        if (player.getInventory().getItem(selected).is(ItemTags.AXES)) return selected;
        for (int slot = 0; slot < 9; slot++) if (player.getInventory().getItem(slot).is(ItemTags.AXES)) return slot;
        return -1;
    }

    private static long nanos(RangeSetting setting) { return (long)(setting.random() * 1_000_000); }

    private void selectSlot(int slot) throws ReflectiveOperationException {
        LogicClient.MODULES.get(LagRange.class).suspend();
        player.getInventory().setSelectedSlot(slot);
        AutoTool.syncSelectedSlot(mc().gameMode);
    }

    private void restore() throws ReflectiveOperationException {
        if (sameSession() && mc().gameMode != null && originalSlot >= 0 && managedSlot >= 0
                && player.getInventory().getSelectedSlot() == managedSlot) selectSlot(originalSlot);
    }

    private void cancel(long now) throws ReflectiveOperationException {
        restore();
        forgetAttempt();
        timing.cancel(now, nanos(cooldown));
    }

    private void forgetAttempt() { target = null; originalSlot = axeSlot = managedSlot = -1; attackRequested = false; }

    private void clear() {
        timing.reset();
        forgetAttempt();
        player = null;
        level = null;
        connection = null;
    }

    @Override protected void onDisable() {
        try { restore(); }
        catch (ReflectiveOperationException | RuntimeException failure) { Logic.LOGGER.warn("Could not restore Auto Shield Breaker's slot", failure); }
        finally { clear(); }
    }
}

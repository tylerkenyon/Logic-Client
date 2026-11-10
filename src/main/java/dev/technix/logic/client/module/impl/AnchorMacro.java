package dev.technix.logic.client.module.impl;

import dev.technix.logic.Logic;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.*;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

 
public final class AnchorMacro extends Module {
    private final BooleanSetting ownOnly = registerSetting(new BooleanSetting("Placed by you",
        "Charge and detonate only anchors whose placement you sent during this session.", false));
    private final RangeSetting interval = registerSetting(new RangeSetting("Action interval",
        "Random waiting time between interaction attempts, in milliseconds.", 30, 50, 0, 200, 1));
    private final BooleanSetting place = registerSetting(new BooleanSetting("Place anchors",
        "Place one anchor per press of the placement key at your crosshair.", true));
    private final KeybindSetting placeKey = registerSetting(new KeybindSetting("Placement key",
        "Keyboard key or mouse button that requests placement; release before placing again."));
    private final ModeSetting placeMode = registerSetting(switchMode("Placement inventory"));
    private final BooleanSetting placeReturn = registerSetting(returnSetting("Restore after placement"));
    private final BooleanSetting charge = registerSetting(new BooleanSetting("Charge anchors",
        "Use glowstone on an empty anchor under your crosshair while standing normally.", true));
    private final ModeSetting chargeMode = registerSetting(switchMode("Charging inventory"));
    private final BooleanSetting chargeReturn = registerSetting(returnSetting("Restore after charging"));
    private final BooleanSetting keyPlacedOnly = registerSetting(new BooleanSetting("Charge macro placements",
        "Charge only anchors placed using this module's placement key.", false));
    private final BooleanSetting detonate = registerSetting(new BooleanSetting("Detonate anchors",
        "Interact with a charged anchor under your crosshair using the configured hotbar slot.", true));
    private final ModeSetting detonateMode = registerSetting(switchMode("Detonation inventory"));
    private final NumberSetting detonateSlot = registerSetting(new NumberSetting("Detonation slot",
        "Hotbar slot used to interact with charged anchors, numbered 1 to 9.", 1, 1, 9, 1));
    private final BooleanSetting detonateReturn = registerSetting(returnSetting("Restore after detonation"));
    private final Map<BlockPos, Long> ownAnchors = new HashMap<>();
    private final Set<BlockPos> keyAnchors = new HashSet<>();
    private LocalPlayer player;
    private ClientLevel level;
    private ClientPacketListener connection;
    private boolean placedThisPress, acting;
    private long nextAction;
    private Pending pending;
    private int restoreSlot = -1, managedSlot = -1, actedTick = Integer.MIN_VALUE;

    public enum Stage { PLACE, CHARGE, DETONATE }
    private record Action(Stage stage, int slot, InteractionHand hand, String mode, boolean restore, BlockPos position) {}
    private record Pending(Action action, int previousCharge, long deadline) {}

    public AnchorMacro() {
        super("Anchor Macro", "Place, charge and activate anchors at your crosshair.", Category.COMBAT);
        placeKey.visibleWhen(place::get);
        placeMode.visibleWhen(place::get);
        placeReturn.visibleWhen(() -> place.get() && placeMode.get().equals("Select slot"));
        chargeMode.visibleWhen(charge::get);
        chargeReturn.visibleWhen(() -> charge.get() && chargeMode.get().equals("Select slot"));
        keyPlacedOnly.visibleWhen(charge::get);
        detonateMode.visibleWhen(detonate::get);
        detonateSlot.visibleWhen(detonate::get);
        detonateReturn.visibleWhen(() -> detonate.get() && detonateMode.get().equals("Select slot"));
    }

    private static ModeSetting switchMode(String name) {
        return new ModeSetting(name, "Held item keeps your slot; Select slot changes it; Temporary restores it immediately after use.",
            "Temporary", "Held item", "Select slot", "Temporary");
    }

    private static BooleanSetting returnSetting(String name) {
        return new BooleanSetting(name, "Return to the original slot at the next action interval unless you select another slot.", true);
    }

    private boolean sameSession() {
        var mc = mc();
        return player != null && player == mc.player && level == mc.level && connection == mc.getConnection();
    }

    public boolean busy() {
        return enabled() && sameSession() && (acting || restoreSlot >= 0 || actedTick == player.tickCount);
    }

    public boolean blocksVanillaUse() {
        if (!enabled() || !sameSession() || !CombatTargets.canAct(mc()) || !(mc().hitResult instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK || LogicClient.MODULES.get(Blink.class).enabled()) return false;
        return (pending != null && System.nanoTime() < pending.deadline()
            && pending.action().position().equals(hit.getBlockPos())) || action(hit) != null;
    }

    public static Stage nextStage(boolean anchor, int charges, boolean placement, boolean charging, boolean detonation) {
        if (!anchor) return placement ? Stage.PLACE : null;
        return charges == 0 ? charging ? Stage.CHARGE : null : detonation ? Stage.DETONATE : null;
    }

    public static boolean canDetonate(int charges, boolean mainGlowstone, boolean offhandGlowstone) {
        return charges > 0 && (charges >= 4 || !mainGlowstone && !offhandGlowstone);
    }

    private Action action(BlockHitResult hit) {
        BlockPos pos = hit.getBlockPos();
        if (player.getEyePosition().distanceToSqr(hit.getLocation()) > Math.pow(player.blockInteractionRange(), 2)
                || !level.getWorldBorder().isWithinBounds(pos)) return null;
        BlockState state = level.getBlockState(pos);
        boolean anchor = state.is(Blocks.RESPAWN_ANCHOR);
        if (anchor && (player.isShiftKeyDown() || ownOnly.get() && !ownAnchors.containsKey(pos))) return null;
        int charges = anchor ? state.getValue(RespawnAnchorBlock.CHARGE) : 0;
        Stage stage = nextStage(anchor, charges, place.get() && placeKey.isDown() && !placedThisPress,
            charge.get() && (!keyPlacedOnly.get() || keyAnchors.contains(pos)), detonate.get());
        if (stage == null) return null;
        String mode = stage == Stage.PLACE ? placeMode.get() : stage == Stage.CHARGE ? chargeMode.get() : detonateMode.get();
        boolean returnAfter = stage == Stage.PLACE ? placeReturn.get() : stage == Stage.CHARGE ? chargeReturn.get() : detonateReturn.get();
        int slot = player.getInventory().getSelectedSlot();
        InteractionHand hand = InteractionHand.MAIN_HAND;
        if (stage == Stage.DETONATE) {
            if (!mode.equals("Held item")) slot = detonateSlot.get().intValue() - 1;
            if (!canDetonate(charges, player.getInventory().getItem(slot).is(Items.GLOWSTONE),
                    player.getOffhandItem().is(Items.GLOWSTONE))) return null;
        } else {
            Item item = stage == Stage.PLACE ? Items.RESPAWN_ANCHOR : Items.GLOWSTONE;
            if (!player.getMainHandItem().is(item)) {
                if (player.getOffhandItem().is(item)) hand = InteractionHand.OFF_HAND;
                else {
                    if (mode.equals("Held item")) return null;
                    slot = findItem(item);
                    if (slot < 0) return null;
                }
            }
        }
        BlockPos result = stage == Stage.PLACE ? placementPosition(hit, state.canBeReplaced()) : pos.immutable();
        if (stage == Stage.PLACE && (!level.getWorldBorder().isWithinBounds(result)
                || level.isOutsideBuildHeight(result) || !level.getBlockState(result).canBeReplaced())) return null;
        return new Action(stage, slot, hand, mode, returnAfter, result);
    }

    @Subscribe(priority = 200)
    private void tick(ClientTickEvent event) {
        if (!sameSession()) {
            clear();
            player = mc().player;
            level = mc().level;
            connection = mc().getConnection();
        }
        if (player == null || level == null || connection == null) return;
        if (!placeKey.isDown()) placedThisPress = false;
        if (!CombatTargets.canAct(mc()) || LogicClient.shieldBreakerBusy() || LogicClient.MODULES.get(Blink.class).enabled()) {
            restore();
            acting = false;
            return;
        }
        long now = System.nanoTime();
        ownAnchors.entrySet().removeIf(entry -> now - entry.getValue() > 1_000_000_000L && !level.getBlockState(entry.getKey()).is(Blocks.RESPAWN_ANCHOR));
        keyAnchors.retainAll(ownAnchors.keySet());
        if (now < nextAction) return;
        nextAction = now + (long)(ThreadLocalRandom.current().nextDouble(interval.low(), Math.nextUp(interval.high())) * 1_000_000);
        restore();
        if (pending != null) {
            if (now < pending.deadline()) return;
             
            if (pending.action().stage() == Stage.PLACE) {
                ownAnchors.remove(pending.action().position());
                keyAnchors.remove(pending.action().position());
                placedThisPress = false;
            }
            pending = null;
        }
        if (!(mc().hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK
                || player.getEyePosition().distanceToSqr(hit.getLocation()) > Math.pow(player.blockInteractionRange(), 2)
                || !level.getWorldBorder().isWithinBounds(hit.getBlockPos())) return;
        Action action = action(hit);
        if (action != null) interact(hit, action);
    }

    private int findItem(Item item) {
        var inventory = player.getInventory();
        if (player.getMainHandItem().is(item)) return inventory.getSelectedSlot();
        for (int slot = 0; slot < 9; slot++) if (inventory.getItem(slot).is(item)) return slot;
        return -1;
    }

    private void interact(BlockHitResult hit, Action action) {
        LogicClient.MODULES.get(AutoTool.class).yieldForInteraction();
         
        action = action(hit);
        if (action == null) return;
         
        LogicClient.MODULES.get(LagRange.class).suspend();
        int original = player.getInventory().getSelectedSlot();
        acting = true;
        boolean failed = false;
        try {
            if (action.hand() == InteractionHand.MAIN_HAND && !action.mode().equals("Held item")) selectSlot(action.slot());
            var before = level.getBlockState(hit.getBlockPos());
            var result = mc().gameMode.useItemOn(player, action.hand(), hit);
            actedTick = player.tickCount;
            if (result.consumesAction()) {
                //? if <26.3 {
                player.swing(action.hand());
                //?} else {
                /*player.swing(action.hand(), net.minecraft.world.item.component.SwingAnimation.DEFAULT, true);
                *///?}
                int previousCharge = before.is(Blocks.RESPAWN_ANCHOR) ? before.getValue(RespawnAnchorBlock.CHARGE) : 0;
                pending = new Pending(action, previousCharge, System.nanoTime() + 1_000_000_000L);
                if (action.stage() == Stage.PLACE) {
                    placedThisPress = true;
                    ownAnchors.put(action.position(), System.nanoTime());
                    keyAnchors.add(action.position());
                }
            }
            if (action.hand() == InteractionHand.MAIN_HAND && action.mode().equals("Select slot")
                    && action.restore() && original != action.slot()) {
                restoreSlot = original;
                managedSlot = action.slot();
            }
        } catch (ReflectiveOperationException | RuntimeException failure) {
            Logic.LOGGER.warn("Anchor Macro could not complete its block interaction", failure);
            failed = true;
        } finally {
            if ((action.mode().equals("Temporary") || failed) && sameSession()
                    && player.getInventory().getSelectedSlot() != original) {
                try { selectSlot(original); }
                catch (ReflectiveOperationException failure) {
                    Logic.LOGGER.warn("Anchor Macro could not restore its temporary slot", failure);
                    failed = true;
                }
            }
            acting = false;
            if (failed) setEnabled(false);
        }
    }

    private void selectSlot(int slot) throws ReflectiveOperationException {
        player.getInventory().setSelectedSlot(slot);
        AutoTool.syncSelectedSlot(mc().gameMode);
    }

    private void restore() {
        if (restoreSlot >= 0 && sameSession() && player.getInventory().getSelectedSlot() == managedSlot && mc().gameMode != null) {
            try { selectSlot(restoreSlot); }
            catch (ReflectiveOperationException failure) { Logic.LOGGER.warn("Anchor Macro could not restore its hotbar slot", failure); }
        }
        restoreSlot = managedSlot = -1;
    }

    public static BlockPos placementPosition(BlockHitResult hit, boolean replaceable) {
        return replaceable ? hit.getBlockPos().immutable() : hit.getBlockPos().relative(hit.getDirection());
    }

    public void observeOutgoing(Packet<?> packet) {
        if (!enabled() || !sameSession() || !(packet instanceof ServerboundUseItemOnPacket use)
                //? if <26.3 {
                || !player.getItemInHand(use.getHand()).is(Items.RESPAWN_ANCHOR)) return;
                //?} else {
                /*|| !player.getItemInHand(use.hand()).is(Items.RESPAWN_ANCHOR)) return;
                *///?}
        //? if <26.3 {
        var state = level.getBlockState(use.getHitResult().getBlockPos());
        //?} else {
        /*var state = level.getBlockState(use.hitResult().getBlockPos());
        *///?}
        //? if <26.3 {
        if (!state.is(Blocks.RESPAWN_ANCHOR)) ownAnchors.put(placementPosition(use.getHitResult(), state.canBeReplaced()), System.nanoTime());
        //?} else {
        /*if (!state.is(Blocks.RESPAWN_ANCHOR)) ownAnchors.put(placementPosition(use.hitResult(), state.canBeReplaced()), System.nanoTime());
        *///?}
    }

    public void observeIncoming(Packet<?> packet) {
        if (!enabled()) return;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (var child : bundle.subPackets()) observeIncoming(child);
        } else if (packet instanceof ClientboundBlockUpdatePacket update) updateBlock(update.getPos(), update.getBlockState());
        else if (packet instanceof ClientboundSectionBlocksUpdatePacket update) update.runUpdates(this::updateBlock);
        else if (packet instanceof ClientboundRespawnPacket || packet instanceof ClientboundLoginPacket || packet.isTerminal()) clear();
    }

    private void updateBlock(BlockPos pos, BlockState state) {
        if (pending != null && pending.action().position().equals(pos)) {
            boolean anchor = state.is(Blocks.RESPAWN_ANCHOR);
            int charges = anchor ? state.getValue(RespawnAnchorBlock.CHARGE) : 0;
            if (confirmed(pending.action().stage(), pending.previousCharge(), anchor, charges)) pending = null;
        }
        if (!state.is(Blocks.RESPAWN_ANCHOR)) {
            ownAnchors.remove(pos);
            keyAnchors.remove(pos);
        }
    }

    public static boolean confirmed(Stage stage, int previousCharge, boolean anchor, int charges) {
        return switch (stage) {
            case PLACE -> anchor;
            case CHARGE -> anchor && charges > previousCharge;
            case DETONATE -> !anchor;
        };
    }

    private void clear() {
        ownAnchors.clear();
        keyAnchors.clear();
        player = null;
        level = null;
        connection = null;
        restoreSlot = managedSlot = -1;
        placedThisPress = acting = false;
        actedTick = Integer.MIN_VALUE;
        nextAction = 0;
        pending = null;
    }

    @Override protected void onDisable() { restore(); clear(); }
}

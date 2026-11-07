package dev.technix.logic.client.module.impl;

import dev.technix.logic.Logic;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.MultiSelectSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

import java.lang.reflect.Method;
import java.util.List;

public final class AutoTool extends Module {
    private static final String[] SLOTS = {"1", "2", "3", "4", "5", "6", "7", "8", "9"};
    private static final Method SYNC_SLOT = findSlotSync();
    private final ModeSetting preference = registerSetting(new ModeSetting("Preference", "Rank eligible tools before comparing mining speed.",
        "Fastest", "Fastest", "Fortune", "Silk Touch", "Durability"));
    private final MultiSelectSetting slots = registerSetting(new MultiSelectSetting("Hotbar slots", "Slots Auto Tool can switch to.", List.of(SLOTS), SLOTS));
    private final MultiSelectSetting checks = registerSetting(new MultiSelectSetting("Activation checks", "Each selected check must pass.",
        List.of("Use key released"), "Sneaking", "On ground", "Holding a tool", "Use key released"));
    private final BooleanSetting requireDrops = registerSetting(new BooleanSetting("Correct tool for drops", "Skip unsuitable tools for blocks that require one.", true));
    private final NumberSetting reserve = registerSetting(new NumberSetting("Durability reserve", "Skip candidates with this many uses left or fewer.", 5, 0, 100, 1));
    private final NumberSetting switchDelay = registerSetting(new NumberSetting("Switch delay", "Ticks spent targeting a block before switching.", 0, 0, 20, 1));
    private final BooleanSetting switchBack = registerSetting(new BooleanSetting("Restore slot", "Return to the original slot after mining.", true));
    private final NumberSetting restoreDelay = registerSetting(new NumberSetting("Restore delay", "Ticks before returning to the original slot.", 2, 0, 20, 1));

    private LocalPlayer owner;
    private ClientLevel level;
    private BlockPos target;
    private long tick, targetSince, idleSince = -1, lastClick = -1;
    private int originalSlot = -1, managedSlot = -1;
    private boolean manualOverride;

    public AutoTool() { super("Auto Tool", "Select the best eligible hotbar tool when mining.", Category.PLAYER); }

    public void beforeAttack(boolean click) {
        if (enabled()) update(click, true);
    }

    @Subscribe
    private void tick(ClientTickEvent event) {
        update(false, false);
        tick++;
    }

    private void update(boolean click, boolean canSwitch) {
        Minecraft mc = mc();
        if (owner != mc.player || level != mc.level) {
            clear();
            owner = mc.player;
            level = mc.level;
        }
        if (owner == null || level == null) return;
        if (LogicClient.anchorBusy() || LogicClient.shieldBreakerBusy()) return;

        Inventory inventory = owner.getInventory();
        if (managedSlot >= 0 && inventory.getSelectedSlot() != managedSlot) {
            originalSlot = managedSlot = -1;
            manualOverride = true;
        }

        if (click) lastClick = tick;
        boolean attacking = lastClick == tick || mc.options.keyAttack.isDown();
        if (!attacking) manualOverride = false;

        BlockPos pos = attacking && !manualOverride ? miningTarget(mc) : null;
        if (pos == null) {
            target = null;
            if (idleSince < 0) idleSince = tick;
            if (!CombatTargets.canAct(mc) || mc.hitResult instanceof EntityHitResult
                    || tick - idleSince >= restoreDelay.get()) restore(inventory);
            return;
        }

        idleSince = -1;
        if (!pos.equals(target)) {
            target = pos;
            targetSince = tick;
        }
        if (!canSwitch || SYNC_SLOT == null || tick - targetSince < switchDelay.get()) return;

        int current = inventory.getSelectedSlot();
        int best = bestSlot(inventory, level.getBlockState(pos), current);
        if (best == current) return;

        inventory.setSelectedSlot(best);
        try {
             
            syncSelectedSlot(mc.gameMode);
        } catch (ReflectiveOperationException failure) {
            inventory.setSelectedSlot(current);
            Logic.LOGGER.warn("Could not synchronize the Auto Tool slot", failure);
            setEnabled(false);
            return;
        }
        if (originalSlot < 0) originalSlot = current;
        managedSlot = best;
        if (best == originalSlot) originalSlot = managedSlot = -1;
    }

    private BlockPos miningTarget(Minecraft mc) {
        if (!CombatTargets.canAct(mc) || mc.gameMode.getPlayerMode() != GameType.SURVIVAL
                || checks.selected("Sneaking") && !owner.isShiftKeyDown()
                || checks.selected("On ground") && !owner.onGround()
                || checks.selected("Holding a tool") && !owner.getMainHandItem().has(DataComponents.TOOL)
                || checks.selected("Use key released") && mc.options.keyUse.isDown()
                || !(mc.hitResult instanceof BlockHitResult hit) || hit.getType() != HitResult.Type.BLOCK) return null;
        BlockPos pos = hit.getBlockPos();
        return level.getWorldBorder().isWithinBounds(pos) && level.getBlockState(pos).getDestroySpeed(level, pos) > 0 ? pos : null;
    }

    private int bestSlot(Inventory inventory, BlockState state, int current) {
        var enchantments = level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT);
        var efficiency = enchantments.getOrThrow(Enchantments.EFFICIENCY);
        String mode = preference.get();
        var preferred = switch (mode) {
            case "Fortune" -> enchantments.getOrThrow(Enchantments.FORTUNE);
            case "Silk Touch" -> enchantments.getOrThrow(Enchantments.SILK_TOUCH);
            default -> null;
        };
        int best = current;
        int bestPriority = -1;
        double bestSpeed = 0;
        for (int i = 0; i < SLOTS.length; i++) {
             
            if (i != current && !slots.selected(SLOTS[i])) continue;
            ItemStack stack = inventory.getItem(i);
            boolean wrongTool = state.requiresCorrectToolForDrops() && !stack.isCorrectToolForDrops(state);
            if (stack.isEmpty() || wrongTool && requireDrops.get()) continue;
            int durability = stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : Integer.MAX_VALUE;
            if (durability <= reserve.get()) continue;
            double speed = stack.getDestroySpeed(state);
            if (speed <= 1) continue;
            int bonus = EnchantmentHelper.getItemEnchantmentLevel(efficiency, stack);
            if (bonus > 0) speed += (double)bonus * bonus + 1;
             
            if (wrongTool) speed *= 0.3;
            int priority = preferred != null ? EnchantmentHelper.getItemEnchantmentLevel(preferred, stack)
                : mode.equals("Durability") ? durability : 0;
            if (priority > bestPriority || priority == bestPriority && (speed > bestSpeed || speed == bestSpeed && i == current)) {
                best = i;
                bestPriority = priority;
                bestSpeed = speed;
            }
        }
        return best;
    }

    static void syncSelectedSlot(MultiPlayerGameMode gameMode) throws ReflectiveOperationException {
        if (SYNC_SLOT == null) throw new NoSuchMethodException("Minecraft hotbar synchronization");
        SYNC_SLOT.invoke(gameMode);
    }

    public void yieldForInteraction() {
        if (!enabled() || owner == null || owner != mc().player || level != mc().level) return;
        restore(owner.getInventory());
        target = null;
        manualOverride = true;
    }

    private static Method findSlotSync() {
        try {
            Method sync = MultiPlayerGameMode.class.getDeclaredMethod("ensureHasSentCarriedItem");
            sync.setAccessible(true);
            return sync;
        } catch (ReflectiveOperationException failure) {
            Logic.LOGGER.warn("Auto Tool could not find Minecraft's hotbar synchronization method", failure);
            return null;
        }
    }

    private void restore(Inventory inventory) {
        if (originalSlot >= 0 && switchBack.get() && inventory.getSelectedSlot() == managedSlot) {
            inventory.setSelectedSlot(originalSlot);
        }
        originalSlot = managedSlot = -1;
    }

    private void clear() {
        owner = null;
        level = null;
        target = null;
        originalSlot = managedSlot = -1;
        lastClick = idleSince = -1;
        manualOverride = false;
    }

    @Override
    protected void onDisable() {
        var mc = mc();
        if (owner != null && owner == mc.player && level == mc.level) restore(owner.getInventory());
        clear();
    }
}

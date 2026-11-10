package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.*;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.ItemAttributeModifiers;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.item.enchantment.Enchantments;

import java.util.List;

public final class AutoArmor extends Module {
    private static final EquipmentSlot[] SLOTS = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};
    private static final String[] NAMES = {"Helmet", "Chestplate", "Leggings", "Boots"};
    private final MultiSelectSetting pieces = registerSetting(new MultiSelectSetting("Armor slots", "Equipment slots to manage.", List.of(NAMES), NAMES));
    private final BooleanSetting inventoryOnly = registerSetting(new BooleanSetting("Inventory only", "Act only while your inventory screen is open.", true));
    private final BooleanSetting upgrade = registerSetting(new BooleanSetting("Replace armor", "Move weaker equipped armor into inventory before equipping an upgrade. Requires a free slot.", true));
    private final BooleanSetting keepElytra = registerSetting(new BooleanSetting("Keep elytra", "Never replace an equipped elytra.", true));
    private final ModeSetting preference = registerSetting(new ModeSetting("Preference", "Protection compares armor, toughness and Protection enchantments; Durability prioritizes remaining uses.", "Protection", "Protection", "Durability"));
    private final BooleanSetting enchantments = registerSetting(new BooleanSetting("Consider Protection", "Include the general Protection enchantment in armor ratings.", true));
    private final BooleanSetting avoidBinding = registerSetting(new BooleanSetting("Avoid Binding Curse", "Do not equip armor with Curse of Binding.", true));
    private final NumberSetting minimumDurability = registerSetting(new NumberSetting("Minimum durability", "Minimum remaining durability percentage for candidate armor.", 5, 0, 100, 1));
    private final RangeSetting delay = registerSetting(new RangeSetting("Action delay", "Client ticks between inventory clicks.", 2, 2, 0, 20, 1));
    private Object owner;
    private int remaining;

    public AutoArmor() {
        super("Auto Armor", "Equip better armor from your inventory without dropping old equipment.", Category.PLAYER);
        enchantments.visibleWhen(() -> preference.get().equals("Protection"));
    }

    @Subscribe
    private void tick(ClientTickEvent event) {
        var mc = mc();
        if (owner != mc.player) { owner = mc.player; remaining = delay.randomInt(); }
        if (mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.isAlive() || mc.player.isSpectator()
                || mc.isPaused() || LogicClient.MODULES.get(ClickGui.class).open()
                || LogicClient.anchorBusy() || LogicClient.shieldBreakerBusy()
                || mc.player.containerMenu != mc.player.inventoryMenu
                || inventoryOnly.get() && !(mc.gui.screen() instanceof InventoryScreen)
                || mc.gui.screen() != null && !(mc.gui.screen() instanceof InventoryScreen)
                || mc.player.isUsingItem() || !mc.player.inventoryMenu.getCarried().isEmpty()) {
            remaining = delay.randomInt();
            return;
        }
        if (remaining > 0) { remaining--; return; }
        var menu = mc.player.inventoryMenu;
        for (int piece = 0; piece < SLOTS.length; piece++) {
            if (!pieces.selected(NAMES[piece])) continue;
            var armorSlot = menu.getSlot(5 + piece);
            ItemStack worn = armorSlot.getItem();
            if (!worn.isEmpty() && (!upgrade.get() || !armorSlot.mayPickup(mc.player)
                    || keepElytra.get() && worn.is(Items.ELYTRA))) continue;
            int best = -1;
            ItemStack bestStack = worn;
            for (int index = 9; index < 45; index++) {
                ItemStack candidate = menu.getSlot(index).getItem();
                if (!eligible(candidate, SLOTS[piece], minimumDurability.get()) || !armorSlot.mayPlace(candidate)) continue;
                if (avoidBinding.get() && EnchantmentHelper.getItemEnchantmentLevel(
                        mc.level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.BINDING_CURSE), candidate) > 0) continue;
                if (better(candidate, bestStack, SLOTS[piece])) { best = index; bestStack = candidate; }
            }
            if (best < 0) continue;
             
            if (!worn.isEmpty()) {
                boolean free = false;
                for (int index = 9; index < 45; index++) if (!menu.getSlot(index).hasItem()) { free = true; break; }
                if (!free) continue;
            }
            mc.gameMode.handleContainerInput(menu.containerId, worn.isEmpty() ? best : 5 + piece, 0, ContainerInput.QUICK_MOVE, mc.player);
            remaining = delay.randomInt();
            return;
        }
    }

    private boolean better(ItemStack candidate, ItemStack current, EquipmentSlot slot) {
        if (current.isEmpty()) return true;
        if (preference.get().equals("Durability")) {
            int comparison = Integer.compare(usesLeft(candidate), usesLeft(current));
            if (comparison != 0) return comparison > 0;
        }
        double a = armorScore(candidate, slot), b = armorScore(current, slot);
        if (enchantments.get() && preference.get().equals("Protection")) {
            var protection = mc().level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.PROTECTION);
            a += EnchantmentHelper.getItemEnchantmentLevel(protection, candidate);
            b += EnchantmentHelper.getItemEnchantmentLevel(protection, current);
        }
        return a > b || a == b && usesLeft(candidate) > usesLeft(current);
    }

    public static boolean eligible(ItemStack stack, EquipmentSlot slot, double minimumPercent) {
        var equippable = stack.get(DataComponents.EQUIPPABLE);
        return !stack.isEmpty() && equippable != null && equippable.slot() == slot
            && armorScore(stack, slot) > 0
            && (!stack.isDamageableItem() || 100.0 * usesLeft(stack) / stack.getMaxDamage() >= minimumPercent);
    }

    public static double armorScore(ItemStack stack, EquipmentSlot slot) {
        var modifiers = stack.getOrDefault(DataComponents.ATTRIBUTE_MODIFIERS, ItemAttributeModifiers.EMPTY);
        return modifiers.compute(Attributes.ARMOR, 0, slot) + modifiers.compute(Attributes.ARMOR_TOUGHNESS, 0, slot) * 0.25;
    }

    private static int usesLeft(ItemStack stack) { return stack.isDamageableItem() ? stack.getMaxDamage() - stack.getDamageValue() : 0; }
    @Override protected void onDisable() { owner = null; remaining = 0; }
}

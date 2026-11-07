package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.*;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public final class ChestStealer extends Module {
    private final MultiSelectSetting containers = registerSetting(new MultiSelectSetting("Containers", "Storage menus to loot. Chests also includes barrels and ender chests.", List.of("Chests"), "Chests", "Shulker boxes", "Hoppers", "Dispensers"));
    private final NumberSetting openDelay = registerSetting(new NumberSetting("Open delay", "Milliseconds to wait after opening a container.", 200, 0, 2000, 10));
    private final RangeSetting delay = registerSetting(new RangeSetting("Transfer delay", "Random milliseconds between stack transfers.", 80, 120, 0, 1000, 10));
    private final ModeSetting order = registerSetting(new ModeSetting("Slot order", "Order in which eligible stacks are transferred.", "First to last", "First to last", "Last to first", "Random"));
    private final ModeSetting filter = registerSetting(new ModeSetting("Item filter", "Choose whether the item list is included or excluded.", "All", "All", "Whitelist", "Blacklist"));
    private final StringSetting items = registerSetting(new StringSetting("Item IDs", "Comma-separated exact IDs, e.g. diamond, minecraft:iron_ingot. Maximum 64 characters.", "diamond,iron_ingot"));
    private final BooleanSetting autoClose = registerSetting(new BooleanSetting("Auto close", "Close when no eligible items remain.", true));
    private final BooleanSetting closeWhenFull = registerSetting(new BooleanSetting("Close when full", "Also close when eligible items remain but none fit in inventory.", false));
    private final NumberSetting closeDelay = registerSetting(new NumberSetting("Close delay", "Milliseconds to wait before closing a finished container.", 200, 0, 2000, 10));
    private AbstractContainerMenu activeMenu;
    private Object owner;
    private long nextAction, finishedSince = -1;

    public ChestStealer() {
        super("Chest Stealer", "Transfer filtered items from opened storage into your inventory.", Category.PLAYER);
        items.visibleWhen(() -> !filter.get().equals("All"));
        closeWhenFull.visibleWhen(autoClose::get);
        closeDelay.visibleWhen(autoClose::get);
    }

    @Subscribe
    private void tick(ClientTickEvent event) {
        var mc = mc();
        if (mc.player == null || mc.level == null || mc.gameMode == null || !mc.player.isAlive() || mc.player.isSpectator()
                || mc.isPaused() || !(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)
                || screen.getMenu() != mc.player.containerMenu || containerSlots(screen.getMenu()) == 0) { reset(); return; }
        var menu = screen.getMenu();
        long now = System.nanoTime() / 1_000_000;
        if (activeMenu != menu || owner != mc.player) {
            activeMenu = menu;
            owner = mc.player;
            nextAction = now + openDelay.get().longValue();
            finishedSince = -1;
        }
        if (!menu.getCarried().isEmpty() || LogicClient.MODULES.get(ClickGui.class).open()) {
            nextAction = now + openDelay.get().longValue();
            finishedSince = -1;
            return;
        }
        if (now < nextAction) return;
        int count = containerSlots(menu);
        List<Integer> candidates = new ArrayList<>();
        boolean eligibleRemaining = false;
        for (int index = 0; index < count; index++) {
            var slot = menu.getSlot(index);
            var stack = slot.getItem();
            if (stack.isEmpty() || !slot.mayPickup(mc.player)
                    || !matchesFilter(BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(), filter.get(), items.get())) continue;
            eligibleRemaining = true;
            for (int destination = count; destination < menu.slots.size(); destination++) {
                var target = menu.getSlot(destination);
                if (target.mayPlace(stack) && canFit(stack, target.getItem(), target.getMaxStackSize(stack))) {
                    candidates.add(index);
                    break;
                }
            }
        }
        if (candidates.isEmpty()) {
            if (!autoClose.get() || eligibleRemaining && !closeWhenFull.get()) { finishedSince = -1; return; }
            if (finishedSince < 0) finishedSince = now;
            if (now - finishedSince >= closeDelay.get()) { screen.onClose(); reset(); }
            return;
        }
        finishedSince = -1;
        int selected = switch (order.get()) {
            case "Last to first" -> candidates.getLast();
            case "Random" -> candidates.get(ThreadLocalRandom.current().nextInt(candidates.size()));
            default -> candidates.getFirst();
        };
        mc.gameMode.handleContainerInput(menu.containerId, selected, 0, ContainerInput.QUICK_MOVE, mc.player);
        nextAction = now + Math.round(ThreadLocalRandom.current().nextDouble(delay.low(), delay.high() + 1));
    }

    private int containerSlots(AbstractContainerMenu menu) {
        if (menu instanceof ChestMenu chest && containers.selected("Chests")) return chest.getRowCount() * 9;
        if (menu instanceof ShulkerBoxMenu && containers.selected("Shulker boxes")) return 27;
        if (menu instanceof HopperMenu && containers.selected("Hoppers")) return 5;
        if (menu instanceof DispenserMenu && containers.selected("Dispensers")) return 9;
        return 0;
    }

    public static boolean matchesFilter(String id, String mode, String list) {
        if (mode.equals("All")) return true;
        boolean listed = false;
        for (String token : list.toLowerCase(Locale.ROOT).split(",")) {
            String entry = token.trim();
            if (entry.isEmpty()) continue;
            if (!entry.contains(":")) entry = "minecraft:" + entry;
            if (entry.equals(id)) { listed = true; break; }
        }
        return mode.equals("Whitelist") ? listed : !listed;
    }

    public static boolean canFit(ItemStack incoming, ItemStack existing, int slotLimit) {
        return slotLimit > 0 && (existing.isEmpty() || ItemStack.isSameItemSameComponents(incoming, existing)
            && existing.getCount() < Math.min(slotLimit, existing.getMaxStackSize()));
    }

    private void reset() { activeMenu = null; owner = null; nextAction = 0; finishedSince = -1; }
    @Override protected void onDisable() { reset(); }
}

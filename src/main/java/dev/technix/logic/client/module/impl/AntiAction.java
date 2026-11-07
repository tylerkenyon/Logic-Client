package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;

public final class AntiAction extends Module {
    private final BooleanSetting doubleGlowstone = registerSetting(new BooleanSetting("Double glowstone",
        "Prevent adding glowstone to an already charged anchor while not sneaking.", true));
    private final BooleanSetting openEChest = registerSetting(new BooleanSetting("Open ender chest",
        "Prevent opening ender chests with combat items; sneak to open them.", true));
    private final BooleanSetting placeGlowstone = registerSetting(new BooleanSetting("Place glowstone",
        "Prevent placing glowstone instead of charging an anchor.", true));
    private final BooleanSetting groundFirework = registerSetting(new BooleanSetting("Ground firework",
        "Prevent using rockets without gliding, except with an offhand crossbow.", true));
    private final BooleanSetting swordShield = registerSetting(new BooleanSetting("Sword shield",
        "Prevent sword attacks against players using a shield.", true));

    public AntiAction() { super("AntiAction", "Prevent accidental combat interactions.", Category.COMBAT); }

    public boolean blocksUse() {
        var mc = mc();
        if (!enabled() || mc.player == null || mc.level == null) return false;
        var player = mc.player;
        if (blocksFirework(holding(Items.FIREWORK_ROCKET), player.isFallFlying(), player.getOffhandItem().is(Items.CROSSBOW))) return true;
        return mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK
            && blocksBlockUse(mc.level.getBlockState(hit.getBlockPos()), player.isShiftKeyDown(), holding(Items.GLOWSTONE),
                isCombatItem(player.getMainHandItem()) || isCombatItem(player.getOffhandItem()));
    }

    private boolean holding(Item item) {
        return mc().player.getMainHandItem().is(item) || mc().player.getOffhandItem().is(item);
    }

    public boolean blocksFirework(boolean holdingRocket, boolean gliding, boolean offhandCrossbow) {
        return groundFirework.get() && holdingRocket && !gliding && !offhandCrossbow;
    }

    public boolean blocksBlockUse(BlockState state, boolean sneaking, boolean glowstone, boolean combatItem) {
        boolean anchor = state.is(Blocks.RESPAWN_ANCHOR);
        if (anchor && doubleGlowstone.get() && !sneaking && glowstone && state.getValue(RespawnAnchorBlock.CHARGE) > 0) return true;
        if (state.is(Blocks.ENDER_CHEST) && openEChest.get() && !sneaking && combatItem) return true;
        return placeGlowstone.get() && glowstone && (!anchor || sneaking) && !state.isAir();
    }

    public static boolean isCombatItem(ItemStack stack) {
        return !stack.isEmpty() && (stack.is(ItemTags.SWORDS) || stack.is(ItemTags.AXES) || stack.is(Items.MACE)
            || stack.is(Items.END_CRYSTAL) || stack.is(Items.OBSIDIAN) || stack.is(Items.CRYING_OBSIDIAN)
            || stack.is(Items.TOTEM_OF_UNDYING) || stack.is(Items.EXPERIENCE_BOTTLE) || stack.is(Items.GLOWSTONE));
    }

    public boolean blocksAttack() {
        var mc = mc();
        return enabled() && mc.player != null && mc.level != null && swordShield.get()
            && mc.player.getMainHandItem().is(ItemTags.SWORDS)
            && mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof Player target
            && target.isUsingItem() && target.getUseItem().is(Items.SHIELD);
    }
}

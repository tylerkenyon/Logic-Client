package dev.technix.logic.client.combat;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.impl.ClickGui;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;

public final class CombatTargets {
    private CombatTargets() { }

    public static boolean canAct(Minecraft mc) {
        return mc.player != null && mc.level != null && mc.gameMode != null
            && mc.player.isAlive() && !mc.player.isSpectator() && !mc.player.isUsingItem()
            && mc.gui.screen() == null && !mc.isPaused() && mc.isWindowActive()
            && !LogicClient.MODULES.get(ClickGui.class).open();
    }

    public static boolean valid(Minecraft mc, LivingEntity target,
                                boolean playersOnly, boolean ignoreTeams, boolean visibleOnly) {
        return target != mc.player && !LogicClient.isBot(target) && target.isAlive() && !target.isSpectator()
            && target.isAttackable() && target.isPickable() && !target.isInvisibleTo(mc.player)
            && (!playersOnly || target instanceof Player)
            && (!ignoreTeams || !mc.player.isAlliedTo(target))
            && (!visibleOnly || mc.player.hasLineOfSight(target));
    }
}

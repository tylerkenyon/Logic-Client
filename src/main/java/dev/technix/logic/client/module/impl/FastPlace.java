package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.world.item.BlockItem;

public final class FastPlace extends Module {
    private final NumberSetting cps = registerSetting(new NumberSetting(
        "Max CPS", "Placement attempts per second", 20, 5, 20, 1));
    private final NumberSetting activation = registerSetting(new NumberSetting(
        "Activation delay", "Ticks after the initial press", 0, 0, 20, 1));
    private final UseRateGate gate = new UseRateGate();
    private net.minecraft.client.player.LocalPlayer owner;

    public FastPlace() { super("FastPlace", "Accelerates held block placement.", Category.PLAYER); }

    public boolean applicable() {
        var mc = mc();
        if (!enabled() || LogicClient.anchorControlsUse() || !CombatTargets.canAct(mc) || !mc.options.keyUse.isDown()) return false;
        var main = mc.player.getMainHandItem();
        return main.getItem() instanceof BlockItem
            || mc.player.getOffhandItem().getItem() instanceof BlockItem;
    }

    public boolean allowUse() {
        if (!applicable()) { gate.reset(); return true; }
        if (owner != mc().player) { gate.reset(); owner = mc().player; }
         
        return gate.tryUseTick(mc().player.tickCount, cps.get(), activation.get().intValue());
    }

    public String rateLabel() { return cps.valueLabel() + " cps"; }

    @Subscribe
    private void onTick(ClientTickEvent event) { if (!applicable()) gate.reset(); }

    @Override
    protected void onDisable() { gate.reset(); owner = null; }
}

package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import net.minecraft.client.KeyMapping;

public final class ClickGuiConfig extends Module {
    public final BooleanSetting draggable = registerSetting(new BooleanSetting("Draggable panels", "Drag category headers; right-click to collapse.", true));
    public final NumberSetting scale = registerSetting(new NumberSetting("Scale", "", 1, 0.75, 1.75, 0.05));
    public final BooleanSetting moveInGui = registerSetting(new BooleanSetting("Move in ClickGUI", "Allow movement while the menu is open.", false));
    public final NumberSetting backgroundOpacity = registerSetting(new NumberSetting("Background opacity", "Screen dimming.", 35, 0, 100, 1));
    public final NumberSetting panelOpacity = registerSetting(new NumberSetting("Panel opacity", "", 100, 20, 100, 1));
    private boolean controllingMovement;
    private boolean controllingSprint;

    public ClickGuiConfig() { super("ClickGUI settings", "", Category.CONFIGURATION); }
    @Override public boolean configurationOnly() { return true; }

    public boolean allowsMovement(boolean open) {
        var mc = mc();
        return open && moveInGui.get() && mc.player != null && mc.options != null
            && mc.gui.screen() == null && mc.isWindowActive();
    }

    public void updateMovement(boolean open) {
        var mc = mc();
        boolean active = allowsMovement(open);
        if (!active && !controllingMovement) return;
        KeyMapping[] keys = {mc.options.keyUp, mc.options.keyDown, mc.options.keyLeft, mc.options.keyRight, mc.options.keyJump, mc.options.keyShift};
        for (KeyMapping binding : keys) {
            MovementKeys.setState(binding, active && MovementKeys.physicalDown(binding),
                binding == mc.options.keyShift && mc.options.toggleCrouch().get());
        }
        boolean manualSprint = active && !LogicClient.MODULES.get(Sprint.class).enabled();
        if (manualSprint || controllingSprint) {
            boolean restoreHeldKey = !open && mc.player != null && mc.isWindowActive() && mc.gui.screen() == null;
            MovementKeys.setState(mc.options.keySprint,
                (manualSprint || restoreHeldKey) && MovementKeys.physicalDown(mc.options.keySprint),
                mc.options.toggleSprint().get());
        }
        controllingSprint = manualSprint;
        controllingMovement = active;
    }
}

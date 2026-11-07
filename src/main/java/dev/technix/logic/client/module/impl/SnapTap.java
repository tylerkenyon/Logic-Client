package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.input.OpposingKeys;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.KeyPressEvent;
import net.minecraft.client.KeyMapping;

public final class SnapTap extends Module {
    public final BooleanSetting strafe = registerSetting(new BooleanSetting("A/D priority", "Last pressed strafe direction wins, using your current key bindings.", true));
    public final BooleanSetting forwardBack = registerSetting(new BooleanSetting("W/S priority", "Apply the same priority to forward and backward movement.", false));
    private final OpposingKeys horizontal = new OpposingKeys(), vertical = new OpposingKeys();
    private boolean controlsStrafe, controlsForward;

    public SnapTap() { super("SnapTap", "Prioritize the last opposing movement key pressed.", Category.MOVEMENT); }

    @Subscribe private void onKey(KeyPressEvent event) {
         
        update();
    }

    private boolean canMove() {
        var mc = mc();
        return mc.player != null && mc.options != null && mc.isWindowActive() && mc.gui.screen() == null
            && (!LogicClient.MODULES.get(ClickGui.class).open() || LogicClient.MODULES.get(ClickGuiConfig.class).moveInGui.get());
    }

     
    public void update() {
        if (!enabled() || !canMove()) { releaseControl(false); return; }
        var options = mc().options;
        if (strafe.get()) apply(horizontal, options.keyLeft, options.keyRight);
        else { if (controlsStrafe) restore(options.keyLeft, options.keyRight, true); horizontal.reset(); }
        if (forwardBack.get()) apply(vertical, options.keyUp, options.keyDown);
        else { if (controlsForward) restore(options.keyUp, options.keyDown, true); vertical.reset(); }
        controlsStrafe = strafe.get(); controlsForward = forwardBack.get();
    }

    private static void apply(OpposingKeys state, KeyMapping first, KeyMapping second) {
        state.update(MovementKeys.physicalDown(first), MovementKeys.physicalDown(second));
        first.setDown(state.firstActive()); second.setDown(state.secondActive());
    }
    private static void restore(KeyMapping first, KeyMapping second, boolean physical) {
        first.setDown(physical && MovementKeys.physicalDown(first));
        second.setDown(physical && MovementKeys.physicalDown(second));
    }
    private void releaseControl(boolean physical) {
        if (mc().options != null) {
            if (controlsStrafe) restore(mc().options.keyLeft, mc().options.keyRight, physical);
            if (controlsForward) restore(mc().options.keyUp, mc().options.keyDown, physical);
        }
        controlsStrafe = controlsForward = false; horizontal.reset(); vertical.reset();
    }
    @Override protected void onDisable() { releaseControl(canMove()); }
}

package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class Sprint extends Module {
    private final BooleanSetting onlyMoving = registerSetting(new BooleanSetting(
        "Only while moving", "Require a movement key to be held", true));
    public Sprint() {
        super("Sprint", "Automatically sprints for you.", Category.MOVEMENT, GLFW.GLFW_KEY_UNKNOWN, true);
    }

    @Subscribe
    private void onTick(ClientTickEvent event) {
        Minecraft mc = mc();
        if (mc.player == null || mc.options == null) {
            return;
        }
        boolean moving = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
        boolean menuOpen = LogicClient.MODULES.get(ClickGui.class).open();
        boolean guiMovement = LogicClient.MODULES.get(ClickGuiConfig.class).allowsMovement(menuOpen);
        boolean active = mc.gui.screen() == null && mc.isWindowActive() && (!menuOpen || guiMovement);
        boolean manual = guiMovement && MovementKeys.physicalDown(mc.options.keySprint);
        MovementKeys.setState(mc.options.keySprint,
            active && (manual || !onlyMoving.get() || moving), mc.options.toggleSprint().get());
    }

    @Override
    protected void onDisable() {
        var mc = mc();
        if (mc != null && mc.options != null) {
            MovementKeys.setState(mc.options.keySprint, false, mc.options.toggleSprint().get());
        }
    }
}

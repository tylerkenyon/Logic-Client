package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

public class Sprint extends Module {
    private final BooleanSetting onlyMoving = registerSetting(new BooleanSetting(
        "Only while moving", "Require a movement key to be held", true));
    public Sprint() {
        super("Sprint", "Automatically sprints for you.", Category.MOVEMENT, 0, true);
    }

    @Subscribe
    private void onTick(ClientTickEvent event) {
        Minecraft mc = mc();
        if (mc.player == null || mc.options == null) {

            return;
        }
        boolean moving = mc.options.keyUp.isDown() || mc.options.keyDown.isDown()
            || mc.options.keyLeft.isDown() || mc.options.keyRight.isDown();
        boolean active = mc.gui.screen() == null && mc.isWindowActive()
            && !LogicClient.MODULES.get(ClickGui.class).open();
        dev.technix.logic.client.input.MovementKeys.setState(mc.options.keySprint,
            active && (!onlyMoving.get() || moving), mc.options.toggleSprint().get());
    }

    @Override
    protected void onDisable() {
        if (mc() != null) dev.technix.logic.client.input.MovementKeys.setState(mc().options.keySprint,
            false, mc().options.toggleSprint().get());
    }
}

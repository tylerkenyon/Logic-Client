package dev.technix.logic.client.setting;

import dev.technix.mica.internal.GameInput;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

 
public final class KeybindSetting extends Setting<Integer> {
    public KeybindSetting(String name, String description) {
        super(name, description, GLFW.GLFW_KEY_UNKNOWN);
    }

    @Override public void set(Integer value) {
        if (value == null || value != GLFW.GLFW_KEY_UNKNOWN
                && !(value >= 0 && value <= GLFW.GLFW_MOUSE_BUTTON_LAST)
                && !(value >= GLFW.GLFW_KEY_SPACE && value <= GLFW.GLFW_KEY_LAST))
            throw new IllegalArgumentException("Invalid action binding");
        super.set(value);
    }

    public boolean isDown() {
        if (get() == GLFW.GLFW_KEY_UNKNOWN) return false;
        long window = Minecraft.getInstance().getWindow().handle();
        return get() <= GLFW.GLFW_MOUSE_BUTTON_LAST
            ? GameInput.boundMouseDown(window, get())
            : GameInput.keyDown(window, get());
    }
}

package dev.technix.logic.client.input;

import dev.technix.mica.internal.GameInput;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;

public final class MovementKeys {
    private MovementKeys() {}

     
    public static void setState(KeyMapping binding, boolean down, boolean toggleMode) {
        if (toggleMode) {
            if (binding.isDown() != down) binding.setDown(true);
        } else binding.setDown(down);
    }

    public static boolean physicalDown(KeyMapping binding) {
        long window = Minecraft.getInstance().getWindow().handle();
        var key = InputConstants.getKey(binding.saveString());
        if (key.getValue() < 0) return false;
        if (key.getType() == InputConstants.Type.MOUSE)
            return GameInput.mouseDown(window, key.getValue());
        //? if <26.3 {
        if (key.getType() == InputConstants.Type.KEYSYM)
            return GLFW.glfwGetKey(window, key.getValue()) == GLFW.GLFW_PRESS;
        if (key.getType() == InputConstants.Type.SCANCODE) {
            for (int code = GLFW.GLFW_KEY_SPACE; code <= GLFW.GLFW_KEY_LAST; code++)
                if (GLFW.glfwGetKeyScancode(code) == key.getValue())
                    return GLFW.glfwGetKey(window, code) == GLFW.GLFW_PRESS;
        }
        return false;
        //?} else {
        /*return InputConstants.isKeyDown(key.getValue());
        *///?}
    }
}

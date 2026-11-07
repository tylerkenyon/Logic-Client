package dev.technix.logic.mixin;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.mica.internal.ActiveRenderers;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = MouseHandler.class, priority = 1100)
public class MouseHandlerMixin {
    private static boolean logic$isOpen() {
        return LogicClient.MODULES.byName("ClickGUI")
            .filter(module -> ((ClickGui) module).open()).isPresent();
    }

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
        at = @At("HEAD"), cancellable = true)
    private void logic$button(long window, MouseButtonInfo info, int action, CallbackInfo ci) {
        if (!logic$isOpen()) return;
        if (LogicClient.MODULES.get(ClickGui.class).bindMouse(info.button(), action)) { ci.cancel(); return; }
        ActiveRenderers.feedMouseButton(info.button(), action != GLFW.GLFW_RELEASE);
        ci.cancel();
    }

    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void logic$scroll(long window, double x, double y, CallbackInfo ci) {
        if (!logic$isOpen()) return;
        ActiveRenderers.feedMouseWheel(x, y);
        ci.cancel();
    }
}

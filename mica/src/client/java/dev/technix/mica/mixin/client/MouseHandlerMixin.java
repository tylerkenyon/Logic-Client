package dev.technix.mica.mixin.client;

import dev.technix.mica.internal.ActiveRenderers;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.glfw.GLFW;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;



@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {


    //? if <26.3 {
    @Inject(method = "onMove(JDD)V", at = @At("HEAD"))
    private void imguiOnMove(long windowPointer, double xPos, double yPos, CallbackInfo ci) {
        ActiveRenderers.feedMouseMove(xPos, yPos);
    }
    //?} else {
    /*@Inject(method = "onMove(JDDDD)V", at = @At("HEAD"))
    private void imguiOnMove(long windowPointer, double xPos, double yPos, double deltaX, double deltaY, CallbackInfo ci) {
        ActiveRenderers.feedMouseMove(xPos, yPos);
    }
    *///?}


    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V",
            at = @At("HEAD"), cancellable = true)
    private void imguiOnButton(long windowPointer, MouseButtonInfo buttonInfo, int action,
                               CallbackInfo ci) {
        boolean pressed = action != GLFW.GLFW_RELEASE;
        ActiveRenderers.feedMouseButton(dev.technix.mica.internal.GameInput.mouse(buttonInfo.button()), pressed);
        if (ActiveRenderers.wantsMouse()) {
            ci.cancel();
        }
    }


    @Inject(method = "onScroll(JDD)V", at = @At("HEAD"), cancellable = true)
    private void imguiOnScroll(long windowPointer, double xOffset, double yOffset,
                               CallbackInfo ci) {
        ActiveRenderers.feedMouseWheel(xOffset, yOffset);
        if (ActiveRenderers.wantsMouse()) {
            ci.cancel();
        }
    }
}

package dev.technix.mica.mixin.client;

import com.mojang.blaze3d.platform.Window;
import org.spongepowered.asm.mixin.Mixin;

//? if >=26.3 {
/*import dev.technix.mica.internal.GameInput;
import org.lwjgl.sdl.SDL_Event;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
*///?}

@Mixin(Window.class)
abstract class WindowMixin {
    //? if >=26.3 {
    /*@Inject(method = "handleEvent", at = @At("HEAD"), cancellable = true)
    private void mica$ignoreForeignWindowEvents(SDL_Event event, CallbackInfo ci) {
        if (GameInput.isForeignWindowEvent(this, event)) ci.cancel();
    }
    *///?}
}

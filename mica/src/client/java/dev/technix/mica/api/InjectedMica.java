package dev.technix.mica.api;

import dev.technix.mica.internal.ActiveRenderers;
import dev.technix.mica.internal.MinecraftAccess;
import net.minecraft.client.Minecraft;

 
public final class InjectedMica {
    private static OverlayRenderer owned;

    private InjectedMica() { }

    public static void prepare() {
        MinecraftAccess.verify();
        if (!MinecraftCompat.current().isVulkanRendererActive()
                && !MinecraftAccess.hasOpenGlContext()) {
            throw new IllegalStateException("Mica requires a Vulkan or OpenGL renderer");
        }
    }

    public static OverlayRenderer start() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Start Mica on the client thread");
        if (ActiveRenderers.get() != null) return ActiveRenderers.get();
        prepare();
        owned = OverlayRenderer.builder().withMinecraftCompat(MinecraftCompat.current()).withFrostedGlass(false).build();
        ActiveRenderers.set(owned);
         
        return owned;
    }

    public static void stop() {
        if (owned == null) return;
        if (ActiveRenderers.get() == owned) ActiveRenderers.set(null);
        owned.close();
        owned = null;
    }

    public static void beforeRender() {
        if (owned != null) owned.prepareForFrame();
    }

    public static void afterRender() {
        if (owned == null) return;
        //? if <26.3 {
        owned.renderOverlay();
        //?} else {
        /*owned.renderFrame();
        *///?}
    }
}

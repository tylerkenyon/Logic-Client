package dev.technix.mica.api;

import dev.technix.mica.api.compat.v26_2.MinecraftCompatImpl_26_2;
import dev.technix.mica.internal.ActiveRenderers;
import dev.technix.mica.internal.MinecraftAccess;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.minecraft.client.Minecraft;

 
public final class InjectedMica {
    private static OverlayRenderer owned;

    private InjectedMica() { }

    public static void prepare() {
        MinecraftAccess.verify();
        if (!new MinecraftCompatImpl_26_2().isVulkanRendererActive()
                && !MinecraftAccess.hasOpenGlContext()) {
            throw new IllegalStateException("Mica requires a Vulkan or OpenGL renderer");
        }
    }

    public static OverlayRenderer start() {
        if (!Minecraft.getInstance().isSameThread()) throw new IllegalStateException("Start Mica on the client thread");
        if (ActiveRenderers.get() != null) return ActiveRenderers.get();
        prepare();
        owned = OverlayRenderer.builder().withMinecraftCompat(new MinecraftCompatImpl_26_2()).withFrostedGlass(false).build();
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
        if (owned != null) owned.renderOverlay();
    }
}

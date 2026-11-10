package dev.technix.mica.internal;

import dev.technix.mica.api.FontRegistry;
import dev.technix.mica.api.CaptureStatus;
import dev.technix.mica.api.MinecraftCompat;
import dev.technix.mica.api.RenderContext;
import dev.technix.mica.api.TextureFilter;
import dev.technix.mica.internal.backend.vulkan.FrostedGlassRenderer;
import dev.technix.mica.internal.backend.vulkan.VulkanContext;
import dev.technix.mica.internal.backend.vulkan.VulkanImGuiBackend;
import imgui.ImGui;
import imgui.ImGuiIO;
import imgui.gl3.ImGuiImplGl3;
import net.minecraft.client.Minecraft;
import static org.lwjgl.glfw.GLFW.*;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.VK_FILTER_LINEAR;
import static org.lwjgl.vulkan.VK10.VK_FILTER_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_NULL_HANDLE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
import static org.lwjgl.vulkan.VK10.VK_SAMPLER_MIPMAP_MODE_NEAREST;
import static org.lwjgl.vulkan.VK10.VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO;
import static org.lwjgl.vulkan.VK10.VK_SUCCESS;
import static org.lwjgl.vulkan.VK10.vkCreateSampler;
import static org.lwjgl.vulkan.VK10.vkDestroySampler;


public final class ImGuiRenderer {

    
    public record HostRenderTarget(int width, int height, long colorImageView) {
    }

    private final MinecraftCompat compat;

    private List<FontRegistry> fontRegistries = List.of();
    private boolean initialized;
    private volatile boolean streamproof;
    private boolean protectedMode;
    private volatile CaptureStatus captureStatus = CaptureStatus.DISABLED;
    private ProtectedOverlay protectedOverlay;

    public void setStreamproof(boolean enabled) {
        if (streamproof == enabled) return;
        streamproof = enabled;
        captureStatus = enabled ? CaptureStatus.PENDING : CaptureStatus.DISABLED;
    }

    public boolean isStreamproofRequested() { return streamproof; }
    public CaptureStatus captureStatus() { return captureStatus; }

    public void reportCaptureFailure() {
        if (streamproof) captureStatus = CaptureStatus.FAILED;
    }
    private ImGuiImplGl3 openGlBackend;
    private boolean openGl;
    private int frameWidth, frameHeight;
    private VulkanImGuiBackend vulkanBackend;
    private VulkanContext vulkanContext;
    private FrostedGlassRenderer frostedGlassRenderer;

    
    private long sharedSampler;
    private long nearestSampler;

    
    private long lastFrameNanos;

    public ImGuiRenderer(MinecraftCompat compat) {
        this.compat = compat;
    }

     




    public void setFontRegistries(@org.jetbrains.annotations.NotNull List<FontRegistry> registries) {
        this.fontRegistries = List.copyOf(registries);
    }

    
    public boolean isEnabled() {
        return initialized;
    }

    public boolean wantsToCaptureMouse() {
        return initialized && ImGui.getIO().getWantCaptureMouse();
    }

    public boolean wantsToCaptureKeyboard() {
        return initialized && ImGui.getIO().getWantCaptureKeyboard();
    }

    
    private void ensureInitialized() {
        if (initialized && protectedMode != streamproof) {
            FrostedGlassRenderer glass = frostedGlassRenderer;
            shutdown();
            frostedGlassRenderer = glass;
        }
        if (initialized) {
            return;
        }
        if (streamproof && (captureStatus == CaptureStatus.FAILED || captureStatus == CaptureStatus.UNSUPPORTED)) return;
        vulkanContext = compat.currentVulkanContext().orElse(null);
        openGl = vulkanContext == null && glfwGetCurrentContext() != 0;
        if (vulkanContext == null && !openGl) return;
        ImGui.createContext();
        ImGuiIO io = ImGui.getIO();
        io.setIniFilename(null);

        
        
        ImGuiFonts.load(registriesForAtlas());

        if (streamproof) {
            protectedOverlay = new ProtectedOverlay();
            try {
                protectedOverlay.init();
                initialized = true;
                protectedMode = true;
                captureStatus = CaptureStatus.ACTIVE;
            } catch (Throwable failure) {
                protectedOverlay.close();
                protectedOverlay = null;
                ImGuiFonts.reset();
                ImGui.destroyContext();
                captureStatus = failure instanceof UnsupportedOperationException
                    ? CaptureStatus.UNSUPPORTED : CaptureStatus.FAILED;
                org.slf4j.LoggerFactory.getLogger("mica").error("Protected overlay unavailable; hiding Mica overlays until disabled", failure);
            }
            return;
        }
        protectedMode = false;
        if (openGl) {
            openGlBackend = new ImGuiImplGl3();
            if (!openGlBackend.init("#version 330 core")) throw new IllegalStateException("Cannot initialize ImGui OpenGL");
            initialized = true;
            return;
        }

        vulkanBackend = new VulkanImGuiBackend(vulkanContext);
        vulkanBackend.init();
        initialized = true;

        
        
        if (frostedGlassRenderer != null) {
            frostedGlassRenderer.init(vulkanContext, vulkanBackend);
        }
    }

     



    private List<FontRegistry> registriesForAtlas() {
        List<FontRegistry> registries = new ArrayList<>(fontRegistries);
        for (FontRegistry registry : ActiveRenderers.fontRegistries()) {
            if (!registries.contains(registry)) {
                registries.add(registry);
            }
        }
        return registries;
    }


    public void attachFrostedGlass(FrostedGlassRenderer glass) {
        if (frostedGlassRenderer == glass) {
            return;
        }
        if (frostedGlassRenderer != null) {
            frostedGlassRenderer.cleanup();
        }
        frostedGlassRenderer = glass;
        if (initialized && !protectedMode && glass != null && vulkanContext != null) {
            glass.init(vulkanContext, vulkanBackend);
        }
    }

    public FrostedGlassRenderer getFrostedGlassRenderer() {
        return frostedGlassRenderer;
    }

    
    public long registerTexture(long imageView, int imageLayout, TextureFilter filter) {
        if (!initialized || vulkanBackend == null) {
            return 0L;
        }
        return vulkanBackend.addTexture(samplerFor(filter), imageView, imageLayout);
    }

    
    private long sharedSampler() {
        if (sharedSampler != VK_NULL_HANDLE) {
            return sharedSampler;
        }
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_LINEAR)
                    .minFilter(VK_FILTER_LINEAR)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxAnisotropy(1.0f)
                    .minLod(0.0f)
                    .maxLod(0.0f);
            LongBuffer pSampler = stack.mallocLong(1);
            int result = vkCreateSampler(vulkanContext.device(), createInfo, null, pSampler);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateSampler (shared HUD texture) failed with VkResult " + result);
            }
            sharedSampler = pSampler.get(0);
            return sharedSampler;
        }
    }

    
    private long nearestSampler() {
        if (nearestSampler != VK_NULL_HANDLE) {
            return nearestSampler;
        }
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo createInfo = VkSamplerCreateInfo.calloc(stack)
                    .sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO)
                    .magFilter(VK_FILTER_NEAREST)
                    .minFilter(VK_FILTER_NEAREST)
                    .mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .maxAnisotropy(1.0f)
                    .minLod(0.0f)
                    .maxLod(0.0f);
            LongBuffer pSampler = stack.mallocLong(1);
            int result = vkCreateSampler(vulkanContext.device(), createInfo, null, pSampler);
            if (result != VK_SUCCESS) {
                throw new IllegalStateException(
                        "vkCreateSampler (nearest HUD texture) failed with VkResult " + result);
            }
            nearestSampler = pSampler.get(0);
            return nearestSampler;
        }
    }

    private long samplerFor(TextureFilter filter) {
        return filter == TextureFilter.NEAREST ? nearestSampler() : sharedSampler();
    }

    

    
    public boolean beginFrame() {
        ensureInitialized();
        if (!initialized) return false;
        refreshVulkanContext();
        if (vulkanContext == null && !openGl) return false;
        Integer contextWidth = openGl ? null : vulkanContext.framebufferWidth();
        Integer contextHeight = openGl ? null : vulkanContext.framebufferHeight();
        if (openGl) {
            try (MemoryStack stack = stackPush()) {
                var w = stack.mallocInt(1); var h = stack.mallocInt(1);
                glfwGetFramebufferSize(Minecraft.getInstance().getWindow().handle(), w, h);
                contextWidth = w.get(0); contextHeight = h.get(0);
            }
        }
        int width = contextWidth != null && contextWidth > 0 ? contextWidth : 1;
        int height = contextHeight != null && contextHeight > 0 ? contextHeight : 1;

        
        
        long now = System.nanoTime();
        float deltaTime = lastFrameNanos == 0L
                ? 1.0f / 60.0f
                : Math.min((now - lastFrameNanos) / 1_000_000_000.0f, 0.1f);
        lastFrameNanos = now;
        frameWidth = width; frameHeight = height;
        beginFrame(width, height, deltaTime);
        return true;
    }

    public void beginFrame(int width, int height, float deltaTime) {
        ensureInitialized();
        if (!initialized || (vulkanBackend == null && protectedOverlay == null && openGlBackend == null)) {
            
            
            return;
        }
        ImGuiIO io = ImGui.getIO();
        io.setDisplaySize(width, height);
        io.setDeltaTime(deltaTime > 0.0f ? deltaTime : 0.005f);
        if (protectedOverlay != null) protectedOverlay.newFrame();
        else if (openGlBackend != null) openGlBackend.newFrame();
        else vulkanBackend.newFrame();
        ImGui.newFrame();
    }

    
    public static void imGuiFrame(int width, int height, float deltaTime) {
        ImGuiIO io = ImGui.getIO();
        io.setDisplaySize(width, height);
        io.setDeltaTime(deltaTime > 0.0f ? deltaTime : 0.005f);
        ImGui.newFrame();
    }

    
    public void beginElementsFrame() {
        
        
    }

    public void endElementsFrame() {
        
    }

    
    public RenderContext newRenderContext(@Nullable FrostedGlassRenderer frostedGlass,
                                          dev.technix.mica.api.OverlayRenderer renderer) {
        long blurTextureId = 0L;
        if (frostedGlass != null && frostedGlass.isBlurTargetReady()) {
            blurTextureId = frostedGlass.getImGuiTextureId();
        }
        Integer fbWidth = vulkanContext != null ? vulkanContext.framebufferWidth() : null;
        Integer fbHeight = vulkanContext != null ? vulkanContext.framebufferHeight() : null;
        float width = fbWidth != null && fbWidth > 0 ? fbWidth : 1.0f;
        float height = fbHeight != null && fbHeight > 0 ? fbHeight : 1.0f;
        float deltaTime = ImGui.getIO().getDeltaTime();
        return new RenderContext(ImGui.getBackgroundDrawList(), width, height,
                blurTextureId, deltaTime, dev.technix.mica.api.FrostedGlassStyle.DEFAULT, renderer);
    }

    public void endFrame() {
        ImGui.render();
    }

    public void endFrame(VkCommandBuffer commandBuffer) {
        ImGui.render();
        if (vulkanBackend != null && commandBuffer != null) {
            vulkanBackend.render(ImGui.getDrawData(), commandBuffer);
        }
    }

    public void endFrame(VkCommandBuffer commandBuffer, long colorImageView, int width, int height) {
        ImGui.render();
        if (vulkanBackend != null && commandBuffer != null) {
            vulkanBackend.renderInOwnPass(ImGui.getDrawData(), commandBuffer, colorImageView,
                    width, height);
        }
    }

    
    public boolean recordFrostedGlassBlur() {
        if (protectedMode || !initialized) return false;
        if (!initialized || frostedGlassRenderer == null || vulkanContext == null) {
            return false;
        }
        VkCommandBuffer commandBuffer = compat.activeCommandBuffer();
        if (commandBuffer == null) {
            return false;
        }
        long sceneImage = vulkanContext.getCurrentSceneImage();
        if (sceneImage == 0L) {
            return false;
        }
        Integer width = vulkanContext.framebufferWidth();
        Integer height = vulkanContext.framebufferHeight();
        if (width == null || height == null) {
            return false;
        }
        frostedGlassRenderer.checkResize(width, height);
        frostedGlassRenderer.recordBlurPass(commandBuffer, sceneImage,
                vulkanContext.getCurrentSceneImageView(),
                vulkanContext.getCurrentSceneImageLayout(), width, height);
        return true;
    }

    
    public void refreshVulkanContext() {
        if (!initialized) {
            return;
        }
        compat.currentVulkanContext().ifPresent(context -> {
            if (vulkanContext == null || vulkanContext.framebufferWidth() != context.framebufferWidth()
                    || vulkanContext.framebufferHeight() != context.framebufferHeight()) {
                vulkanContext = context;
                if (frostedGlassRenderer != null && !protectedMode) {
                    frostedGlassRenderer.checkResize(context.framebufferWidth() != null
                            ? context.framebufferWidth() : 0,
                            context.framebufferHeight() != null
                                    ? context.framebufferHeight() : 0);
                }
            }
        });
    }

    
    public boolean recordPendingTransfers() {
        if (!initialized || vulkanBackend == null) {
            return false;
        }
        VkCommandBuffer commandBuffer = compat.activeCommandBuffer();
        if (commandBuffer == null) {
            return false;
        }
        return vulkanBackend.recordPendingTransfers(commandBuffer);
    }

    
    public boolean isFontTextureReady() {
        if (protectedOverlay != null || openGlBackend != null) return initialized;
        return initialized && vulkanBackend != null && vulkanBackend.isFontTextureReady();
    }

    
    public void submit(RenderContext context, HostRenderTarget target, boolean blurred) {
        if (protectedOverlay != null) {
            ImGui.render();
            protectedOverlay.render();
            return;
        }
        if (openGlBackend != null) {
            ImGui.render();
            openGlBackend.renderDrawData(ImGui.getDrawData());
            return;
        }
        if (vulkanBackend == null) return;
        VkCommandBuffer commandBuffer = compat.activeCommandBuffer();
        if (commandBuffer == null) {
            return;
        }
        
        
        
        ImGui.render();
        vulkanBackend.renderInOwnPass(ImGui.getDrawData(), commandBuffer, target.colorImageView(),
                target.width(), target.height());
    }

    
    public java.util.Optional<HostRenderTarget> currentHostRenderTarget(MinecraftCompat compat) {
        if (openGl) return java.util.Optional.of(new HostRenderTarget(frameWidth, frameHeight, 0));
        VulkanContext ctx = compat.currentVulkanContext().orElse(null);
        if (ctx == null || ctx.getCurrentSceneImageView() == 0L) {
            return java.util.Optional.empty();
        }
        Integer width = ctx.framebufferWidth();
        Integer height = ctx.framebufferHeight();
        if (width == null || width <= 0 || height == null || height <= 0) {
            return java.util.Optional.empty();
        }
        return java.util.Optional.of(new HostRenderTarget(width, height,
                ctx.getCurrentSceneImageView()));
    }

    public void shutdown() {
        if (!initialized) return;
        if (vulkanBackend != null && vulkanContext != null) {
            org.lwjgl.vulkan.VK10.vkDeviceWaitIdle(vulkanContext.device());
        }
        if (protectedOverlay != null) {
            protectedOverlay.close();
            protectedOverlay = null;
        }
        if (frostedGlassRenderer != null) {
            frostedGlassRenderer.cleanup();
            frostedGlassRenderer = null;
        }
        if (openGlBackend != null) { openGlBackend.shutdown(); openGlBackend = null; }
        if (vulkanBackend != null) {
            vulkanBackend.close();
            vulkanBackend = null;
        }
        
        
        if (sharedSampler != VK_NULL_HANDLE && vulkanContext != null) {
            vkDestroySampler(vulkanContext.device(), sharedSampler, null);
            sharedSampler = VK_NULL_HANDLE;
        }
        if (nearestSampler != VK_NULL_HANDLE && vulkanContext != null) {
            vkDestroySampler(vulkanContext.device(), nearestSampler, null);
            nearestSampler = VK_NULL_HANDLE;
        }
        ImGuiFonts.reset();
        ImGui.destroyContext();
        initialized = false;
        vulkanContext = null;
        openGl = false;
    }

    public boolean isOpenGl() { return openGl; }

    public VulkanContext getVulkanContext() {
        return vulkanContext;
    }

    
    
    

    
    public float viewportWidthOrDefault() {
        if (openGl) return frameWidth;
        Integer w = vulkanContext != null ? vulkanContext.framebufferWidth() : null;
        return w != null && w > 0 ? w : 1.0f;
    }

    
    public float viewportHeightOrDefault() {
        if (openGl) return frameHeight;
        Integer h = vulkanContext != null ? vulkanContext.framebufferHeight() : null;
        return h != null && h > 0 ? h : 1.0f;
    }

    
    public float currentDeltaTime() {
        return ImGui.getIO().getDeltaTime();
    }

    
    public imgui.ImDrawList backgroundDrawList() {
        return ImGui.getBackgroundDrawList();
    }
}

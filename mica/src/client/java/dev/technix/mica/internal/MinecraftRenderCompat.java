package dev.technix.mica.internal;

import com.mojang.blaze3d.pipeline.RenderTarget;
//? if <26.3 {
import com.mojang.blaze3d.systems.CommandEncoder;
//?} else {
/*import com.mojang.renderpearl.api.commands.CommandEncoder;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.systems.CommandEncoderBackend;
//?} else {
/*import com.mojang.renderpearl.backend.api.CommandEncoderBackend;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.systems.GpuDevice;
//?} else {
/*import com.mojang.renderpearl.api.device.GpuDevice;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.systems.GpuDeviceBackend;
//?} else {
/*import com.mojang.renderpearl.backend.api.GpuDeviceBackend;
*///?}
import com.mojang.blaze3d.systems.RenderSystem;
//? if <26.3 {
import com.mojang.blaze3d.textures.GpuTexture;
//?} else {
/*import com.mojang.renderpearl.api.textures.GpuTexture;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.textures.GpuTextureView;
//?} else {
/*import com.mojang.renderpearl.api.textures.GpuTextureView;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanConst;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanConst;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanDevice;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanDevice;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanGpuTexture;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanGpuTexture;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanGpuTextureView;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanQueue;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanQueue;
*///?}
import dev.technix.mica.api.MinecraftCompat;
import dev.technix.mica.api.SpriteBounds;
import dev.technix.mica.api.VanillaAtlases;
import dev.technix.mica.internal.backend.vulkan.VulkanContext;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.AbstractTexture;
import net.minecraft.client.renderer.texture.TextureAtlas;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDevice;

import java.util.Optional;

import static org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_UNDEFINED;


public abstract class MinecraftRenderCompat implements MinecraftCompat {

    
    @Override
    @NotNull
    public Optional<VulkanContext> currentVulkanContext() {
        VulkanDevice device = vulkanDevice();
        if (device == null) {
            return Optional.empty();
        }
        VkDevice vkDevice = device.vkDevice();
        VulkanQueue graphicsQueue = device.graphicsQueue();
        if (vkDevice == null || graphicsQueue == null) {
            return Optional.empty();
        }

        long sceneImage = 0L;
        long sceneImageView = 0L;
        int sceneLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        int colorFormat = 0;
        int width = 0;
        int height = 0;

        RenderTarget target = mainRenderTarget();
        if (target != null) {
            GpuTexture colorTexture = target.getColorTexture();
            GpuTextureView colorView = target.getColorTextureView();
            if (colorTexture instanceof VulkanGpuTexture vulkanTexture
                    && colorView instanceof VulkanGpuTextureView vulkanView) {
                sceneImage = vulkanTexture.vkImage();
                sceneImageView = vulkanView.vkImageView();
                
                
                
                sceneLayout = org.lwjgl.vulkan.VK10.VK_IMAGE_LAYOUT_GENERAL;
                colorFormat = VulkanConst.toVk(colorTexture.getFormat());
                width = target.width;
                height = target.height;
            }
        }

        VulkanContext ctx = new VulkanContext(
                device.instance().vkInstance(),
                vkDevice.getPhysicalDevice(),
                vkDevice,
                graphicsQueue.vkQueue(),
                graphicsQueue.queueFamilyIndex(),
                0,
                1,
                width,
                height,
                sceneImage,
                sceneImageView,
                sceneLayout,
                colorFormat);
        return Optional.of(ctx);
    }

    @Override
    public boolean isVulkanRendererActive() {
        return vulkanDevice() != null && mainRenderTarget() != null;
    }

    @Override
    @Nullable
    public VkCommandBuffer activeCommandBuffer() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        CommandEncoder encoder = device.createCommandEncoder();
        CommandEncoderBackend backend = MinecraftAccess.backend(encoder);
        if (!(backend instanceof VulkanCommandEncoder vulkanEncoder)) {
            return null;
        }
        if (MinecraftAccess.renderPass(vulkanEncoder) != null) {
            return null;
        }
        return MinecraftAccess.commandBuffer(vulkanEncoder);
    }

    @Override
    public long vkImageViewFor(@NotNull Identifier textureId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return 0L;
        }
        AbstractTexture texture = minecraft.getTextureManager().getTexture(textureId);
        if (texture == null) {
            return 0L;
        }
        GpuTextureView view = texture.getTextureView();
        return view instanceof VulkanGpuTextureView vulkanView ? vulkanView.vkImageView() : 0L;
    }

    @Override
    @NotNull
    public Optional<SpriteBounds> locateSprite(@NotNull Identifier atlasId, @NotNull Identifier spriteId) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.getTextureManager() == null) {
            return Optional.empty();
        }
        TextureAtlas atlas = resolveAtlas(minecraft, atlasId);
        if (atlas == null) {
            return Optional.empty();
        }
        TextureAtlasSprite sprite = atlas.getSprite(spriteId);
        if (sprite == atlas.missingSprite()) {
            return Optional.empty();
        }
        return Optional.of(new SpriteBounds(atlas.location(),
                sprite.getU0(), sprite.getV0(), sprite.getU1(), sprite.getV1()));
    }

    
    private static @Nullable TextureAtlas resolveAtlas(Minecraft minecraft, Identifier atlasId) {
        if (minecraft.getTextureManager().getTexture(atlasId) instanceof TextureAtlas atlas) {
            return atlas;
        }
        if (atlasId.equals(VanillaAtlases.ITEMS)
                && minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_ITEMS)
                        instanceof TextureAtlas itemsAtlas) {
            return itemsAtlas;
        }
        if (atlasId.equals(VanillaAtlases.BLOCKS)
                && minecraft.getTextureManager().getTexture(TextureAtlas.LOCATION_BLOCKS)
                        instanceof TextureAtlas blockAtlas) {
            return blockAtlas;
        }
        return null;
    }

    

    
    @Nullable
    private static VulkanDevice vulkanDevice() {
        GpuDevice device = RenderSystem.tryGetDevice();
        if (device == null) {
            return null;
        }
        GpuDeviceBackend backend = MinecraftAccess.backend(device);
        if (!(backend instanceof VulkanDevice)) return null;
        return (VulkanDevice) backend;
    }

    
    @Nullable
    private static RenderTarget mainRenderTarget() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft == null || minecraft.gameRenderer == null) {
            return null;
        }
        return minecraft.gameRenderer.mainRenderTarget();
    }
}

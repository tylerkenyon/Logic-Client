package dev.technix.mica.mixin.client;

//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanCommandEncoder;
*///?}
//? if <26.3 {
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
//?} else {
/*import com.mojang.renderpearl.backend.vulkan.VulkanRenderPass;
*///?}
import org.jetbrains.annotations.Nullable;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;



@Mixin(VulkanCommandEncoder.class)
public interface VulkanCommandEncoderAccessor {

    //? if <26.3 {
    @Invoker("textureInitCommandBuffer")
    //?} else {
    /*@Invoker("commandBuffer")
    *///?}
    VkCommandBuffer imgui$currentCommandBuffer();

    @Accessor("currentRenderPass")
    @Nullable
    VulkanRenderPass imgui$currentRenderPass();
}

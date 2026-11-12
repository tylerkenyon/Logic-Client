package dev.technix.mica.internal;

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
import org.lwjgl.vulkan.VkCommandBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

 
public final class MinecraftAccess {
    //? if <26.3 {
    private static final Field DEVICE_BACKEND = field(GpuDevice.class, "backend");
    private static final Method ENCODER_BACKEND = method(CommandEncoder.class, "backend");
    //?} else {
    /*private static final Field DEVICE_BACKEND = field(com.mojang.renderpearl.frontend.FrontendGpuDevice.class, "backend");
    private static final Method ENCODER_BACKEND = method(com.mojang.renderpearl.frontend.FrontendCommandEncoder.class, "backend");
    *///?}
    private static final Field RENDER_PASS = field(VulkanCommandEncoder.class, "currentRenderPass");
    //? if <26.3 {
    private static final Method COMMAND_BUFFER = method(VulkanCommandEncoder.class, "textureInitCommandBuffer");
    //?} else {
    /*private static final Method COMMAND_BUFFER = method(VulkanCommandEncoder.class, "commandBuffer");
    *///?}

    private MinecraftAccess() { }

    public static void verify() {   }

    record OpenGlTarget(int texture, int mipLevel) { }

    static OpenGlTarget openGlTarget() {
        var target = net.minecraft.client.Minecraft.getInstance().gameRenderer.mainRenderTarget();
        //? if <26.3 {
        if (target.getColorTextureView() instanceof com.mojang.blaze3d.opengl.GlTextureView color) {
        //?} else {
        /*if (target.getColorTextureView() instanceof com.mojang.renderpearl.backend.opengl.GlTextureView color) {
        *///?}
            return new OpenGlTarget(color.glId(), color.fboMipLevel());
        }
        return null;
    }

    public static boolean hasOpenGlContext() {
        //? if <26.3 {
        return org.lwjgl.glfw.GLFW.glfwGetCurrentContext() != 0;
        //?} else {
        /*return org.lwjgl.sdl.SDLVideo.SDL_GL_GetCurrentContext() != 0;
        *///?}
    }

    public static GpuDeviceBackend backend(GpuDevice device) {
        return (GpuDeviceBackend) read(DEVICE_BACKEND, device);
    }

    public static CommandEncoderBackend backend(CommandEncoder encoder) {
        return (CommandEncoderBackend) invoke(ENCODER_BACKEND, encoder);
    }

    public static VulkanRenderPass renderPass(VulkanCommandEncoder encoder) {
        return (VulkanRenderPass) read(RENDER_PASS, encoder);
    }

    public static VkCommandBuffer commandBuffer(VulkanCommandEncoder encoder) {
        return (VkCommandBuffer) invoke(COMMAND_BUFFER, encoder);
    }

    private static Field field(Class<?> owner, String name) {
        try { Field field = owner.getDeclaredField(name); field.setAccessible(true); return field; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private static Method method(Class<?> owner, String name) {
        try { Method method = owner.getDeclaredMethod(name); method.setAccessible(true); return method; }
        catch (ReflectiveOperationException error) { throw new ExceptionInInitializerError(error); }
    }

    private static Object read(Field field, Object target) {
        try { return field.get(target); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }

    private static Object invoke(Method method, Object target) {
        try { return method.invoke(target); }
        catch (ReflectiveOperationException error) { throw new IllegalStateException(error); }
    }
}

package dev.technix.mica.internal;

import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.CommandEncoderBackend;
import com.mojang.blaze3d.systems.GpuDevice;
import com.mojang.blaze3d.systems.GpuDeviceBackend;
import com.mojang.blaze3d.vulkan.VulkanCommandEncoder;
import com.mojang.blaze3d.vulkan.VulkanRenderPass;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.lang.reflect.Field;
import java.lang.reflect.Method;

 
public final class MinecraftAccess {
    private static final Field DEVICE_BACKEND = field(GpuDevice.class, "backend");
    private static final Method ENCODER_BACKEND = method(CommandEncoder.class, "backend");
    private static final Field RENDER_PASS = field(VulkanCommandEncoder.class, "currentRenderPass");
    private static final Method COMMAND_BUFFER = method(VulkanCommandEncoder.class, "textureInitCommandBuffer");

    private MinecraftAccess() { }

    public static void verify() {   }

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

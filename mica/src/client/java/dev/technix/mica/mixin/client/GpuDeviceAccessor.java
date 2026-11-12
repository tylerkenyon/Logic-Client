package dev.technix.mica.mixin.client;

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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;



//? if <26.3 {
@Mixin(GpuDevice.class)
//?} else {
/*@Mixin(com.mojang.renderpearl.frontend.FrontendGpuDevice.class)
*///?}
public interface GpuDeviceAccessor {

    @Accessor("backend")
    GpuDeviceBackend imgui$backend();
}

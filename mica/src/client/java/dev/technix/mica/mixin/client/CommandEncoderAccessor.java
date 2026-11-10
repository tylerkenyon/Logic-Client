package dev.technix.mica.mixin.client;

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
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;



//? if <26.3 {
@Mixin(CommandEncoder.class)
//?} else {
/*@Mixin(com.mojang.renderpearl.frontend.FrontendCommandEncoder.class)
*///?}
public interface CommandEncoderAccessor {

    @Invoker("backend")
    CommandEncoderBackend imgui$backend();
}

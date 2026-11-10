package dev.technix.logic.mixin;

import dev.technix.logic.client.LogicClient;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.PacketListener;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;


@Mixin(targets = "net.minecraft.network.PacketProcessor$ListenerAndPacket")
public class IncomingPacketMixin {
    @Shadow @Final private PacketListener listener;
    @Shadow @Final private Packet<?> packet;

    @Inject(method = "handle", at = @At("HEAD"), cancellable = true)
    private void logic$delayPlayPacket(CallbackInfo ci) {
        if (listener instanceof ClientCommonPacketListenerImpl common && !(listener instanceof ClientPacketListener)
                && LogicClient.interceptCommonPacket(packet, common)) { ci.cancel(); return; }
        if (!(listener instanceof ClientPacketListener client)
                || Minecraft.getInstance().getConnection() != client) return;
        if (LogicClient.interceptPlayPacket(packet, client)) ci.cancel();
    }
}

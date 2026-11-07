package dev.technix.logic.mixin;

import dev.technix.logic.client.LogicClient;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Connection.class)
public abstract class OutgoingPacketMixin {
    @ModifyVariable(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"), argsOnly = true)
    private Packet<?> logic$rewriteOutgoing(Packet<?> packet) {
        return LogicClient.rewriteOutgoing(packet);
    }

    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;Lio/netty/channel/ChannelFutureListener;Z)V",
        at = @At("HEAD"), cancellable = true)
    private void logic$bufferOutgoing(Packet<?> packet, ChannelFutureListener listener, boolean flush, CallbackInfo ci) {
        if (LogicClient.interceptOutgoing((Connection)(Object)this, packet, listener, flush)) ci.cancel();
    }
}

package dev.technix.logic.mixin;

import dev.technix.logic.Logic;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.event.impl.FrameUpdateEvent;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyArg;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Unique
    private long logic$lastFrameNanos;

    @Inject(method = "update(Lnet/minecraft/client/DeltaTracker;)V", at = @At("HEAD"))
    private void logic$beforeCameraUpdate(DeltaTracker deltaTracker, CallbackInfo ci) {
        if (!dev.technix.logic.client.LogicClient.running()) return;
        long now = System.nanoTime();
        double elapsed = logic$lastFrameNanos == 0 ? 0 : (now - logic$lastFrameNanos) / 1_000_000_000.0;
        logic$lastFrameNanos = now;
        Logic.events().post(new FrameUpdateEvent(deltaTracker.getGameTimeDeltaPartialTick(false), elapsed));
    }

    @ModifyArg(method = "renderLevel", at = @At(value = "INVOKE", ordinal = 0,
        target = "Lnet/minecraft/client/renderer/ProjectionMatrixBuffer;getBuffer(Lorg/joml/Matrix4f;)Lcom/mojang/blaze3d/buffers/GpuBufferSlice;"), index = 0)
    private Matrix4f logic$captureProjection(Matrix4f projection) {
        if (!dev.technix.logic.client.LogicClient.running()) return projection;
        GameRenderer renderer = (GameRenderer) (Object) this;
        WorldProjection.capture(projection, renderer.gameRenderState().levelRenderState.cameraRenderState);
        return projection;
    }
}

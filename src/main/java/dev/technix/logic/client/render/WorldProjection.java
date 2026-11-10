package dev.technix.logic.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;

public final class WorldProjection {
    public record Frame(Matrix4f matrix, Vec3 camera, float partialTick, ClientLevel level) { }

    private static Frame frame;

    private WorldProjection() { }

    public static void capture(Matrix4f projection, CameraRenderState camera) {
        var mc = Minecraft.getInstance();
        frame = new Frame(new Matrix4f(projection).mul(camera.viewRotationMatrix), camera.pos,
            mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), mc.level);
    }

    public static Vec3 entityPosition(net.minecraft.world.entity.Entity entity, float partialTick) {
        return new Vec3(
            net.minecraft.util.Mth.lerp((double) partialTick, entity.xOld, entity.getX()),
            net.minecraft.util.Mth.lerp((double) partialTick, entity.yOld, entity.getY()),
            net.minecraft.util.Mth.lerp((double) partialTick, entity.zOld, entity.getZ()));
    }

    public static Frame current() { return frame; }
    public static void clear() { frame = null; }
}

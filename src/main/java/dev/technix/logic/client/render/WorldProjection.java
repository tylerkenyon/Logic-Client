package dev.technix.logic.client.render;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.state.level.CameraRenderState;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix4f;
import org.joml.Vector4f;

public final class WorldProjection {
    public record Frame(Matrix4f matrix, Vec3 camera, float partialTick, ClientLevel level,
                        float horizontalScale, float verticalScale) {
        public Vector4f clip(Vec3 point) {
            Vec3 relative = point.subtract(camera);
            return matrix.transform(new Vector4f((float) relative.x, (float) relative.y, (float) relative.z, 1));
        }

        public float focalLength(float width, float height) {
            return Math.min(Math.abs(horizontalScale) * width, Math.abs(verticalScale) * height) * 0.5f;
        }
    }

    private static Frame frame;

    private WorldProjection() { }

    public static void capture(Matrix4f projection, CameraRenderState camera) {
        var mc = Minecraft.getInstance();
        frame = new Frame(new Matrix4f(projection).mul(camera.viewRotationMatrix), camera.pos,
            mc.getDeltaTracker().getGameTimeDeltaPartialTick(false), mc.level, projection.m00(), projection.m11());
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

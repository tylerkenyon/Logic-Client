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

    public static Frame current() { return frame; }
    public static void clear() { frame = null; }
}

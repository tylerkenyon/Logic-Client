package dev.technix.logic.client.render;

import dev.technix.mica.api.RenderContext;
import imgui.ImColor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.geom.ModelPart;
import net.minecraft.client.renderer.entity.LivingEntityRenderer;
import net.minecraft.client.renderer.entity.state.HumanoidRenderState;
import net.minecraft.world.entity.LivingEntity;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.joml.Vector4f;

public final class SkeletonRenderer {
    private SkeletonRenderer() { }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void draw(RenderContext context, WorldProjection.Frame frame, LivingEntity entity) {
        var renderer = Minecraft.getInstance().getEntityRenderDispatcher().getRenderer(entity);
        if (!(renderer instanceof LivingEntityRenderer living) || !(living.getModel() instanceof HumanoidModel model)) return;
        var state = renderer.createRenderState(entity, frame.partialTick());
        if (!(state instanceof HumanoidRenderState humanoid)) return;
        var parts = model.root().getAllParts();
        var poses = parts.stream().map(part -> ((ModelPart) part).storePose()).toList();
        try {
            model.setupAnim(humanoid);
            var position = entity.getPosition(frame.partialTick()).subtract(frame.camera());
            Matrix4f base = new Matrix4f().translation((float) position.x, (float) position.y, (float) position.z)
                .rotateY((float) Math.toRadians(180 - humanoid.bodyRot))
                .scale(-humanoid.scale, -humanoid.scale, humanoid.scale).translate(0, -1.501f, 0);
            base = partMatrix(base, model.root());
            Vector3f neck = point(base, model.head, 0);
            Vector3f leftHip = point(base, model.leftLeg, 0), rightHip = point(base, model.rightLeg, 0);
            segment(context, frame, neck, new Vector3f(leftHip).add(rightHip).mul(0.5f));
            segment(context, frame, neck, point(base, model.head, -6));
            segment(context, frame, point(base, model.leftArm, 0), point(base, model.rightArm, 0));
            segment(context, frame, leftHip, rightHip);
            for (ModelPart limb : new ModelPart[]{model.leftArm, model.rightArm, model.leftLeg, model.rightLeg}) {
                segment(context, frame, point(base, limb, 0), point(base, limb, 6));
                segment(context, frame, point(base, limb, 6), point(base, limb, 12));
            }
        } finally {
            for (int i = 0; i < parts.size(); i++) ((ModelPart) parts.get(i)).loadPose((net.minecraft.client.model.geom.PartPose) poses.get(i));
        }
    }

    private static Matrix4f partMatrix(Matrix4f parent, ModelPart part) {
        return new Matrix4f(parent).translate(part.x / 16, part.y / 16, part.z / 16)
            .rotateZYX(part.zRot, part.yRot, part.xRot).scale(part.xScale, part.yScale, part.zScale);
    }

    private static Vector3f point(Matrix4f base, ModelPart part, float y) {
        return partMatrix(base, part).transformPosition(new Vector3f(0, y / 16, 0));
    }

    private static void segment(RenderContext context, WorldProjection.Frame frame, Vector3f start, Vector3f end) {
        Vector4f a = frame.matrix().transform(new Vector4f(start, 1));
        Vector4f b = frame.matrix().transform(new Vector4f(end, 1));
        float near = 0.05f;
        if (a.w < near && b.w < near) return;
        if (a.w < near) a.lerp(b, (near - a.w) / (b.w - a.w));
        else if (b.w < near) b.lerp(a, (near - b.w) / (a.w - b.w));
        float x1 = (a.x / a.w + 1) * context.width() / 2;
        float y1 = (1 - a.y / a.w) * context.height() / 2;
        float x2 = (b.x / b.w + 1) * context.width() / 2;
        float y2 = (1 - b.y / b.w) * context.height() / 2;
        if (!Float.isFinite(x1 + y1 + x2 + y2)) return;
        context.drawList().addLine(x1, y1, x2, y2, ImColor.rgba(0, 0, 0, 220), 3);
        context.drawList().addLine(x1, y1, x2, y2, ImColor.rgba(235, 239, 245, 255), 1.2f);
    }
}

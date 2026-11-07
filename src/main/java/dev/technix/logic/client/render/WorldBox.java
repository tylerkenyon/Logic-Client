package dev.technix.logic.client.render;

import dev.technix.mica.api.RenderContext;
import imgui.ImColor;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;

public final class WorldBox {
    private static final int[][] FACES = {{0,1,3,2},{4,6,7,5},{0,4,5,1},{2,3,7,6},{0,2,6,4},{1,5,7,3}};
    private WorldBox() { }

    public static int tint(String name, int alpha) {
        return switch (name) {
            case "Blue" -> ImColor.rgba(143, 184, 232, alpha);
            case "Green" -> ImColor.rgba(151, 211, 177, alpha);
            case "Rose" -> ImColor.rgba(221, 157, 177, alpha);
            case "Amber" -> ImColor.rgba(228, 197, 143, alpha);
            default -> ImColor.rgba(233, 235, 240, alpha);
        };
    }

    public static void draw(RenderContext context, WorldProjection.Frame frame, Vec3 position,
                            float width, float height, float yaw, String color, boolean filled, float thickness) {
        Vector4f[] points = new Vector4f[8];
        Vec3 relative = position.subtract(frame.camera());
        double radians = Math.toRadians(-yaw), sin = Math.sin(radians), cos = Math.cos(radians);
        for (int i = 0; i < 8; i++) {
            double x = ((i & 1) == 0 ? -0.5 : 0.5) * width;
            double z = ((i & 4) == 0 ? -0.5 : 0.5) * width;
            points[i] = frame.matrix().transform(new Vector4f((float) (relative.x + x * cos - z * sin),
                (float) (relative.y + ((i & 2) == 0 ? 0 : height)), (float) (relative.z + x * sin + z * cos), 1));
        }
        var draw = context.drawList();
        draw.pushClipRect(0, 0, context.width(), context.height(), true);
        try {
            if (filled) for (int[] face : FACES) {
                boolean visible = true;
                for (int index : face) if (points[index].w < 0.05f) visible = false;
                if (!visible) continue;
                Vector4f a = points[face[0]], b = points[face[1]], c = points[face[2]], d = points[face[3]];
                draw.addQuadFilled(x(a, context), y(a, context), x(b, context), y(b, context),
                    x(c, context), y(c, context), x(d, context), y(d, context), tint(color, 23));
            }
            for (int i = 0; i < 8; i++) for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) != 0) continue;
                Vector4f a = new Vector4f(points[i]), b = new Vector4f(points[i | bit]);
                if (a.w < 0.05f && b.w < 0.05f) continue;
                if (a.w < 0.05f) a.lerp(b, (0.05f - a.w) / (b.w - a.w));
                else if (b.w < 0.05f) b.lerp(a, (0.05f - b.w) / (a.w - b.w));
                draw.addLine(x(a, context), y(a, context), x(b, context), y(b, context), tint(color, 230), thickness);
            }
        } finally { draw.popClipRect(); }
    }

    private static float x(Vector4f point, RenderContext context) { return (point.x / point.w + 1) * context.width() / 2; }
    private static float y(Vector4f point, RenderContext context) { return (1 - point.y / point.w) * context.height() / 2; }
}

package dev.technix.logic.client.render;

import org.joml.Matrix4fc;
import org.joml.Vector4f;

public final class ScreenProjection {
    public record Bounds(float left, float top, float right, float bottom) { }

    private ScreenProjection() { }


    public static Bounds bounds(Matrix4fc matrix, double minX, double minY, double minZ,
                                double maxX, double maxY, double maxZ,
                                float near, float width, float height) {
        Vector4f[] corners = new Vector4f[8];
        float[] bounds = {Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY,
            Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY};
        for (int i = 0; i < 8; i++) {
            corners[i] = matrix.transform(new Vector4f(
                (float) ((i & 1) == 0 ? minX : maxX),
                (float) ((i & 2) == 0 ? minY : maxY),
                (float) ((i & 4) == 0 ? minZ : maxZ), 1));
            if (corners[i].w >= near) include(corners[i], width, height, bounds);
        }
        for (int i = 0; i < 8; i++) {
            for (int bit = 1; bit <= 4; bit <<= 1) {
                if ((i & bit) != 0) continue;
                Vector4f a = corners[i], b = corners[i | bit];
                if ((a.w >= near) != (b.w >= near)) {
                    float t = (near - a.w) / (b.w - a.w);
                    include(new Vector4f(a).lerp(b, t), width, height, bounds);
                }
            }
        }
        if (!Float.isFinite(bounds[0]) || bounds[2] < 0 || bounds[0] > width
                || bounds[3] < 0 || bounds[1] > height) return null;
        return new Bounds(Math.clamp(bounds[0], 0, width), Math.clamp(bounds[1], 0, height),
            Math.clamp(bounds[2], 0, width), Math.clamp(bounds[3], 0, height));
    }

    private static void include(Vector4f point, float width, float height, float[] bounds) {
        float x = (point.x / point.w + 1) * 0.5f * width;
        float y = (1 - point.y / point.w) * 0.5f * height;
        if (!Float.isFinite(x) || !Float.isFinite(y)) return;
        bounds[0] = Math.min(bounds[0], x);
        bounds[1] = Math.min(bounds[1], y);
        bounds[2] = Math.max(bounds[2], x);
        bounds[3] = Math.max(bounds[3], y);
    }
}

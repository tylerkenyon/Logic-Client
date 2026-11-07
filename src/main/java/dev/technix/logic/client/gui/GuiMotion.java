package dev.technix.logic.client.gui;


public final class GuiMotion {
    private GuiMotion() { }

    public static float smooth(float value, float target, float speed, float seconds) {
        if (!Float.isFinite(seconds) || seconds <= 0) return value;
        float result = value + (target - value) * (float) -Math.expm1(-speed * Math.min(seconds, 0.05f));
        return Math.abs(result - target) < 0.0005f ? target : result;
    }
}

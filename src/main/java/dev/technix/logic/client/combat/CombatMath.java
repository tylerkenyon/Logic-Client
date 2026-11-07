package dev.technix.logic.client.combat;

public final class CombatMath {
    private CombatMath() { }

    public static float wrapDegrees(float angle) {
        float wrapped = angle % 360;
        if (wrapped >= 180) wrapped -= 360;
        if (wrapped < -180) wrapped += 360;
        return wrapped;
    }

    public static float approachAngle(float current, float target, float maxStep) {
        return current + Math.clamp(wrapDegrees(target - current), -maxStep, maxStep);
    }

     
    public static float smoothAngle(float current, float target, double degreesPerSecond, double deltaSeconds) {
        return smoothAngle(current, target, degreesPerSecond, deltaSeconds, 1);
    }

    public static float smoothAngle(float current, float target, double degreesPerSecond, double deltaSeconds,
                                    double smoothing) {
        if (!Double.isFinite(deltaSeconds) || deltaSeconds <= 0
                || !Double.isFinite(degreesPerSecond) || degreesPerSecond <= 0
                || !Double.isFinite(smoothing) || smoothing < 1) return current;
        double seconds = Math.min(deltaSeconds, 0.05);  
        double error = wrapDegrees(target - current);
        double distance = Math.abs(error);
        double response = 10 / smoothing;
        double transition = degreesPerSecond / response;
        double linearTime = Math.max(0, (distance - transition) / degreesPerSecond);
        double step;
        if (seconds <= linearTime) {
            step = degreesPerSecond * seconds;
        } else {
            double remaining = Math.min(distance, transition) * Math.exp(-response * (seconds - linearTime));
            step = distance - remaining;
        }
        return current + (float) Math.copySign(Math.min(step, distance), error);
    }

    public static boolean cooldownReady(float strength, double threshold) {
        return Float.isFinite(strength) && strength >= threshold;
    }
}

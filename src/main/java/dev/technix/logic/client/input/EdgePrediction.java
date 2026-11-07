package dev.technix.logic.client.input;

import java.util.function.DoublePredicate;

 
public final class EdgePrediction {
    private EdgePrediction() {}
    public static boolean reachesEdge(double speedPerTick, double leadMillis, DoublePredicate supportedAtDistance) {
        if (!Double.isFinite(speedPerTick) || !Double.isFinite(leadMillis) || speedPerTick < 0 || leadMillis < 0)
            return false;
        if (!supportedAtDistance.test(0)) return true;
         
        double distance = speedPerTick * (leadMillis / 50.0 + 1);
        if (distance <= 0) return false;
        int samples = Math.clamp((int)Math.ceil(distance / 0.05), 1, 512);
        for (int i = 1; i <= samples; i++)
            if (!supportedAtDistance.test(distance * i / samples)) return true;
        return false;
    }
}

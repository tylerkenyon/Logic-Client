package dev.technix.logic.client.input;

import net.minecraft.world.phys.AABB;

import java.util.function.DoublePredicate;
import java.util.function.Predicate;

 
public final class EdgePrediction {
    private EdgePrediction() {}

    public static boolean reachesEdge(AABB feet, double xPerTick, double zPerTick, double leadMillis,
                                      Predicate<AABB> supported) {
        double speed = Math.hypot(xPerTick, zPerTick);
        if (speed == 0) return !supported.test(feet);
        return reachesEdge(speed, leadMillis,
            distance -> supported.test(feet.move(xPerTick / speed * distance, 0, zPerTick / speed * distance)));
    }

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

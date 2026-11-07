package dev.technix.logic.client.input;

 
public final class EagleAlignment {
    private boolean sneaking;
    private long safeSince = Long.MIN_VALUE, lastTick = Long.MIN_VALUE;

    public boolean update(boolean danger, boolean safelyClear, long tick, boolean align) {
        if (tick < lastTick) reset();
        lastTick = tick;
        if (danger) { sneaking = true; safeSince = Long.MIN_VALUE; }
        else if (!align) { sneaking = false; safeSince = Long.MIN_VALUE; }
        else if (sneaking) {
            if (!safelyClear) safeSince = Long.MIN_VALUE;
            else {
                if (safeSince == Long.MIN_VALUE) safeSince = tick;
                if (tick - safeSince >= 2) { sneaking = false; safeSince = Long.MIN_VALUE; }
            }
        }
        return sneaking;
    }
    public void reset() { sneaking = false; safeSince = lastTick = Long.MIN_VALUE; }

     
    public static double[] alignedHeading(double x, double z) {
        if (Math.abs(x) > Math.abs(z) * 4) return new double[]{Math.signum(x), 0};
        if (Math.abs(z) > Math.abs(x) * 4) return new double[]{0, Math.signum(z)};
        return new double[]{x, z};
    }
}

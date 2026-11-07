package dev.technix.logic.client.module.impl;

public final class UseRateGate {
    private boolean started;
    private long nextUse;
    private long lastUse;

    public boolean tryUseTick(long tick, double cps, int activationTicks) {
        return tryUse(tick * 50_000_000L, cps, activationTicks * 50_000_000L);
    }

    public boolean tryUse(long now, double cps, long activationNanos) {
        if (!Double.isFinite(cps) || cps <= 0) return false;
        if (started && now < lastUse) reset();
        long interval = (long) (1_000_000_000.0 / cps);
        if (!started) {
            started = true;
            nextUse = now + Math.max(interval, activationNanos);
            lastUse = now;
            return true;
        }
        if (now - nextUse < 0) return false;
         
         
        nextUse = now - nextUse >= interval ? now + interval : nextUse + interval;
        lastUse = now;
        return true;
    }

    public void reset() { started = false; nextUse = lastUse = 0; }
}

package dev.technix.logic.client.combat;

 
public final class JumpResetTiming {
    private int previousHurt = Integer.MAX_VALUE;
    private int remaining = -1;

    public boolean update(int hurt, boolean eligible, double chance, double accuracy, double roll, double timingRoll) {
        boolean hit = hurt > previousHurt;
        previousHurt = hurt;
        if (!eligible || hurt <= 0) { remaining = -1; return false; }
        if (hit) remaining = roll * 100 < chance
            ? (int) Math.round((1 - accuracy / 100) * 3 * timingRoll) : -1;
        if (remaining < 0) return false;
        if (remaining-- > 0) return false;
        return true;
    }

    public void reset() { previousHurt = Integer.MAX_VALUE; remaining = -1; }
    public void consumeHit(int hurt) { previousHurt = hurt; remaining = -1; }
}

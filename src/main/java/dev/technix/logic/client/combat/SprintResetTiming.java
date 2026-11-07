package dev.technix.logic.client.combat;

 
public final class SprintResetTiming {
    private int delay = -1, remaining, duration, cooldown, cooldownRemaining;

    public boolean request(int delayTicks, int durationTicks, int cooldownTicks, double chance, double roll) {
        if (delay >= 0 || remaining > 0 || cooldownRemaining > 0 || roll * 100 >= chance) return false;
        delay = delayTicks;
        duration = durationTicks;
        cooldown = cooldownTicks;
        return true;
    }

    public boolean tick() {
        if (remaining > 0) {
            if (--remaining > 0) return true;
            cooldownRemaining = cooldown;
            return false;
        }
        if (cooldownRemaining > 0) cooldownRemaining--;
        if (delay < 0 || delay-- > 0) return false;
        remaining = duration;
        return true;
    }

    public void reset() { delay = -1; remaining = cooldownRemaining = 0; }
}

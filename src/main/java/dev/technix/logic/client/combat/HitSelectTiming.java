package dev.technix.logic.client.combat;

public final class HitSelectTiming {
    private long fightStart = -1;
    private long lastTradeHit = -1;
    private long lastAttack = -1;
    private int previousHurtTime;
    private boolean dealtFirstHit;
    private int timerDelay;

    public void observe(long now, int hurtTime, boolean aimingAtEntity) {
        if (hurtTime > previousHurtTime) lastTradeHit = now;
        previousHurtTime = hurtTime;
        if (aimingAtEntity) engage(now);
        else if (fightStart >= 0 && now - fightStart > 1000) {
            fightStart = lastTradeHit = -1;
            dealtFirstHit = false;
        }
    }

    public void engage(long now) {
        if (fightStart < 0) fightStart = now;
    }

    public boolean openingHit() { return !dealtFirstHit; }

    public boolean receivedHit() { return lastTradeHit >= 0; }

    public boolean tradeReady(long now, int firstHitWait, int tradeDelay) {
        return (firstHitWait == 0 || receivedHit() || fightStart >= 0 && now - fightStart >= firstHitWait)
            && (!receivedHit() || now - lastTradeHit >= tradeDelay);
    }

    public boolean timerReady(long now) {
        return lastAttack < 0 || now - lastAttack >= timerDelay;
    }

    public void dispatched(long now, int nextTimerDelay) {
        lastAttack = now;
        timerDelay = nextTimerDelay;
        dealtFirstHit = true;
    }

    public void reset() {
        fightStart = lastTradeHit = lastAttack = -1;
        previousHurtTime = timerDelay = 0;
        dealtFirstHit = false;
    }
}

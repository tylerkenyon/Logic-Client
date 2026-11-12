package dev.technix.logic.client.combat;

import java.util.function.BooleanSupplier;

public final class HitSelectTiming {
    public enum AttackSource { HELD, TRIGGERBOT }

    private long lastCheckTick = -1;
    private long lastAttackTick = -1;
    private AttackSource selectedClick;

    public boolean beginCheck(long tick, int delayTicks) {
        if (lastCheckTick == tick) return false;
        lastCheckTick = tick;
        return lastAttackTick < 0 || tick - lastAttackTick >= delayTicks;
    }

    public boolean selectClick(boolean held, boolean queuedClick, BooleanSupplier triggerReady) {
        clearClick();
        if (held) selectedClick = AttackSource.HELD;
        else if (!queuedClick && triggerReady.getAsBoolean()) selectedClick = AttackSource.TRIGGERBOT;
        return selectedClick != null;
    }

    public AttackSource dispatched(long tick) {
        var source = selectedClick;
        if (source != null) lastAttackTick = tick;
        clearClick();
        return source;
    }

    public void clearClick() {
        selectedClick = null;
    }

    public void resetDelay() {
        lastAttackTick = -1;
        clearClick();
    }
}

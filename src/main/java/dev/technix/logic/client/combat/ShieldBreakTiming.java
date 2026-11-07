package dev.technix.logic.client.combat;

 
public final class ShieldBreakTiming {
    public enum Action { NONE, SWITCH, ATTACK, RESTORE }
    private enum Stage { IDLE, SWITCH, ATTACK, RESTORE, COOLDOWN }
    private Stage stage = Stage.IDLE;
    private long deadline;

    public boolean busy() { return stage != Stage.IDLE && stage != Stage.COOLDOWN; }
    public boolean restoring() { return stage == Stage.RESTORE; }

    public boolean start(long now, long switchDelay) {
        if (stage == Stage.COOLDOWN && now >= deadline) stage = Stage.IDLE;
        if (stage != Stage.IDLE) return false;
        stage = Stage.SWITCH;
        deadline = now + switchDelay;
        return true;
    }

    public Action poll(long now, long attackDelay, long restoreDelay, long cooldown) {
        if (now < deadline) return Action.NONE;
        return switch (stage) {
            case SWITCH -> { stage = Stage.ATTACK; deadline = now + attackDelay; yield Action.SWITCH; }
            case ATTACK -> { stage = Stage.RESTORE; deadline = now + restoreDelay; yield Action.ATTACK; }
            case RESTORE -> { cancel(now, cooldown); yield Action.RESTORE; }
            default -> Action.NONE;
        };
    }

    public void cancel(long now, long cooldown) { stage = Stage.COOLDOWN; deadline = now + cooldown; }
    public void reset() { stage = Stage.IDLE; deadline = 0; }
}

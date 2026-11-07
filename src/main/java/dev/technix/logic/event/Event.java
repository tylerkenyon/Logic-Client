package dev.technix.logic.event;

public abstract class Event {


    private boolean cancelled;

    public boolean isCancelled() {
        return cancelled;
    }


    public void cancel() {
        this.cancelled = true;
    }
}

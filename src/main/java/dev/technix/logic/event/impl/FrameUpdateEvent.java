package dev.technix.logic.event.impl;

import dev.technix.logic.event.Event;

 
public final class FrameUpdateEvent extends Event {
    private final float partialTick;
    private final double deltaSeconds;

    public FrameUpdateEvent(float partialTick, double deltaSeconds) {
        this.partialTick = partialTick;
        this.deltaSeconds = deltaSeconds;
    }

    public float partialTick() { return partialTick; }
    public double deltaSeconds() { return deltaSeconds; }
}

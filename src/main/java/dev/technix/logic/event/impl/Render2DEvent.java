package dev.technix.logic.event.impl;

import dev.technix.logic.event.Event;
import dev.technix.mica.api.RenderContext;



public final class Render2DEvent extends Event {

    private final RenderContext context;

    public Render2DEvent(RenderContext context) {
        this.context = context;
    }

    public RenderContext context() {
        return context;
    }
}

package dev.technix.logic.event.impl;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.event.Event;

public class ModuleToggleEvent extends Event {

    private final Module module;
    private final boolean enabled;

    public ModuleToggleEvent(Module module, boolean enabled) {
        this.module = module;
        this.enabled = enabled;
    }


    public dev.technix.logic.client.module.Module module() {
        return module;
    }
    public boolean enabled() {
        return enabled;
    }
}

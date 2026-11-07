package dev.technix.logic.event.impl;

import dev.technix.logic.event.Event;
import net.minecraft.world.entity.Entity;

public final class AttackEvent extends Event {
    private final Entity target;
    public AttackEvent(Entity target) { this.target = target; }
    public Entity target() { return target; }
}

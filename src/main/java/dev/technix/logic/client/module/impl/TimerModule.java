package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.NumberSetting;

public final class TimerModule extends Module {
    private final NumberSetting speed = registerSetting(new NumberSetting("Speed",
        "Client tick speed during gameplay. 0 pauses ticks, 1 is normal, and 2 doubles the rate.", 1, 0, 2, 0.05));

    public TimerModule() { super("Timer", "Adjust client game speed while keeping menus responsive.", Category.MOVEMENT); }

    public float tickDuration(float original) {
        if (!enabled()) return original;
        float multiplier = speed.get().floatValue();
        return multiplier == 0 ? Float.POSITIVE_INFINITY : original / multiplier;
    }
}

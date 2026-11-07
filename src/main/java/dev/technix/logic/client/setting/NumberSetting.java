package dev.technix.logic.client.setting;

import java.util.Locale;

public final class NumberSetting extends Setting<Double> {
    private final double min;
    private final double max;
    private final double step;

    public NumberSetting(String name, String description, double value,
                         double min, double max, double step) {
        super(name, description, value);
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step)
                || min >= max || step <= 0) {
            throw new IllegalArgumentException("Invalid numeric setting bounds");
        }
        this.min = min;
        this.max = max;
        this.step = step;
        set(value);
    }

    public double min() { return min; }
    public double max() { return max; }

    @Override
    public void set(Double value) {
        if (value == null || !Double.isFinite(value)) {
            throw new IllegalArgumentException("Setting value must be finite");
        }
        double clamped = Math.clamp(value, min, max);
        super.set(Math.clamp(min + Math.round((clamped - min) / step) * step, min, max));
    }

    public String valueLabel() {
        return String.format(Locale.ROOT, step >= 1 ? "%.0f" : "%.2f", get());
    }
}

package dev.technix.logic.client.setting;

import java.util.Locale;

 
public final class RangeSetting extends Setting<RangeSetting.Value> {
    public record Value(double low, double high) {}
    private final double min, max, step;
    public RangeSetting(String name, String description, double low, double high, double min, double max, double step) {
        super(name, description, new Value(low, high));
        if (!Double.isFinite(min) || !Double.isFinite(max) || !Double.isFinite(step) || min >= max || step <= 0)
            throw new IllegalArgumentException("Invalid range bounds");
        this.min = min; this.max = max; this.step = step;
        set(new Value(low, high));
    }
    public double min() { return min; }
    public double max() { return max; }
    public double low() { return get().low(); }
    public double high() { return get().high(); }
    public double random() {
        Value value = get();
        return value.low() == value.high() ? value.low()
            : java.util.concurrent.ThreadLocalRandom.current().nextDouble(value.low(), value.high());
    }
    public int randomInt() {
        Value value = get();
        return java.util.concurrent.ThreadLocalRandom.current().nextInt((int) value.low(), (int) value.high() + 1);
    }
    private double snap(double value) {
        if (!Double.isFinite(value)) throw new IllegalArgumentException("Range endpoints must be finite");
        return Math.clamp(min + Math.round((Math.clamp(value, min, max) - min) / step) * step, min, max);
    }
    @Override public void set(Value value) {
        if (value == null) throw new IllegalArgumentException("Missing range");
        double a = snap(value.low()), b = snap(value.high());
        super.set(new Value(Math.min(a, b), Math.max(a, b)));
    }
    public void setLow(double value) { set(new Value(Math.min(snap(value), high()), high())); }
    public void setHigh(double value) { set(new Value(low(), Math.max(snap(value), low()))); }
    public String valueLabel() { return String.format(Locale.ROOT, step >= 1 ? "%.0f - %.0f" : "%.2f - %.2f", low(), high()); }
}

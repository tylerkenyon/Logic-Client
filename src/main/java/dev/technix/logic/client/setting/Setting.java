package dev.technix.logic.client.setting;

import java.util.Objects;
import java.util.function.BooleanSupplier;

public abstract class Setting<T> {
    private final String name;
    private final String description;
    private volatile T value;
    private BooleanSupplier visibility = () -> true;

    protected Setting(String name, String description, T value) {
        this.name = Objects.requireNonNull(name);
        this.description = Objects.requireNonNull(description);
        this.value = Objects.requireNonNull(value);
    }

    public final String name() { return name; }
    public final String description() { return description; }
    public final T get() { return value; }
    public void set(T value) { this.value = Objects.requireNonNull(value); }

    public final boolean visible() { return visibility.getAsBoolean(); }
    public final void visibleWhen(BooleanSupplier condition) { visibility = Objects.requireNonNull(condition); }

}

package dev.technix.logic.client.setting;

import java.util.List;

public final class ModeSetting extends Setting<String> {
    private final List<String> options;

    public ModeSetting(String name, String description, String value, String... options) {
        super(name, description, value);
        this.options = List.of(options);
        if (this.options.isEmpty() || this.options.stream().distinct().count() != this.options.size()) {
            throw new IllegalArgumentException("Modes must be nonempty and unique");
        }
        set(value);
    }

    public List<String> options() { return options; }

    @Override
    public void set(String value) {
        if (!options.contains(value)) throw new IllegalArgumentException("Unknown mode: " + value);
        super.set(value);
    }
}

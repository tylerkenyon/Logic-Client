package dev.technix.logic.client.setting;

import java.util.ArrayList;
import java.util.List;

public final class MultiSelectSetting extends Setting<List<String>> {
    private final List<String> options;
    public MultiSelectSetting(String name, String description, List<String> selected, String... options) {
        super(name, description, List.copyOf(selected));
        this.options = List.of(options);
        if (this.options.isEmpty() || this.options.stream().distinct().count() != this.options.size())
            throw new IllegalArgumentException("Options must be nonempty and unique");
        set(selected);
    }
    public List<String> options() { return options; }
    public boolean selected(String option) { return get().contains(option); }
    public void toggleOption(String option) {
        if (!options.contains(option)) throw new IllegalArgumentException("Unknown option: " + option);
        var next = new ArrayList<>(get());
        if (!next.remove(option)) next.add(option);
        set(next);
    }
    @Override public void set(List<String> selected) {
        if (selected == null || !options.containsAll(selected) || selected.stream().distinct().count() != selected.size())
            throw new IllegalArgumentException("Invalid selection");
        super.set(options.stream().filter(selected::contains).toList());
    }
    public String summary() { return get().isEmpty() ? "None" : get().size() == options.size() ? "All" : get().size() + " selected"; }
}

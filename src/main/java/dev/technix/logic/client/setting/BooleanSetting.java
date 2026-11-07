package dev.technix.logic.client.setting;

public final class BooleanSetting extends Setting<Boolean> {
    public BooleanSetting(String name, String description, boolean value) {
        super(name, description, value);
    }

    public void toggle() { set(!get()); }
}

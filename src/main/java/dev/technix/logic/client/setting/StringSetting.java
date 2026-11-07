package dev.technix.logic.client.setting;

public final class StringSetting extends Setting<String> {
    public static final int MAX_LENGTH = 64;

    public StringSetting(String name, String description, String value) {
        super(name, description, value);
        set(value);
    }

    @Override
    public void set(String value) {
        if (value == null || value.length() > MAX_LENGTH || value.codePoints().anyMatch(c -> Character.isISOControl(c) || c == 0xA7))
            throw new IllegalArgumentException("Text must contain at most 64 printable characters");
        super.set(value);
    }
}

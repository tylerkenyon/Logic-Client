package dev.technix.logic.client.module;

public enum Category {
    COMBAT("Combat"),
    MOVEMENT("Movement"),
    RENDER("Visuals"),
    PLAYER("Misc"),
    CONFIGURATION("Configuration");



    private final String displayName;

    Category(String displayName) {
        this.displayName = displayName;
    }

    public String displayName() {
        return displayName;
    }
}

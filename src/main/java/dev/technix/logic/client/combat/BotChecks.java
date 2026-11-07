package dev.technix.logic.client.combat;

 
public final class BotChecks {
    public record Options(boolean stationary, String uuidMode, boolean sleeping, boolean invisible,
                          double minimumAge, boolean username) { }
    public record Sample(double horizontalMovementSquared, int uuidVersion, boolean sleeping,
                         boolean invisibleWithoutArmour, int age, String name) { }

    private double movement;
    private boolean seenVisible, aged, validName;

    public boolean update(Sample sample, Options options) {
        movement += sample.horizontalMovementSquared();
        boolean stationary = movement < 1;
        if (options.stationary() && stationary) return true;
        if (sample.uuidVersion() == 2 && (options.uuidMode().equals("Always")
                || options.uuidMode().equals("While stationary") && stationary)) return true;
        if (options.sleeping() && sample.sleeping()) return true;
        if (options.invisible() && !seenVisible) {
            if (sample.invisibleWithoutArmour()) return true;
            if (sample.age() >= 21) seenVisible = true;
        }
        if (options.minimumAge() > 0 && !aged) {
            if (sample.age() < options.minimumAge()) return true;
            aged = true;
        }
        if (options.username() && !validName) {
            validName = hasValidNameCharacter(sample.name());
            if (!validName) return true;
        }
        return false;
    }

    public static boolean hasValidNameCharacter(String name) {
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c == '\u00a7') { i++; continue; }
            if (c >= 'A' && c <= 'Z' || c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '_') return true;
        }
        return false;
    }
}

package dev.technix.logic.testpayload;
public final class ReinjectionMarker {
    private static int counter;
    public static int increment() { return ++counter; }
}

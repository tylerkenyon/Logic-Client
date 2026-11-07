package dev.technix.logic.inject;


public final class NativeBridge {
    private NativeBridge() { }
    public static native byte[] classBytes(Class<?> type);
    public static native byte[][] classBytesBatch(Class<?>[] types);
    public static native int redefineBatch(Class<?>[] types, byte[][] bytes);
    public static native int redefine(Class<?> type, byte[] bytes);
    public static native void log(String message);
    public static native void release();
}

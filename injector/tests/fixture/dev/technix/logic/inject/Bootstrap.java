package dev.technix.logic.inject;

public final class Bootstrap {
    private static boolean started;
    public static void start() throws Exception {
        if (started) throw new AssertionError("Reinjection reused the old payload's static state");
        started = true;
        Class<?> target = Class.forName("FixtureTarget");
        byte[][] originals = NativeBridge.classBytesBatch(new Class<?>[]{target});
        if (originals == null || originals[0] == null) throw new AssertionError("Capture failed");
        byte[] patch = originals[0].clone();
        int changes = 0;
        for (int i = 0; i < patch.length - 2; i++) {
            if (patch[i] == 0x10 && patch[i+1] == 7 && patch[i+2] == (byte)0xac) { patch[i+1] = 42; changes++; }
        }
        if (changes != 1) throw new AssertionError("Unexpected fixture bytecode");
        int result = NativeBridge.redefineBatch(new Class<?>[]{target}, new byte[][]{patch});
        if (result != 0 || !target.getMethod("value").invoke(null).equals(42)) throw new AssertionError("Redefinition failed: " + result);
        if (NativeBridge.redefineBatch(new Class<?>[]{target}, originals) != 0
                || !target.getMethod("value").invoke(null).equals(7)) throw new AssertionError("Rollback failed");
        NativeBridge.log("client initialised: JNI/JVMTI smoke verified class loading, capture, redefinition and rollback");
        new Thread(() -> {
            try {
                Thread.sleep(300);  
                NativeBridge.release();
                boolean unbound = false;
                try { NativeBridge.classBytes(target); } catch (UnsatisfiedLinkError expected) { unbound = true; }
                if (!unbound) throw new AssertionError("Native bindings remained registered after release");
                Thread.sleep(3000);  
                if (!target.getMethod("value").invoke(null).equals(7)) throw new AssertionError("Code changed after unload");
                ClassLoader loader = Bootstrap.class.getClassLoader();
                if (!(loader instanceof java.net.URLClassLoader urls)) throw new AssertionError("Payload was loaded in the game class loader");
                java.nio.file.Path jar = java.nio.file.Path.of(urls.getURLs()[0].toURI());
                dev.technix.logic.host.RuntimeHost.closePayload(loader);
                if (java.nio.file.Files.exists(jar)) throw new AssertionError("Payload JAR remained open after unload");
                int cycle = Integer.parseInt(System.getProperty("logic.smoke.cycles", "0")) + 1;
                System.setProperty("logic.smoke.cycles", Integer.toString(cycle));
                System.out.println("Fixture cycle " + cycle + " released");
            } catch (Throwable failure) { failure.printStackTrace(); System.setProperty("logic.smoke.failed", "true"); }
        }, "release-smoke").start();
    }
}

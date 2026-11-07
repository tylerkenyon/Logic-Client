public final class FixtureHost {
    public static void main(String[] args) throws Exception {
        Thread.currentThread().setName("Render thread");
        Class.forName("net.fabricmc.loader.impl.launch.FabricLauncherBase");
        if (FixtureTarget.value() != 7) throw new AssertionError();
        System.out.println("Fixture JVM ready " + ProcessHandle.current().pid());
        long deadline = System.nanoTime() + 60_000_000_000L;
        while (Integer.parseInt(System.getProperty("logic.smoke.cycles", "0")) < 2 && !Boolean.getBoolean("logic.smoke.failed") && System.nanoTime() < deadline) Thread.sleep(50);
        if (Integer.parseInt(System.getProperty("logic.smoke.cycles", "0")) != 2) throw new AssertionError("Native reinjection smoke timed out");
        if (FixtureTarget.value() != 7) throw new AssertionError("Rollback did not restore original code");
        System.out.println("JNI/JVMTI reinjection fixture passed: two DLL load/unload cycles, fresh payload state and both extracted JARs closed/deleted in one live JVM");
    }
}

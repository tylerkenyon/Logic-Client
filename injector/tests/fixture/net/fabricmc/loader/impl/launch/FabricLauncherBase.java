package net.fabricmc.loader.impl.launch;
public final class FabricLauncherBase {
    private static final FabricLauncher INSTANCE = new FabricLauncher();
    public static FabricLauncher getLauncher() { return INSTANCE; }
}

package net.fabricmc.loader.impl.launch;

import java.net.URLClassLoader;
import java.nio.file.Path;

 
public final class FabricLauncher extends URLClassLoader {
    public FabricLauncher() { super(new java.net.URL[0], ClassLoader.getSystemClassLoader()); }
    public void addToClassPath(Path path, String... prefixes) throws Exception { addURL(path.toUri().toURL()); }
    public ClassLoader getTargetClassLoader() { return this; }
}

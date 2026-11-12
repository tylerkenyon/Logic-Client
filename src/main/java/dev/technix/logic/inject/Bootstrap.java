package dev.technix.logic.inject;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.host.RuntimeHost;
import dev.technix.mica.internal.ActiveRenderers;
import dev.technix.mica.api.InjectedMica;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class Bootstrap {
    private static final AtomicBoolean STARTED = new AtomicBoolean();
    private static Class<?>[] installedTargets;
    private static byte[][] originalBytes;
    private static boolean ownsRenderer;
    private Bootstrap() { }


    public static void start() { start(null); }

    public static void start(String dataDirectory) {
        if (!STARTED.compareAndSet(false, true)) throw new IllegalStateException("Logic injection has already started");
        try {
            try {
                RuntimeHost.class.getMethod("attackClick", boolean.class);
                RuntimeHost.class.getMethod("blockAttack");
                RuntimeHost.class.getMethod("blockMouseLook");
                RuntimeHost.class.getMethod("afterKeybinds");
                RuntimeHost.class.getMethod("attackDispatched");
                RuntimeHost.class.getMethod("afterPlayerInput", Object.class);
                RuntimeHost.class.getMethod("hideVanillaNametag", Object.class);
                RuntimeHost.class.getMethod("outgoing", Object.class, Object.class, Object.class, boolean.class);
            } catch (NoSuchMethodException oldRuntime) {
                NativeBridge.log("FAILED: restart Minecraft once to update the Logic hook bridge");
                try { RuntimeHost.closePayload(Bootstrap.class.getClassLoader()); }
                finally { NativeBridge.release(); }
                return;
            }
            var loader = FabricLoader.getInstance();
            if (loader.isModLoaded("logic") && RuntimeHost.parentClientRunning()) {
                NativeBridge.log("client initialised: Logic is already running as a Fabric mod; no injection needed");
                RuntimeHost.closePayload(Bootstrap.class.getClassLoader());
                NativeBridge.release();
                return;
            }
            String version = loader.getModContainer("minecraft").orElseThrow().getMetadata().getVersion().getFriendlyString();
            //? if <26.3 {
            String expected = "26.2";
            //?} else {
            /*String expected = "26.3";
            *///?}
            if (!version.equals(expected)) throw new IllegalStateException("Expected Minecraft " + expected + ", found " + version);
            if (!loader.isModLoaded("fabric-api")) {
                throw new IllegalStateException("Injection requires Fabric API; Mica is bundled in the payload");
            }
            Minecraft game = Minecraft.getInstance();
            for (int attempt = 0; game == null && attempt < 600; attempt++) {
                Thread.sleep(100);
                game = Minecraft.getInstance();
            }
            if (game == null) throw new IllegalStateException("Minecraft did not finish starting within 60 seconds");
            CompletableFuture<Void> done = new CompletableFuture<>();
            game.execute(() -> {
                try { install(dataDirectory); done.complete(null); }
                catch (Throwable failure) { done.completeExceptionally(failure); }
            });

            done.join();
        } catch (Throwable failure) {
            NativeBridge.log("FAILED: " + failure);
            throw new IllegalStateException("Logic injection failed", failure);
        }
    }

    private static void install(String dataDirectory) throws Exception {
        boolean injectMica = ActiveRenderers.get() == null;
        boolean micaHooks = !FabricLoader.getInstance().isModLoaded("mica");
        if (injectMica) InjectedMica.prepare();
        ClassLoader loader = Bootstrap.class.getClassLoader();
        InjectionHooks.prepare();
        Class<?>[] targets = new Class<?>[LiveTransformer.TARGETS.length];
        for (int i = 0; i < targets.length; i++) targets[i] = Class.forName(LiveTransformer.TARGETS[i], false, loader);
        byte[][] original = NativeBridge.classBytesBatch(targets);
        if (original == null || original.length != targets.length) throw new IllegalStateException("JVMTI did not capture the target classes");
        byte[][] transformed = new byte[targets.length][];
        for (int i = 0; i < targets.length; i++) {
            if (original[i] == null) throw new IllegalStateException("No class bytes for " + targets[i].getName());
            transformed[i] = LiveTransformer.transform(original[i], loader, micaHooks);
        }
        int result = NativeBridge.redefineBatch(targets, transformed);
        if (result != 0) throw new IllegalStateException("JVMTI rejected the hook transaction: " + result);
        installedTargets = targets;
        originalBytes = original;
        try {
            RuntimeHost.bind(InjectionHooks::dispatch);
            ownsRenderer = injectMica;
            InjectionHooks.ownsMica = micaHooks;
            if (injectMica) InjectedMica.start().setDiagnostics(NativeBridge::log);
            new LogicClient().initializeInjected(dataDirectory == null ? null : java.nio.file.Path.of(dataDirectory, "config.json"));
            InjectionHooks.active = true;
            NativeBridge.log("client initialised: Logic ready; Mica " + (injectMica ? "injected from payload" : "already active"));
        } catch (Throwable failure) {
            InjectionHooks.active = false;
            if (injectMica) InjectedMica.stop();
            int rollback = NativeBridge.redefineBatch(targets, original);
            installedTargets = null;
            originalBytes = null;
            RuntimeHost.closePayload(loader);
            NativeBridge.log("FAILED: initialization failed; hook rollback result " + rollback);
            throw failure;
        }
    }

     
    public static void stop() {
        InjectionHooks.active = false;
        if (installedTargets == null) return;  
        if (ownsRenderer) InjectedMica.stop();
        int result = NativeBridge.redefineBatch(installedTargets, originalBytes);
        if (result != 0) {
            dev.technix.logic.Logic.LOGGER.warn("Hook restoration returned {}; native code retained for safety", result);
            return;
        }
        installedTargets = null;
        originalBytes = null;
        InjectionHooks.clear();
        ownsRenderer = false;
        NativeBridge.log("Logic hooks restored; releasing native loader");
        NativeBridge.release();
        try { RuntimeHost.closePayload(Bootstrap.class.getClassLoader()); }
        catch (java.io.IOException failure) { dev.technix.logic.Logic.LOGGER.warn("Could not close payload JAR", failure); }
    }
}

package dev.technix.logic;

import dev.technix.logic.host.RuntimeHost;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import java.nio.file.*;
import java.util.jar.*;

public final class RuntimeChecks {
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("logic-reload-");
        ClassLoader parent = RuntimeChecks.class.getClassLoader();
        String marker = "dev.technix.logic.testpayload.ReinjectionMarker";
        String resource = marker.replace('.', '/') + ".class";
        ClassLoader previous = null;
        for (int cycle = 0; cycle < 3; cycle++) {
            Path jar = directory.resolve("payload-" + cycle + ".jar");
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar)); var in = parent.getResourceAsStream(resource)) {
                out.putNextEntry(new JarEntry(resource)); out.write(in.readAllBytes()); out.closeEntry();
            }
            ClassLoader loader = RuntimeHost.openPayload(jar.toString(), parent);
            if (loader == previous || loader == parent) throw new AssertionError("Payload loader was reused");
            Class<?> type = Class.forName(marker, true, loader);
            if (type.getClassLoader() != loader || !type.getMethod("increment").invoke(null).equals(1))
                throw new AssertionError("Payload static state was not isolated");
            int[] tickCalls = {0};
            RuntimeHost.bind((hook, values) -> {
                if (hook.equals("startTick") || hook.equals("endTick") || hook.equals("stopClient")) tickCalls[0]++;
                return hook.equals("useItem") ? Boolean.TRUE : null;
            });
            ClientTickEvents.START_CLIENT_TICK.invoker().onStartTick(null);
            ClientTickEvents.END_CLIENT_TICK.invoker().onEndTick(null);
            ClientLifecycleEvents.CLIENT_STOPPING.invoker().onClientStopping(null);
            if (tickCalls[0] != 3 || !RuntimeHost.useItem()) throw new AssertionError("Lifecycle callbacks duplicated or failed");
            RuntimeHost.closePayload(loader);
            if (Files.exists(jar) || RuntimeHost.useItem()) throw new AssertionError("Payload JAR or hook delegate remained active");
            if (!RuntimeHost.attackClick(true) || RuntimeHost.attackClick(false))
                throw new AssertionError("Detached Triggerbot hook must preserve vanilla input");
            if (RuntimeHost.blockAttack() || RuntimeHost.hideVanillaNametag(null) || RuntimeHost.outgoing(null, null, null, true))
                throw new AssertionError("Detached hooks must allow vanilla names and outbound packets");
            ClientTickEvents.END_CLIENT_TICK.invoker().onEndTick(null);
            if (tickCalls[0] != 3) throw new AssertionError("Closed payload received a lifecycle callback");
            previous = loader;
        }
        Files.delete(directory);
        System.out.println("Runtime checks passed: three isolated payloads, fresh state, one lifecycle bridge, JAR closure and inactive hooks after unload.");
    }
}

package dev.technix.logic.host;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.function.BiFunction;

 
public final class RuntimeHost {
    private static volatile BiFunction<String, Object[], Object> dispatcher;
    private static PayloadLoader payload;
    private static boolean eventsInstalled;
    private RuntimeHost() {}

    public static String minecraftVersion() throws ReflectiveOperationException {
        ClassLoader loader = RuntimeHost.class.getClassLoader();
        Class<?> fabric = Class.forName("net.fabricmc.loader.api.FabricLoader", true, loader);
        Object instance = fabric.getMethod("getInstance").invoke(null);
        var minecraft = (java.util.Optional<?>) fabric.getMethod("getModContainer", String.class).invoke(instance, "minecraft");
        Class<?> container = Class.forName("net.fabricmc.loader.api.ModContainer", false, loader);
        Object metadata = container.getMethod("getMetadata").invoke(minecraft.orElseThrow());
        Class<?> modMetadata = Class.forName("net.fabricmc.loader.api.metadata.ModMetadata", false, loader);
        Object version = modMetadata.getMethod("getVersion").invoke(metadata);
        return (String) Class.forName("net.fabricmc.loader.api.Version", false, loader)
            .getMethod("getFriendlyString").invoke(version);
    }

    public static synchronized ClassLoader openPayload(String jar, ClassLoader parent) throws Exception {
        if (payload != null) throw new IllegalStateException("A Logic payload is already active; unload it first");
        boolean micaInParent;
        try { Class.forName("dev.technix.mica.internal.ActiveRenderers", false, parent); micaInParent = true; }
        catch (ClassNotFoundException ignored) { micaInParent = false; }
        payload = new PayloadLoader(Path.of(jar), parent, !micaInParent);
        return payload;
    }

    public static synchronized void bind(BiFunction<String, Object[], Object> target) throws Exception {
        if (dispatcher != null) throw new IllegalStateException("Logic hooks are already bound");
        installEvents();
        dispatcher = java.util.Objects.requireNonNull(target);
    }

    public static synchronized void closePayload(ClassLoader owner) throws IOException {
        if (payload != owner) return;
        dispatcher = null;
        PayloadLoader old = payload;
        payload = null;
        old.close();
        Files.deleteIfExists(old.jar);
    }

    public static boolean parentClientRunning() {
        try {
            Class<?> client = Class.forName("dev.technix.logic.client.LogicClient", false, RuntimeHost.class.getClassLoader());
            return Boolean.TRUE.equals(client.getMethod("running").invoke(null));
        } catch (ReflectiveOperationException ignored) { return false; }
    }

    private static void installEvents() throws Exception {
        if (eventsInstalled) return;
        registerEvent("ClientTickEvents", "START_CLIENT_TICK", "StartTick", "startTick");
        registerEvent("ClientTickEvents", "END_CLIENT_TICK", "EndTick", "endTick");
        registerEvent("ClientLifecycleEvents", "CLIENT_STOPPING", "ClientStopping", "stopClient");
        eventsInstalled = true;
    }
    private static void registerEvent(String group, String field, String listener, String hook) throws Exception {
        String base = "net.fabricmc.fabric.api.client.event.lifecycle.v1." + group;
        ClassLoader parent = RuntimeHost.class.getClassLoader();
        Class<?> events = Class.forName(base, true, parent);
        Class<?> type = Class.forName(base + "$" + listener, true, parent);
        Object proxy = Proxy.newProxyInstance(parent, new Class<?>[]{type}, (self, method, args) -> {
            if (method.getDeclaringClass() == Object.class) return switch (method.getName()) {
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                default -> "Logic runtime " + hook;
            };
            invoke(hook);
            return null;
        });
        Class<?> event = Class.forName("net.fabricmc.fabric.api.event.Event", true, parent);
        event.getMethod("register", Object.class).invoke(events.getField(field).get(null), proxy);
    }
    private static Object invoke(String name, Object... args) {
        var current = dispatcher;
        return current == null ? null : current.apply(name, args);
    }
    public static boolean keyboard(int action, Object key) { return Boolean.TRUE.equals(invoke("keyboard", action, key)); }
    public static boolean attackClick(boolean manualClick) {
        Object result = invoke("attackClick", manualClick);
        return result instanceof Boolean click ? click : manualClick;
    }
    public static boolean mouse(Object button, int action) { return Boolean.TRUE.equals(invoke("mouse", button, action)); }
    public static boolean scroll(double x, double y) { return Boolean.TRUE.equals(invoke("scroll", x, y)); }
    public static void mouseMove(double x, double y) { invoke("mouseMove", x, y); }
    public static boolean blockMouseLook() { return Boolean.TRUE.equals(invoke("blockMouseLook")); }
    public static boolean textInput(Object text) { return Boolean.TRUE.equals(invoke("textInput", text)); }
    public static boolean character(Object event) { return Boolean.TRUE.equals(invoke("character", event)); }
    public static boolean foreignWindowEvent(Object window, Object event) {
        return Boolean.TRUE.equals(invoke("foreignWindowEvent", window, event));
    }
    public static void beforeGuiRender() { invoke("beforeGuiRender"); }
    public static void afterGuiRender() { invoke("afterGuiRender"); }
    public static void frame(Object tracker) { invoke("frame", tracker); }
    public static void projection(Object buffer, Object matrix) { invoke("projection", buffer, matrix); }
    public static void attack(Object target) { invoke("attack", target); }
    public static boolean packet(Object listener, Object packet) { return Boolean.TRUE.equals(invoke("packet", listener, packet)); }
    public static boolean hideVanillaNametag(Object entity) { return Boolean.TRUE.equals(invoke("hideVanillaNametag", entity)); }
    public static Object rewriteOutgoing(Object packet) {
        Object rewritten = invoke("rewriteOutgoing", packet);
        return rewritten == null ? packet : rewritten;
    }
    public static Object rewriteDisplayText(Object text) {
        Object rewritten = invoke("rewriteDisplayText", text);
        return rewritten == null ? text : rewritten;
    }
    public static boolean outgoing(Object connection, Object packet, Object listener, boolean flush) {
        return Boolean.TRUE.equals(invoke("outgoing", connection, packet, listener, flush));
    }
    public static boolean useItem() { return Boolean.TRUE.equals(invoke("useItem")); }
    public static boolean blockAttack() { return Boolean.TRUE.equals(invoke("blockAttack")); }
    public static float tickDuration(float original) {
        Object result = invoke("tickDuration", original);
        return result instanceof Float duration ? duration : original;
    }
    public static void keybinds() { invoke("keybinds"); }
    public static void afterKeybinds() { invoke("afterKeybinds"); }
    public static void attackDispatched() { invoke("attackDispatched"); }

    private static final class PayloadLoader extends URLClassLoader {
        private final Path jar;
        private final boolean ownMica;
        PayloadLoader(Path jar, ClassLoader parent, boolean ownMica) throws Exception {
            super(new URL[]{jar.toUri().toURL()}, parent);
            this.jar = jar; this.ownMica = ownMica;
        }
        @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                boolean own = name.startsWith("dev.technix.logic.") && !name.startsWith("dev.technix.logic.host.")
                    || ownMica && name.startsWith("dev.technix.mica.");
                if (!own) return super.loadClass(name, resolve);
                Class<?> type = findLoadedClass(name);
                if (type == null) type = findClass(name);
                if (resolve) resolveClass(type);
                return type;
            }
        }
    }
}

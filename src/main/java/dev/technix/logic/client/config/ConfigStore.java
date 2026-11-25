package dev.technix.logic.client.config;

import com.google.gson.*;
import dev.technix.logic.Logic;
import dev.technix.logic.client.gui.ClickGuiRenderer;
import dev.technix.logic.client.gui.UiTheme;
import dev.technix.logic.client.module.ModuleManager;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.client.module.impl.Blink;
import dev.technix.logic.client.setting.*;
import java.nio.file.*;
import java.io.IOException;


public final class ConfigStore {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final ModuleManager modules;
    private final ClickGuiRenderer gui;
    private final UiTheme theme;
    private final JsonObject defaults;
    private String saveError = "";
    private String lastSaved;
    private long nextSave;
    private boolean writable = true;
    private java.util.function.Consumer<String> diagnostics = message -> {};

    public void setDiagnostics(java.util.function.Consumer<String> diagnostics) {
        this.diagnostics = java.util.Objects.requireNonNull(diagnostics);
    }

    public ConfigStore(ModuleManager modules, ClickGuiRenderer gui, UiTheme theme) {
        this(defaultPath(), modules, gui, theme);
    }

    public ConfigStore(Path file, ModuleManager modules, ClickGuiRenderer gui, UiTheme theme) {
        this.modules = modules; this.gui = gui; this.theme = theme;
        this.file = file.toAbsolutePath().normalize();
        defaults = snapshot();
    }

    private static Path defaultPath() {
        String appData = System.getenv("APPDATA");
        return Path.of(appData == null || appData.isBlank() ? System.getProperty("user.home") : appData, ".logic", "latest.json");
    }

    public Path file() { return file; }
    public String saveError() { return saveError; }

    public void load() {
        Path source = file;
        if (Files.exists(source)) {
            JsonObject root;
            try { root = read(source); }
            catch (IOException | RuntimeException failure) {
                diagnostics.accept("configuration read failed: " + failure);
                saveError = "Could not read " + source.getFileName();
                try {
                    preserveInvalid(source);
                } catch (IOException backupFailure) {
                    writable = false;
                    Logic.LOGGER.error("Cannot preserve invalid configuration; automatic saving disabled", backupFailure);
                }
                return;
            }
            applyStored(root, false);
        }
        save();
    }

    private void applyStored(JsonObject root, boolean strict) {
        if (root.has("theme") && root.get("theme").isJsonObject()) theme.load(root.getAsJsonObject("theme"));

        if (root.has("panels") && root.get("panels").isJsonObject()) gui.loadLayout(root.getAsJsonObject("panels"));
        if (root.has("modules") && root.get("modules").isJsonObject()) {
            JsonObject stored = root.getAsJsonObject("modules");
            diagnostics.accept("configuration parsed: modules=" + stored.size() + ", registered=" + modules.all().size());
            for (var module : modules.all()) {
                if (!stored.has(module.name()) || !stored.get(module.name()).isJsonObject()) continue;
                JsonObject state = stored.getAsJsonObject(module.name());
                if (state.has("settings") && state.get("settings").isJsonObject()) {
                    JsonObject settings = state.getAsJsonObject("settings");
                    for (Setting<?> setting : module.settings()) {
                        if (!settings.has(setting.name())) continue;
                        if (module.name().equals("Interface") && setting.name().equals("Screenshare protection"))
                            diagnostics.accept("configuration stored protection=" + settings.get(setting.name()));
                        try { readSetting(setting, settings.get(setting.name())); }
                        catch (RuntimeException failure) {
                            if (strict) throw failure;
                            diagnostics.accept("configuration setting failed: " + module.name() + " / " + setting.name() + ": " + failure);
                            Logic.LOGGER.warn("Ignoring invalid setting {} / {}", module.name(), setting.name());
                        }
                    }
                }
                try {
                    if (state.has("key")) module.setKey(state.get("key").getAsInt());
                } catch (RuntimeException failure) {
                    if (strict) throw failure;
                    Logic.LOGGER.warn("Ignoring invalid state for {}", module.name(), failure);
                }
            }
            for (var module : modules.all()) {
                if (!restorable(module) || !stored.has(module.name()) || !stored.get(module.name()).isJsonObject()) continue;
                try {
                    JsonObject state = stored.getAsJsonObject(module.name());
                    if (state.has("enabled")) module.setEnabled(state.get("enabled").getAsBoolean());
                } catch (RuntimeException failure) {
                    if (strict) throw failure;
                    Logic.LOGGER.warn("Ignoring invalid state for {}", module.name(), failure);
                }
            }
        }
    }


    private static boolean restorable(dev.technix.logic.client.module.Module module) {
        return !(module instanceof ClickGui) && !(module instanceof Blink) && !module.configurationOnly() && !module.actionOnly();
    }

    public static void readSetting(Setting<?> setting, JsonElement value) {
        if (setting instanceof MultiSelectSetting multi) {
            if (!value.isJsonArray()) throw new IllegalArgumentException("Expected an array of choices");
            var selected = new java.util.ArrayList<String>();
            for (JsonElement item : value.getAsJsonArray()) {
                if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString())
                    throw new IllegalArgumentException("Expected a choice name");
                selected.add(item.getAsString());
            }
            multi.set(selected);
            return;
        }
        if (setting instanceof RangeSetting range) {
            if (!value.isJsonObject()) throw new IllegalArgumentException("Expected a range object");
            JsonObject object = value.getAsJsonObject();
            JsonElement low = object.get("low"), high = object.get("high");
            if (low == null || high == null || !low.isJsonPrimitive() || !high.isJsonPrimitive()
                    || !low.getAsJsonPrimitive().isNumber() || !high.getAsJsonPrimitive().isNumber())
                throw new IllegalArgumentException("Expected numeric low and high endpoints");
            range.set(new RangeSetting.Value(low.getAsDouble(), high.getAsDouble()));
            return;
        }
        if (!value.isJsonPrimitive()) throw new IllegalArgumentException("Expected a primitive value");
        var primitive = value.getAsJsonPrimitive();
        if (setting instanceof BooleanSetting bool && primitive.isBoolean()) bool.set(value.getAsBoolean());
        else if (setting instanceof NumberSetting number && primitive.isNumber()) number.set(value.getAsDouble());
        else if (setting instanceof ModeSetting mode && primitive.isString()) mode.set(value.getAsString());
        else if (setting instanceof StringSetting text && primitive.isString()) text.set(value.getAsString());
        else if (setting instanceof KeybindSetting bind && primitive.isNumber()) bind.set(primitive.getAsBigDecimal().intValueExact());
        else throw new IllegalArgumentException("Setting type mismatch");
    }

    public void tick() {
        if (System.nanoTime() < nextSave) return;
        nextSave = System.nanoTime() + 1_000_000_000L;
        save();
    }

    public JsonObject snapshot() {
        JsonObject root = new JsonObject(), stored = new JsonObject();
        root.addProperty("version", 2);
        root.add("theme", theme.save()); root.add("panels", gui.saveLayout());
        for (var module : modules.all()) {
            JsonObject state = new JsonObject(), settings = new JsonObject();
            state.addProperty("enabled", !(module instanceof ClickGui) && !(module instanceof Blink) && !module.actionOnly() && module.enabled());
            state.addProperty("key", module.key());
            for (Setting<?> setting : module.settings()) {
                settings.add(setting.name(), JSON.toJsonTree(setting.get()));
            }
            state.add("settings", settings); stored.add(module.name(), state);
        }
        root.add("modules", stored);
        return root;
    }

    public void save() {
        try { saveOrThrow(); }
        catch (IOException failure) {
            diagnostics.accept("configuration save failed: " + failure);
            Logic.LOGGER.warn("Could not save {}", file, failure);
        }
    }

    public void saveOrThrow() throws IOException {
        if (!writable) throw new IOException("Automatic saving disabled: invalid configuration could not be backed up");
        String output = JSON.toJson(snapshot()) + System.lineSeparator();
        try {
            if (!output.equals(lastSaved) || !Files.isRegularFile(file)) {
                write(file, output, true);
                lastSaved = output;
            }
            saveError = "";
        } catch (IOException failure) {
            saveError = "Could not save current session: " + failure.getMessage();
            throw failure;
        }
    }

    public static JsonObject read(Path source) throws IOException {
        try {
            JsonElement root = JsonParser.parseString(Files.readString(source));
            if (!root.isJsonObject()) throw new IllegalArgumentException("Expected a configuration object");
            return root.getAsJsonObject();
        } catch (RuntimeException failure) {
            throw new IOException("Invalid configuration: " + source.getFileName(), failure);
        }
    }

    public static void preserveInvalid(Path source) throws IOException {
        String name = source.getFileName().toString().replaceFirst("\\.json$", "");
        Path backup = source.resolveSibling(name + "-invalid-" + System.nanoTime() + ".json.bak");
        Files.copy(source, backup);
    }

    public static void write(Path target, JsonObject snapshot, boolean replace) throws IOException {
        write(target, JSON.toJson(snapshot) + System.lineSeparator(), replace);
    }

    private static void write(Path target, String output, boolean replace) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".logic-", ".tmp");
        try {
            Files.writeString(temporary, output);
            if (replace) {
                try { Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING); }
            } else Files.move(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    public JsonObject prepare(JsonObject source) {
        JsonObject input = source.deepCopy();
        if (!input.has("modules") || !input.get("modules").isJsonObject())
            throw new IllegalArgumentException("Missing module configuration");
        if (input.has("version") && input.get("version").getAsInt() > 2)
            throw new IllegalArgumentException("This configuration requires a newer client");
        JsonObject result = defaults.deepCopy();
        UiTheme normalizedTheme = new UiTheme();
        normalizedTheme.load(defaults.getAsJsonObject("theme"));
        if (input.has("theme")) {
            JsonObject colors = input.getAsJsonObject("theme");
            for (String radius : java.util.List.of("radius-sm", "radius-md", "radius-lg")) if (colors.has(radius)) {
                JsonElement value = colors.get(radius);
                if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber() || !Float.isFinite(value.getAsFloat()))
                    throw new IllegalArgumentException("Invalid theme radius: " + radius);
            }
            for (String palette : java.util.List.of("dark", "light")) {
                if (colors.has(palette)) for (var color : colors.getAsJsonObject(palette).entrySet())
                    UiTheme.parse(color.getValue().getAsString(), 1);
            }
            normalizedTheme.load(colors);
        }
        result.add("theme", normalizedTheme.save());
        if (input.has("panels")) {
            JsonObject layout = input.getAsJsonObject("panels");
            for (var entry : layout.entrySet()) {
                if (!result.getAsJsonObject("panels").has(entry.getKey())) continue;
                JsonObject panel = entry.getValue().getAsJsonObject();
                JsonObject target = result.getAsJsonObject("panels").getAsJsonObject(entry.getKey());
                if (panel.has("x") != panel.has("y")) throw new IllegalArgumentException("Incomplete panel position");
                for (String axis : java.util.List.of("x", "y")) if (panel.has(axis)) {
                    float value = panel.get(axis).getAsFloat();
                    if (!Float.isFinite(value)) throw new IllegalArgumentException("Invalid panel position");
                    target.addProperty(axis, value);
                }
                if (panel.has("expanded")) {
                    requireBoolean(panel.get("expanded"));
                    target.add("expanded", panel.get("expanded"));
                }
            }
        }
        JsonObject stored = input.getAsJsonObject("modules");
        for (var module : modules.all()) {
            if (!stored.has(module.name())) continue;
            JsonObject state = stored.getAsJsonObject(module.name());
            JsonObject target = result.getAsJsonObject("modules").getAsJsonObject(module.name());
            if (state.has("key")) {
                if (!state.get("key").isJsonPrimitive() || !state.get("key").getAsJsonPrimitive().isNumber())
                    throw new IllegalArgumentException("Expected a numeric module binding");
                int key = state.get("key").getAsBigDecimal().intValueExact();
                if (key != -1 && (key < 32 || key > 348)) throw new IllegalArgumentException("Invalid module binding");
                target.addProperty("key", key);
            }
            if (state.has("enabled")) {
                requireBoolean(state.get("enabled"));
                target.addProperty("enabled", restorable(module) && state.get("enabled").getAsBoolean());
            }
            if (!state.has("settings")) continue;
            JsonObject settings = state.getAsJsonObject("settings");
            for (Setting<?> setting : module.settings()) {
                if (!settings.has(setting.name())) continue;
                Setting<?> copy = copySetting(setting);
                readSetting(copy, settings.get(setting.name()));
                target.getAsJsonObject("settings").add(setting.name(), JSON.toJsonTree(copy.get()));
            }
        }
        return result;
    }

    private static void requireBoolean(JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("Expected a boolean");
    }

    private static Setting<?> copySetting(Setting<?> setting) {
        if (setting instanceof BooleanSetting value) return new BooleanSetting("", "", value.get());
        if (setting instanceof NumberSetting value) return new NumberSetting("", "", value.get(), value.min(), value.max(), value.step());
        if (setting instanceof RangeSetting value) return new RangeSetting("", "", value.low(), value.high(), value.min(), value.max(), value.step());
        if (setting instanceof ModeSetting value) return new ModeSetting("", "", value.get(), value.options().toArray(String[]::new));
        if (setting instanceof MultiSelectSetting value) return new MultiSelectSetting("", "", value.get(), value.options().toArray(String[]::new));
        if (setting instanceof StringSetting value) return new StringSetting("", "", value.get());
        if (setting instanceof KeybindSetting) return new KeybindSetting("", "");
        throw new IllegalArgumentException("Unsupported setting: " + setting.name());
    }

    public void applySnapshot(JsonObject snapshot) {
        gui.cancelInteractions();
        for (var module : modules.all()) if (!(module instanceof ClickGui)) module.setEnabled(false);
        theme.load(defaults.getAsJsonObject("theme"));
        gui.resetLayout();
        applyStored(snapshot, true);
    }
}

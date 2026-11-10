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
    private String lastSaved;
    private long nextSave;
    private boolean writable = true;

    public ConfigStore(ModuleManager modules, ClickGuiRenderer gui, UiTheme theme) {
        this(defaultPath(), modules, gui, theme);
    }

    public ConfigStore(Path file, ModuleManager modules, ClickGuiRenderer gui, UiTheme theme) {
        this.modules = modules; this.gui = gui; this.theme = theme;
        this.file = file;
    }

    private static Path defaultPath() {
        String appData = System.getenv("APPDATA");
        return Path.of(appData == null || appData.isBlank() ? System.getProperty("user.home") : appData, ".logic", "config.json");
    }

    public void load() {
        if (Files.exists(file)) {
            JsonObject root;
            try { root = JsonParser.parseString(Files.readString(file)).getAsJsonObject(); }
            catch (IOException | RuntimeException failure) {
                try {
                    Path backup = file.resolveSibling("config-invalid-" + System.currentTimeMillis() + ".json");
                    Files.copy(file, backup);
                    Logic.LOGGER.warn("Invalid configuration preserved at {}", backup, failure);
                } catch (IOException backupFailure) {
                    writable = false;
                    Logic.LOGGER.error("Cannot preserve invalid configuration; automatic saving disabled", backupFailure);
                }
                return;
            }
            if (root.has("theme") && root.get("theme").isJsonObject()) theme.load(root.getAsJsonObject("theme"));
             
            if ((!root.has("version") || new JsonPrimitive(1).equals(root.get("version")))
                    && theme.radiusSm == 6 && theme.radiusMd == 10 && theme.radiusLg == 16) {
                theme.radiusSm = 4; theme.radiusMd = 6; theme.radiusLg = 10;
            }
            if (root.has("panels") && root.get("panels").isJsonObject()) gui.loadLayout(root.getAsJsonObject("panels"));
            if (root.has("modules") && root.get("modules").isJsonObject()) {
                JsonObject stored = root.getAsJsonObject("modules");
                for (var module : modules.all()) {
                    if (!stored.has(module.name()) || !stored.get(module.name()).isJsonObject()) continue;
                    JsonObject state = stored.getAsJsonObject(module.name());
                    if (state.has("settings") && state.get("settings").isJsonObject()) {
                        JsonObject settings = state.getAsJsonObject("settings");
                        for (Setting<?> setting : module.settings()) {
                            if (!settings.has(setting.name())) continue;
                            try { readSetting(setting, settings.get(setting.name())); }
                            catch (RuntimeException failure) { Logic.LOGGER.warn("Ignoring invalid setting {} / {}", module.name(), setting.name()); }
                        }
                    }
                    try {
                        if (state.has("key")) module.setKey(state.get("key").getAsInt());
                        if (!(module instanceof ClickGui) && !(module instanceof Blink) && !module.configurationOnly() && !module.actionOnly() && state.has("enabled")) module.setEnabled(state.get("enabled").getAsBoolean());
                    } catch (RuntimeException failure) { Logic.LOGGER.warn("Ignoring invalid state for {}", module.name(), failure); }
                }
            }
        }
        save();
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
            if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber()) {
                double fixed = value.getAsDouble();
                range.set(new RangeSetting.Value(fixed, fixed));
                return;
            }
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

    public void save() {
        if (!writable) return;
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
        String output = JSON.toJson(root) + System.lineSeparator();
        if (output.equals(lastSaved)) return;
        try {
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling("config.json.tmp");
            Files.writeString(temporary, output);
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
            lastSaved = output;
        } catch (IOException failure) { Logic.LOGGER.warn("Could not save {}", file, failure); }
    }
}

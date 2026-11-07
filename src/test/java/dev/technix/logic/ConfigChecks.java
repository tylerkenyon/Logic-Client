package dev.technix.logic;

import com.google.gson.*;
import dev.technix.logic.client.config.ConfigStore;
import dev.technix.logic.client.gui.ClickGuiRenderer;
import dev.technix.logic.client.gui.UiTheme;
import dev.technix.logic.client.module.ModuleManager;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.impl.AutoTool;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.client.module.impl.InterfaceModule;
import dev.technix.logic.client.setting.*;
import java.nio.file.*;
import static org.lwjgl.glfw.GLFW.*;

public final class ConfigChecks {
    private static final class Example extends dev.technix.logic.client.module.Module {
        final BooleanSetting flag = registerSetting(new BooleanSetting("Visible", "", true));
        final NumberSetting size = registerSetting(new NumberSetting("Size", "", 1, 0.5, 2, 0.1));
        final ModeSetting mode = registerSetting(new ModeSetting("Mode", "", "Dark", "Dark", "Light"));
        final MultiSelectSetting checks = registerSetting(new MultiSelectSetting("Checks", "", java.util.List.of("Pitch"), "Pitch", "Ground"));
        final RangeSetting range = registerSetting(new RangeSetting("Lead", "", 100, 100, 0, 500, 10));
        final StringSetting text = registerSetting(new StringSetting("Text", "", "You"));
        final KeybindSetting actionKey = registerSetting(new KeybindSetting("Action key", ""));
        Example() {
            super("Example", "", Category.CONFIGURATION);
            text.visibleWhen(flag::get);
        }
    }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("logic-config-check-");
        try {
            ModuleManager modules = new ModuleManager();
            Example module = modules.register(new Example());
            var unload = modules.register(new dev.technix.logic.client.module.impl.SelfDestruct());
            var eagle = modules.register(new dev.technix.logic.client.module.impl.Eagle());
            var backtrack = modules.register(new dev.technix.logic.client.module.impl.Backtrack());
            var playersOnly = (BooleanSetting)backtrack.settings().stream().filter(s -> s.name().equals("Players only")).findFirst().orElseThrow();
            require(!playersOnly.get(), "Allow mob testing by default");
            var sprintReset = modules.register(new dev.technix.logic.client.module.impl.SprintReset());
            var lagRange = modules.register(new dev.technix.logic.client.module.impl.LagRange());
            var lagSettings = lagRange.settings().stream().collect(java.util.stream.Collectors.toMap(Setting::name, setting -> setting));
            var anchor = modules.register(new dev.technix.logic.client.module.impl.AnchorMacro());
            var anchorSettings = anchor.settings().stream().collect(java.util.stream.Collectors.toMap(Setting::name, setting -> setting));
            var autoTool = modules.register(new AutoTool());
            var autoArmor = modules.register(new dev.technix.logic.client.module.impl.AutoArmor());
            var chestStealer = modules.register(new dev.technix.logic.client.module.impl.ChestStealer());
            var armorSettings = autoArmor.settings().stream().collect(java.util.stream.Collectors.toMap(Setting::name, setting -> setting));
            var chestSettings = chestStealer.settings().stream().collect(java.util.stream.Collectors.toMap(Setting::name, setting -> setting));
            var shieldBreaker = modules.register(new dev.technix.logic.client.module.impl.AutoShieldBreaker());
            var shieldSettings = shieldBreaker.settings().stream().collect(java.util.stream.Collectors.toMap(Setting::name, setting -> setting));
            require(!shieldBreaker.enabled() && shieldBreaker.category() == Category.COMBAT, "Shield breaker starts disabled in Combat");
            var toolSettings = autoTool.settings().stream().collect(java.util.stream.Collectors.toMap(Setting::name, setting -> setting));
            InterfaceModule ui = modules.register(new InterfaceModule());
            ui.setEnabled(false);
            ui.screenshareProtection.set(false);
            ClickGui gui = new ClickGui();
            KeybindSetting capture = new KeybindSetting("Capture", "");
            ClickGuiRenderer renderer = new ClickGuiRenderer(gui);
            UiTheme theme = new UiTheme();
            Path file = directory.resolve("config.json");
            ConfigStore config = new ConfigStore(file, modules, renderer, theme);
            config.load();
            ((MultiSelectSetting) armorSettings.get("Armor slots")).set(java.util.List.of("Helmet", "Boots"));
            ((NumberSetting) armorSettings.get("Minimum durability")).set(20.0);
            ((BooleanSetting) armorSettings.get("Inventory only")).set(false);
            ((ModeSetting) chestSettings.get("Item filter")).set("Whitelist");
            ((StringSetting) chestSettings.get("Item IDs")).set("diamond,iron_ingot");
            ((RangeSetting) chestSettings.get("Transfer delay")).set(new RangeSetting.Value(100, 200));
            require(Files.exists(file), "Create a default configuration on first launch");
            module.flag.set(false); module.size.set(1.7); module.mode.set("Light"); module.setKey(65);
            module.text.set("Private name");
            gui.startBinding(module.actionKey);
            require(gui.bindMouse(GLFW_MOUSE_BUTTON_4, GLFW_PRESS) && module.actionKey.get() == GLFW_MOUSE_BUTTON_4,
                "Capture a mouse button as a held macro action binding");
            ((KeybindSetting) anchorSettings.get("Placement key")).set(GLFW_KEY_V);
            ((ModeSetting) anchorSettings.get("Placement inventory")).set("Temporary");
            ((ModeSetting) anchorSettings.get("Charging inventory")).set("Select slot");
            ((RangeSetting) anchorSettings.get("Action interval")).set(new RangeSetting.Value(40, 80));
            require(!anchorSettings.get("Restore after placement").visible() && anchorSettings.get("Restore after charging").visible(),
                "Show restoration toggles only for persistent slot selection");
            ((BooleanSetting) anchorSettings.get("Charge anchors")).set(false);
            require(!anchorSettings.get("Charging inventory").visible() && !anchorSettings.get("Restore after charging").visible()
                && !anchorSettings.get("Charge macro placements").visible(), "Hide all charging options when charging is disabled");
            ((NumberSetting) lagSettings.get("Packet delay")).set(240.0);
            ((BooleanSetting) lagSettings.get("Show server position")).set(false);
            ((ModeSetting) lagSettings.get("Marker colour")).set("Amber");
            ((NumberSetting) lagSettings.get("Marker thickness")).set(2.5);
            ((BooleanSetting) lagSettings.get("Fill marker")).set(true);
            require(!lagSettings.get("Marker colour").visible() && !lagSettings.get("Marker thickness").visible()
                && !lagSettings.get("Fill marker").visible(), "Hide Lag Range appearance settings when its marker is off");
            require(!module.text.visible(), "Hide dependent text when its toggle is off");
            gui.startBinding(capture);
            gui.handleKey(GLFW_KEY_F6, GLFW_REPEAT);
            require(gui.isBinding(capture) && capture.get() == GLFW_KEY_UNKNOWN, "Ignore repeats during capture");
            gui.handleKey(GLFW_KEY_F6, GLFW_PRESS);
            ConfigStore.readSetting(toolSettings.get("Preference"), new JsonPrimitive("Fortune"));
            ConfigStore.readSetting(toolSettings.get("Hotbar slots"), JsonParser.parseString("[\"2\",\"4\"]"));
            ConfigStore.readSetting(toolSettings.get("Durability reserve"), new JsonPrimitive(12));
            module.checks.set(java.util.List.of("Pitch", "Ground"));
            module.range.set(new RangeSetting.Value(60, 150));
            eagle.shiftInAir.set(true); eagle.mode.set("Standard"); playersOnly.set(true);
            for (var setting : sprintReset.settings()) {
                if (setting instanceof NumberSetting number) number.set(number.max());
                else if (setting instanceof BooleanSetting flag) flag.set(true);
            }
            JsonObject layout = JsonParser.parseString("{\"COMBAT\":{\"x\":24,\"y\":35,\"expanded\":false}}").getAsJsonObject();
            renderer.loadLayout(layout);
            for (var setting : shieldBreaker.settings()) if (setting instanceof NumberSetting number) number.set(number.max());
            config.save();
            unload.setEnabled(true);
            config.save();
            require(!JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("modules").getAsJsonObject("Self Destruct").get("enabled").getAsBoolean(), "Never persist an armed one-shot unload action");
            unload.setEnabled(false);
            for (var setting : sprintReset.settings()) {
                if (setting instanceof NumberSetting number) number.set(number.min());
                else if (setting instanceof BooleanSetting flag) flag.set(false);
            }
            ui.screenshareProtection.set(true);
            ((MultiSelectSetting) armorSettings.get("Armor slots")).set(java.util.List.of());
            ((NumberSetting) armorSettings.get("Minimum durability")).set(0.0);
            ((BooleanSetting) armorSettings.get("Inventory only")).set(true);
            ((ModeSetting) chestSettings.get("Item filter")).set("All");
            ((StringSetting) chestSettings.get("Item IDs")).set("");
            ((RangeSetting) chestSettings.get("Transfer delay")).set(new RangeSetting.Value(0, 0));
            for (var setting : shieldBreaker.settings()) if (setting instanceof NumberSetting number) number.set(number.min());
            module.flag.set(true); module.size.set(1.0); module.mode.set("Dark"); module.setKey(-1);
            module.text.set("Changed");
            module.actionKey.set(GLFW_KEY_UNKNOWN);
            ((KeybindSetting) anchorSettings.get("Placement key")).set(GLFW_KEY_UNKNOWN);
            ((ModeSetting) anchorSettings.get("Placement inventory")).set("Held item");
            ((BooleanSetting) anchorSettings.get("Charge anchors")).set(true);
            ((RangeSetting) anchorSettings.get("Action interval")).set(new RangeSetting.Value(0, 0));
            ((NumberSetting) lagSettings.get("Packet delay")).set(0.0);
            ((BooleanSetting) lagSettings.get("Show server position")).set(true);
            ((ModeSetting) lagSettings.get("Marker colour")).set("Blue");
            require(lagSettings.get("Marker colour").visible(), "Restore Lag Range appearance settings when its marker is enabled");
            require(module.text.visible(), "Show dependent text when its toggle is on");
            gui.startBinding(capture);
            gui.handleKey(GLFW_KEY_ESCAPE, GLFW_PRESS);
            require(!gui.isBinding(capture) && capture.get() == GLFW_KEY_F6, "Cancel capture without changing the bind");
            gui.startBinding(capture);
            gui.handleKey(GLFW_KEY_BACKSPACE, GLFW_PRESS);
            require(capture.get() == GLFW_KEY_UNKNOWN, "Clear the current bind");
            ConfigStore.readSetting(toolSettings.get("Preference"), new JsonPrimitive("Fastest"));
            ConfigStore.readSetting(toolSettings.get("Hotbar slots"), new JsonArray());
            ConfigStore.readSetting(toolSettings.get("Durability reserve"), new JsonPrimitive(0));
            module.checks.set(java.util.List.of()); module.range.set(new RangeSetting.Value(0, 0));
            eagle.shiftInAir.set(false); eagle.mode.set("Autoalign"); playersOnly.set(false);
            new ConfigStore(file, modules, renderer, theme).load();
            require(((MultiSelectSetting) armorSettings.get("Armor slots")).get().equals(java.util.List.of("Helmet", "Boots"))
                && armorSettings.get("Minimum durability").get().equals(20.0)
                && armorSettings.get("Inventory only").get().equals(false)
                && chestSettings.get("Item filter").get().equals("Whitelist")
                && chestSettings.get("Item IDs").get().equals("diamond,iron_ingot")
                && ((RangeSetting) chestSettings.get("Transfer delay")).low() == 100
                && ((RangeSetting) chestSettings.get("Transfer delay")).high() == 200, "Persist armor choices and chest filters/timing");
            require(shieldSettings.keySet().containsAll(java.util.List.of("Switch delay", "Attack delay", "Restore delay", "Cooldown", "Range"))
                && shieldBreaker.settings().stream().allMatch(setting -> !(setting instanceof NumberSetting number) || number.get() == number.max()),
                "Persist shield-break delays, retry cooldown and reach independently");
            require(module.text.get().equals("Private name") && !module.text.visible(), "Persist hidden text values and recompute visibility after loading");
            require(module.actionKey.get() == GLFW_MOUSE_BUTTON_4
                && anchorSettings.get("Placement key").get().equals(GLFW_KEY_V)
                && anchorSettings.get("Placement inventory").get().equals("Temporary")
                && !anchorSettings.get("Charging inventory").visible()
                && ((RangeSetting) anchorSettings.get("Action interval")).low() == 40
                && ((RangeSetting) anchorSettings.get("Action interval")).high() == 80,
                "Round-trip macro action bindings, switching modes, timing and hidden stage settings");
            require(lagSettings.get("Packet delay").get().equals(240.0)
                && lagSettings.get("Marker colour").get().equals("Amber")
                && lagSettings.get("Marker thickness").get().equals(2.5)
                && lagSettings.get("Fill marker").get().equals(true) && !lagSettings.get("Marker colour").visible(),
                "Persist Lag Range delay and hidden marker settings");
            boolean badText = false;
            try { ConfigStore.readSetting(module.text, new JsonPrimitive(42)); } catch (IllegalArgumentException expected) { badText = true; }
            require(badText && module.text.get().equals("Private name"), "Reject mismatched text config without changing its value");
            require(eagle.shiftInAir.get() && eagle.mode.get().equals("Standard") && playersOnly.get(), "Persist Eagle air/autoalign settings and Backtrack target filters");
            require(module.checks.get().equals(java.util.List.of("Pitch", "Ground")) && module.range.low() == 60 && module.range.high() == 150, "Round-trip multi-select arrays and min/max range objects");
            boolean badRange = false, badMulti = false;
            try { ConfigStore.readSetting(module.range, JsonParser.parseString("{\"low\":20,\"high\":\"wrong\"}")); } catch (IllegalArgumentException expected) { badRange = true; }
            try { ConfigStore.readSetting(module.checks, JsonParser.parseString("[\"Pitch\",\"Unknown\"]")); } catch (IllegalArgumentException expected) { badMulti = true; }
            require(badRange && badMulti && module.range.high() == 150 && module.checks.selected("Ground"), "Invalid range and multi-select profiles must not partially apply");
            require(sprintReset.settings().stream().allMatch(setting ->
                !(setting instanceof NumberSetting number) || number.get() == number.max()),
                "Round-trip Sprint Reset chance, delay, duration and cooldown");
            require(sprintReset.settings().stream().filter(s -> s instanceof BooleanSetting)
                .allMatch(s -> ((BooleanSetting) s).get()), "Persist Sprint Reset ground restriction");
            require(!ui.screenshareProtection.get() && !ui.enabled(), "Persist capture protection independently of the HUD enabled state");
            require(!module.flag.get() && Math.abs(module.size.get() - 1.7) < 0.001 && module.mode.get().equals("Light") && module.key() == 65, "Round-trip registered settings and key bindings");
            require(toolSettings.get("Preference").get().equals("Fortune")
                && toolSettings.get("Hotbar slots").get().equals(java.util.List.of("2", "4"))
                && toolSettings.get("Durability reserve").get().equals(12.0), "Round-trip Auto Tool preferences and slot filters");
            gui.startBinding(module.flag);
            require(!gui.isBinding(module.flag), "Ordinary settings cannot enter keybind capture");
            require(renderer.saveLayout().getAsJsonObject("COMBAT").get("x").getAsInt() == 24 && !renderer.saveLayout().getAsJsonObject("COMBAT").get("expanded").getAsBoolean(), "Round-trip dragged panel positions and collapse state");
            String saved = Files.readString(file);
            JsonObject root = JsonParser.parseString(saved).getAsJsonObject();
            root.getAsJsonObject("modules").getAsJsonObject("Example").add("settingKeys", JsonParser.parseString("{\"Visible\":65,\"Mode\":66}"));
            root.getAsJsonObject("modules").getAsJsonObject("Self Destruct").addProperty("enabled", true);
            root.addProperty("version", 1);
            root.getAsJsonObject("theme").addProperty("radius-sm", 6);
            root.getAsJsonObject("theme").addProperty("radius-md", 10);
            root.getAsJsonObject("theme").addProperty("radius-lg", 16);
            Files.writeString(file, root.toString());
            new ConfigStore(file, modules, renderer, theme).load();
            require(!JsonParser.parseString(Files.readString(file)).getAsJsonObject().getAsJsonObject("modules")
                .getAsJsonObject("Example").has("settingKeys"), "Remove obsolete per-setting bindings from existing profiles");
            require(theme.radiusMd == 6 && JsonParser.parseString(Files.readString(file)).getAsJsonObject().get("version").getAsInt() == 2, "Upgrade existing profiles to less rounded defaults");
            require(!unload.enabled(), "Never execute Self Destruct while loading a profile");
            JsonObject settings = root.getAsJsonObject("modules").getAsJsonObject("Example").getAsJsonObject("settings");
            settings.addProperty("Size", 1000); settings.addProperty("Mode", "invalid"); settings.addProperty("Visible", "false");
            root.getAsJsonObject("theme").getAsJsonObject("dark").addProperty("bg-primary", "#123456");
            root.getAsJsonObject("theme").getAsJsonObject("dark").addProperty("accent", "wrong");
            Files.writeString(file, root.toString());
            new ConfigStore(file, modules, renderer, theme).load();
            require(module.size.get() == 2 && module.mode.get().equals("Light") && !module.flag.get(), "Clamp numbers and reject mismatched types and invalid modes");
            require(theme.save().getAsJsonObject("dark").get("bg-primary").getAsString().equals("#123456"), "Load custom theme tokens");
            require(theme.save().getAsJsonObject("dark").get("accent").getAsString().equals("#6B8CA7"), "Retain defaults for malformed colors");
            int overlay = UiTheme.parse("#00000080", 0.5f);
            require((overlay >>> 24) == 64, "CSS alpha is the trailing component and scales with opacity");
            Files.writeString(file, "{broken");
            ConfigStore broken = new ConfigStore(file, modules, renderer, theme); broken.load(); broken.save();
            try (var entries = Files.list(directory)) { require(entries.anyMatch(p -> p.getFileName().toString().startsWith("config-invalid-")), "Preserve malformed configs before replacing them"); }
            require(JsonParser.parseString(Files.readString(file)).isJsonObject(), "Recover to a valid profile");
            require(!Files.exists(directory.resolve("config.json.tmp")), "Leave no temporary file after an atomic write");
            System.out.println("Configuration checks passed.");
        } finally {
            try (var entries = Files.list(directory)) { for (Path path : entries.toList()) Files.deleteIfExists(path); }
            Files.deleteIfExists(directory);
        }
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

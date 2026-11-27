package dev.technix.logic;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.technix.logic.client.config.ConfigStore;
import dev.technix.logic.client.config.ProfileManager;
import dev.technix.logic.client.gui.ClickGuiRenderer;
import dev.technix.logic.client.gui.UiTheme;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.module.ModuleManager;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.client.module.impl.KeyPearl;
import dev.technix.logic.client.module.impl.Sprint;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import java.io.IOException;
import java.nio.file.*;
import java.util.Comparator;

public final class ProfileChecks {
    private static final class Feature extends Module {
        final BooleanSetting flag = registerSetting(new BooleanSetting("Flag", "", true));
        final NumberSetting number = registerSetting(new NumberSetting("Number", "", 2, 0, 10, 1));
        Feature peer;
        boolean observedPeer;
        int stops;
        boolean failEnable;
        Feature(String name) { super(name, "", Category.COMBAT); }
        @Override protected void onEnable() {
            if (failEnable) { failEnable = false; throw new IllegalStateException("Simulated activation failure"); }
            observedPeer = peer != null && peer.flag.get();
        }
        @Override protected void onDisable() { stops++; }
    }

    public static void run() throws Exception {
        Path directory = Files.createTempDirectory("logic-profiles-check-");
        ModuleManager modules = new ModuleManager();
        Feature first = modules.register(new Feature("First"));
        Feature second = modules.register(new Feature("Second"));
        first.peer = second;
        KeyPearl pearl = modules.register(new KeyPearl());
        Sprint sprint = modules.register(new Sprint());
        require(sprint.key() == -1, "Sprint defaults to the canonical unbound key");
        ClickGui gui = modules.register(new ClickGui());
        ClickGuiRenderer renderer = new ClickGuiRenderer(gui);
        UiTheme theme = new UiTheme();
        ConfigStore store = new ConfigStore(directory.resolve("latest.json"), modules, renderer, theme);
        int[] checkpoints = {0};
        ProfileManager profiles = new ProfileManager(store, () -> checkpoints[0]++);
        renderer.setProfiles(profiles);
        try {
            first.flag.set(false); first.number.set(7.0); first.setKey(65); first.setEnabled(true);
            theme.radiusMd = 12;
            renderer.loadLayout(JsonParser.parseString("{\"COMBAT\":{\"x\":42,\"y\":18,\"expanded\":false}}").getAsJsonObject());
            ConfigStore.write(directory.resolve("config.json"), store.snapshot(), false);
            String legacy = Files.readString(directory.resolve("config.json"));
            first.flag.set(true); first.number.set(2.0); first.setEnabled(false);
            store.load();
            require(first.flag.get() && first.number.get() == 2 && !first.enabled(), "Do not import legacy config.json");
            require(Files.readString(directory.resolve("config.json")).equals(legacy), "Leave legacy configuration intact");
            require(Files.exists(store.file()), "Create latest on first launch");
            first.flag.set(false); first.number.set(7.0); first.setEnabled(true);
            store.saveOrThrow();

            profiles.saveAs("PvP");
            Path saved = directory.resolve("configs/PvP.json");
            String original = Files.readString(saved);
            first.flag.set(true); first.number.set(4.0); first.setEnabled(false);
            second.flag.set(false); theme.radiusMd = 3;
            store.tick();
            require(Files.readString(saved).equals(original), "Autosave never overwrites a saved config");
            require(ConfigStore.read(store.file()).getAsJsonObject("modules").getAsJsonObject("First")
                .getAsJsonObject("settings").get("Number").getAsInt() == 4, "Autosave updates latest");

            var openField = ClickGui.class.getDeclaredField("open");
            openField.setAccessible(true); openField.setBoolean(gui, true);
            gui.startBinding(first);
            pearl.setEnabled(true);
            profiles.request(ProfileManager.Action.LOAD, "PvP", "");
            require(first.number.get() == 4 && pearl.busy() && checkpoints[0] == 0, "Defer switching until the client tick");
            profiles.processPending();
            require(!profiles.error() && first.enabled() && !first.flag.get() && first.number.get() == 7 && first.key() == 65,
                "Restore module state, settings and binding");
            require(first.observedPeer && second.flag.get(), "Apply every module's settings before enabling modules");
            require(!pearl.busy() && !gui.isBinding(first) && gui.open(), "Cancel active throws and key capture while keeping the menu open");
            require(theme.radiusMd == 12 && renderer.saveLayout().getAsJsonObject("COMBAT").get("x").getAsInt() == 42,
                "Restore theme and layout");
            require(Files.readString(saved).equals(original), "Loading preserves the saved snapshot");
            require(ConfigStore.read(store.file()).equals(store.snapshot()), "Loading immediately updates latest");

            first.number.set(9.0); store.saveOrThrow();
            profiles.select("PvP");
            new ConfigStore(store.file(), modules, renderer, theme).load();
            require(first.number.get() == 9, "Restart restores latest rather than the selected named config");
            profiles.overwrite("PvP");
            require(!Files.readString(saved).equals(original), "Only explicit overwrite changes the saved config");
            profiles.rename("PvP", "Building");
            require(!Files.exists(saved) && profiles.names().equals(java.util.List.of("Building")), "Rename the selected config");
            expectFailure(() -> profiles.saveAs("building"), "Reject case-insensitive duplicate names");
            profiles.saveAs("Other");
            expectFailure(() -> profiles.rename("Other", "BUILDING"), "Rename must not overwrite another config");
            profiles.delete("Other");
            require(profiles.names().equals(java.util.List.of("Building")), "Delete only the chosen snapshot");
            for (String name : java.util.List.of("", " ", "../outside", "a/b", "a\\b", "bad:name", "bad?", "bad*", "bad|", "bad<", "bad>",
                    "bad\"", "trail.", "trail ", "CON", "con.txt", "PRN", "AUX", "NUL", "COM1", "LPT9", "bad\nname")) {
                expectFailure(() -> profiles.saveAs(name), "Reject invalid name: " + name);
            }

            JsonObject older = JsonParser.parseString("{\"modules\":{\"First\":{\"settings\":{\"Number\":3}}}}").getAsJsonObject();
            JsonObject legacySprint = new JsonObject();
            legacySprint.addProperty("key", 0);
            older.getAsJsonObject("modules").add("Sprint", legacySprint);
            Path olderFile = directory.resolve("configs/Older.json");
            ConfigStore.write(olderFile, older, false);
            first.flag.set(false); first.setKey(80); first.setEnabled(true); second.flag.set(false);
            gui.editNumber(first.number);
            int stops = first.stops;
            profiles.load("Older");
            require(sprint.key() == -1 && ConfigStore.read(olderFile).equals(older),
                "Load Sprint's legacy zero binding as unbound while preserving the original named profile");
            require(ConfigStore.read(store.file()).getAsJsonObject("modules").getAsJsonObject("Sprint")
                .get("key").getAsInt() == -1, "Save the normalized Sprint binding to latest");
            require(first.flag.get() && first.number.get() == 3 && first.key() == -1 && !first.enabled() && second.flag.get(),
                "Missing settings, bindings and module states return to registered defaults");
            require(first.stops > stops && gui.editingSetting() == null && gui.open(), "Switch resets module runtime state and numeric editing");
            require(theme.radiusMd == 6 && !renderer.saveLayout().getAsJsonObject("COMBAT").has("x")
                && renderer.saveLayout().getAsJsonObject("COMBAT").get("expanded").getAsBoolean(), "Missing theme and layout return to defaults");
            require(!store.snapshot().toString().contains("Building") && !store.snapshot().toString().contains("Profiles"),
                "Keep saved-config UI selection and name outside persisted settings");

            JsonObject before = store.snapshot();
            String beforeLatest = Files.readString(store.file());
            Path broken = directory.resolve("configs/Broken.json");
            Files.writeString(broken, "{broken");
            int beforeCheckpoints = checkpoints[0];
            gui.startBinding(first); pearl.setEnabled(true);
            expectFailure(() -> profiles.load("Broken"), "Reject malformed profile without applying it");
            require(first.number.get() == 3 && gui.isBinding(first) && pearl.busy() && checkpoints[0] == beforeCheckpoints,
                "Invalid loads leave live actions and editors untouched");
            require(Files.readString(broken).equals("{broken") && Files.readString(store.file()).equals(beforeLatest),
                "Failed reads leave the original profile and latest unchanged");
            profiles.refresh();
            require(profiles.names().stream().noneMatch(name -> name.contains("invalid-")), "Do not list malformed-file backups as profiles");
            JsonObject invalid = store.snapshot();
            invalid.getAsJsonObject("modules").getAsJsonObject("First").getAsJsonObject("settings").addProperty("Flag", "wrong");
            ConfigStore.write(directory.resolve("configs/Invalid.json"), invalid, false);
            expectFailure(() -> profiles.load("Invalid"), "Validate the complete snapshot before touching live settings");
            pearl.setEnabled(false);

            Files.delete(store.file()); Files.createDirectory(store.file());
            profiles.request(ProfileManager.Action.LOAD, "Building", ""); profiles.processPending();
            require(profiles.error() && !profiles.message().isBlank() && checkpoints[0] == beforeCheckpoints && gui.isBinding(first),
                "Failure saving the current session prevents switching and remains visible in the UI");
            Files.delete(store.file()); store.saveOrThrow();
            JsonObject activationFailure = store.snapshot();
            activationFailure.getAsJsonObject("modules").getAsJsonObject("First").addProperty("enabled", true);
            ConfigStore.write(directory.resolve("configs/Activation.json"), activationFailure, false);
            first.failEnable = true;
            expectFailure(() -> profiles.load("Activation"), "Roll back an activation failure");
            require(store.snapshot().equals(before) && !first.enabled(), "Restore the previous snapshot after an activation failure");

            ProfileManager failingSave = new ProfileManager(store, () -> {
                try { Files.delete(store.file()); Files.createDirectory(store.file()); }
                catch (IOException failure) { throw new RuntimeException(failure); }
            });
            String building = Files.readString(directory.resolve("configs/Building.json"));
            expectFailure(() -> failingSave.load("Building"), "Roll back a failure writing latest after application");
            require(store.snapshot().equals(before) && Files.readString(directory.resolve("configs/Building.json")).equals(building),
                "A failed final save preserves the previous live settings and named file");
            Files.delete(store.file()); store.saveOrThrow();

            Path blockedDirectory = directory.resolve("blocked"); Files.writeString(blockedDirectory, "blocked");
            ConfigStore blockedStore = new ConfigStore(blockedDirectory.resolve("latest.json"), modules, renderer, theme);
            ProfileManager blocked = new ProfileManager(blockedStore, () -> {});
            expectFailure(() -> blocked.saveAs("CannotWrite"), "Report a failed profile write");
            require(Files.readString(blockedDirectory).equals("blocked") && store.snapshot().equals(before), "Failed writes preserve existing data");
            System.out.println("Profile checks passed: latest startup, snapshots, autosave isolation, queued switching, defaults, lifecycle and failure recovery.");
        } finally {
            for (var module : modules.all()) if (!(module instanceof ClickGui)) module.setEnabled(false);
            Logic.events().clear();
            try (var entries = Files.walk(directory)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(entry);
            }
        }
    }

    @FunctionalInterface private interface Operation { void run() throws Exception; }
    private static void expectFailure(Operation operation, String message) throws Exception {
        try { operation.run(); }
        catch (IOException | IllegalArgumentException expected) { return; }
        throw new AssertionError(message);
    }
    private static void require(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}

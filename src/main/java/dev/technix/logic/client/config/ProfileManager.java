package dev.technix.logic.client.config;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Locale;

public final class ProfileManager {
    public enum Action { LOAD, SAVE_AS, OVERWRITE, RENAME, DELETE, REFRESH }
    private record Request(Action action, String name, String newName) {}
    private final ConfigStore store;
    private final Path directory;
    private final Runnable beforeLoad;
    private List<String> names = List.of();
    private Request pending;
    private String selected = "", message = "";
    private boolean error;

    public ProfileManager(ConfigStore store, Runnable beforeLoad) {
        this.store = store;
        this.beforeLoad = beforeLoad;
        directory = store.file().getParent().resolve("configs");
        try { refresh(); }
        catch (IOException failure) { fail(failure); }
    }

    public List<String> names() { return names; }
    public String selected() { return selected; }
    public void select(String name) { selected = name; }
    public boolean pending() { return pending != null; }
    public boolean error() { return error || !store.saveError().isEmpty(); }
    public String message() { return store.saveError().isEmpty() ? message : store.saveError(); }

    public void request(Action action, String name, String newName) {
        if (pending != null) return;
        pending = new Request(action, name, newName);
        message = "Waiting for client tick...";
        error = false;
    }

    public void processPending() {
        Request request = pending;
        if (request == null) return;
        try {
            switch (request.action()) {
                case LOAD -> load(request.name());
                case SAVE_AS -> saveAs(request.name());
                case OVERWRITE -> overwrite(request.name());
                case RENAME -> rename(request.name(), request.newName());
                case DELETE -> delete(request.name());
                case REFRESH -> refresh();
            }
            message = switch (request.action()) {
                case LOAD -> "Loaded " + request.name();
                case SAVE_AS -> "Saved " + request.name();
                case OVERWRITE -> "Updated " + request.name();
                case RENAME -> "Renamed to " + request.newName();
                case DELETE -> "Deleted " + request.name();
                case REFRESH -> "Config list refreshed";
            };
            error = false;
        } catch (IOException | RuntimeException failure) {
            fail(failure);
        } finally { pending = null; }
    }

    private void fail(Exception failure) {
        error = true;
        message = failure.getMessage() == null ? "Configuration operation failed" : failure.getMessage();
    }

    public void refresh() throws IOException {
        if (!Files.exists(directory)) { names = List.of(); return; }
        if (Files.isSymbolicLink(directory)) throw new IOException("The configs directory cannot be a symbolic link");
        try (var entries = Files.list(directory)) {
            names = entries.filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
                .map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(".json"))
                .map(name -> name.substring(0, name.length() - 5))
                .filter(ProfileManager::validName)
                .sorted(String.CASE_INSENSITIVE_ORDER).toList();
        }
        if (!names.contains(selected)) selected = "";
    }

    public static String validateName(String name) {
        if (name == null || name.isBlank() || !name.equals(name.strip()) || name.length() > 64
                || name.endsWith(".") || name.matches(".*[<>:\"/\\\\|?*\\p{Cntrl}].*")
                || name.equals(".") || name.equals(".."))
            throw new IllegalArgumentException("Use 1-64 characters without path separators or Windows-invalid characters");
        String base = name.split("\\.", 2)[0].stripTrailing().toUpperCase(Locale.ROOT);
        if (base.matches("CON|PRN|AUX|NUL|COM[1-9¹²³]|LPT[1-9¹²³]|CONIN\\$|CONOUT\\$"))
            throw new IllegalArgumentException("This name is reserved by Windows");
        return name;
    }

    private static boolean validName(String name) {
        try { validateName(name); return true; }
        catch (IllegalArgumentException invalid) { return false; }
    }

    private Path existing(String name) throws IOException {
        validateName(name);
        refresh();
        String actual = names.stream().filter(value -> value.equalsIgnoreCase(name)).findFirst()
            .orElseThrow(() -> new IOException("Saved config not found: " + name));
        Path file = directory.resolve(actual + ".json");
        if (Files.isSymbolicLink(file)) throw new IOException("Saved configs cannot be symbolic links");
        return file;
    }

    private Path unused(String name) throws IOException {
        validateName(name);
        refresh();
        if (names.stream().anyMatch(value -> value.equalsIgnoreCase(name)))
            throw new IOException("A config with this name already exists");
        return directory.resolve(name + ".json");
    }

    public void saveAs(String name) throws IOException {
        Path target = unused(name);
        ConfigStore.write(target, store.snapshot(), false);
        refresh();
        selected = name;
    }

    public void overwrite(String name) throws IOException {
        ConfigStore.write(existing(name), store.snapshot(), true);
    }

    public void rename(String name, String newName) throws IOException {
        Path source = existing(name), target = unused(newName);
        Files.move(source, target);
        refresh();
        selected = newName;
    }

    public void delete(String name) throws IOException {
        Files.delete(existing(name));
        refresh();
    }

    public void load(String name) throws IOException {
        Path source = existing(name);
        JsonObject prepared;
        try { prepared = store.prepare(ConfigStore.read(source)); }
        catch (IOException | RuntimeException failure) {
            try { ConfigStore.preserveInvalid(source); }
            catch (IOException backupFailure) { failure.addSuppressed(backupFailure); }
            throw new IOException("Cannot load " + name + ": " + failure.getMessage(), failure);
        }
        store.saveOrThrow();
        JsonObject previous = store.snapshot();
        try {
            beforeLoad.run();
            store.applySnapshot(prepared);
            store.saveOrThrow();
        } catch (IOException | RuntimeException failure) {
            try { store.applySnapshot(previous); }
            catch (RuntimeException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                throw new IOException("Could not load " + name + "; could not fully restore previous settings", failure);
            }
            throw new IOException("Could not load " + name + "; previous settings restored", failure);
        }
        selected = source.getFileName().toString().replaceFirst("\\.json$", "");
    }
}

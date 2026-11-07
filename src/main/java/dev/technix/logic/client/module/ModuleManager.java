package dev.technix.logic.client.module;

import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.KeyPressEvent;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

public final class ModuleManager {

    

    private final List<Module> modules = new ArrayList<>();


    public <T extends Module> T register(T module) {
        modules.add(module);
        if (module.enabledByDefault()) {
            module.setEnabled(true);
        }
        return module;
    }

    public List<Module> all() {
        return Collections.unmodifiableList(modules);
    }

    public void clear() { modules.clear(); }

    public List<Module> inCategory(Category category) {
        return modules.stream().filter(m -> m.category() == category).toList();
    }

    public Optional<Module> byName(String name) {
        return modules.stream().filter(m -> m.name().equalsIgnoreCase(name)).findFirst();
    }

    public <T extends Module> T get(Class<T> type) {
        for (Module module : modules) {
            if (type.isInstance(module)) {
                return type.cast(module);
            }
        }
        throw new IllegalArgumentException("Module not registered: " + type.getSimpleName());
    }


    @Subscribe
    private void onKey(KeyPressEvent event) {
        if (!event.isPress()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();

        if (mc == null) return;
        for (Module module : modules) {
            if (!(module instanceof ClickGui) && (mc.player == null || mc.gui.screen() != null)) continue;
            if (module.key() != GLFW.GLFW_KEY_UNKNOWN && module.key() == event.key()) {
                module.toggle();
                if (module instanceof ClickGui gui && gui.open()) return;
            }
        }
    }
}

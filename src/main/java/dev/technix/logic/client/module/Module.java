package dev.technix.logic.client.module;

import dev.technix.logic.Logic;
import dev.technix.logic.event.impl.ModuleToggleEvent;
import dev.technix.logic.client.setting.Setting;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public abstract class Module {
    private final List<Setting<?>> settings = new ArrayList<>();

    protected final <T extends Setting<?>> T registerSetting(T setting) {
        settings.add(setting);
        return setting;
    }

    public final List<Setting<?>> settings() {
        return Collections.unmodifiableList(settings);
    }

     
    public boolean configurationOnly() { return false; }
    public boolean actionOnly() { return false; }
    private final String name;
    private final String description;
    private final Category category;
    private final boolean enabledByDefault;

    private int key;
    private volatile boolean enabled;

    protected Module(String name, String description, Category category, int key,
                     boolean enabledByDefault) {
        this.name = name;
        this.description = description;
        this.category = category;
        this.key = key;
        this.enabledByDefault = enabledByDefault;

    }
    protected Module(String name, String description, Category category) {
        this(name, description, category, GLFW.GLFW_KEY_UNKNOWN, false);
    }


     

    protected void onEnable() {
    }
    protected void onDisable() {

    }



    public final void toggle() {
        setEnabled(!enabled);
    }

    public final void setEnabled(boolean enabled) {
        if (this.enabled == enabled) {
            return;
        }
        this.enabled = enabled;

        if (enabled) {
            Logic.events().register(this);    
            onEnable();
        } else {
            onDisable();
            Logic.events().unregister(this);  
        }
        Logic.events().post(new ModuleToggleEvent(this, enabled));
    }
    public final String name() {
        return name;
    }

    public final String description() {
        return description;
    }

    public final Category category() {
        return category;
    }

    public final boolean enabled() {
        return enabled;
    }

    public final boolean enabledByDefault() {
        return enabledByDefault;
    }

    public final int key() {
        return key;
    }

    public final void setKey(int key) {
        this.key = key;
    }

    protected final Minecraft mc() {
        return Minecraft.getInstance();
    }
}



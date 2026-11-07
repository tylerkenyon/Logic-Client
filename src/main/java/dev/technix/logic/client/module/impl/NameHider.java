package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.NameMask;
import dev.technix.logic.client.setting.MultiSelectSetting;
import dev.technix.logic.client.setting.StringSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.util.FormattedCharSequence;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class NameHider extends Module {
    private final MultiSelectSetting modes = registerSetting(new MultiSelectSetting(
        "Modes", "Choose whose names to hide locally.", List.of("Self"), "Self", "Others"));
    private final StringSetting selfName = registerSetting(new StringSetting("Self name", "Replacement for your displayed name.", "You"));
    private volatile NameMask mask = new NameMask(Map.of());

    public NameHider() {
        super("Name Hider", "Replace player names in displayed text.", Category.RENDER);
        selfName.visibleWhen(() -> modes.selected("Self"));
    }

    @Override protected void onEnable() { refresh(); }
    @Override protected void onDisable() { mask = new NameMask(Map.of()); }
    @Subscribe private void tick(ClientTickEvent event) { refresh(); }

    private void refresh() {
        var mc = mc();
        String self = mc.getUser().getName().toLowerCase(Locale.ROOT);
        Map<String, String> replacements = new HashMap<>();
        if (modes.selected("Others") && mc.getConnection() != null) {
            for (var info : mc.getConnection().getOnlinePlayers()) {
                String name = info.getProfile().name().toLowerCase(Locale.ROOT);
                if (!name.equals(self)) replacements.put(name, "Player");
            }
        }
        if (modes.selected("Self")) replacements.put(self, selfName.get().isBlank() ? "You" : selfName.get());
        if (!mask.matches(replacements)) mask = new NameMask(replacements);
    }

    public Object replace(Object text) {
        if (!enabled()) return text;
        NameMask current = mask;
        if (text instanceof String string) return current.replace(string);
        if (text instanceof FormattedCharSequence sequence) return current.replace(sequence);
        if (text instanceof FormattedText formatted) return current.replace(formatted);
        return text;
    }
}

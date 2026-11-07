package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.gui.GuiMotion;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.Render2DEvent;
import dev.technix.mica.api.FontFace;
import imgui.ImGui;
import java.util.*;
import org.lwjgl.glfw.GLFW;

public final class ArraylistModule extends Module {
    public final ModeSetting layout = registerSetting(new ModeSetting("Layout", "", "Panel", "Panel", "Rows", "Text"));
    public final ModeSetting sort = registerSetting(new ModeSetting("Sort", "", "Width", "Width", "Alphabetical"));
    public final BooleanSetting showModes = registerSetting(new BooleanSetting("Show modes", "Dimmed mode and placement-rate labels.", true));
    public final BooleanSetting animations = registerSetting(new BooleanSetting("Animations", "Smooth entry, removal and reordering.", true));
    public final BooleanSetting belowHud = registerSetting(new BooleanSetting("Below HUD", "Keep the list below the Interface widgets.", true));
    public final NumberSetting scale = registerSetting(new NumberSetting("Scale", "", 1, 0.75, 2, 0.05));
    public final NumberSetting yOffset = registerSetting(new NumberSetting("Y offset", "", 14, 0, 400, 1));
    public final NumberSetting margin = registerSetting(new NumberSetting("Right margin", "", 14, 0, 200, 1));
    public final NumberSetting spacing = registerSetting(new NumberSetting("Spacing", "", 3, 0, 20, 1));
    public final NumberSetting opacity = registerSetting(new NumberSetting("Background opacity", "", 90, 0, 100, 1));
    public final BooleanSetting accent = registerSetting(new BooleanSetting("Accent line", "A thin accent at the right edge.", false));
    private static final class Row {
        float alpha, space, width, y = Float.NaN;
    }
    private record Entry(Module module, String suffix, float nameWidth, float suffixWidth, float width, Row row) {}
    private final Map<Module, Row> rows = new IdentityHashMap<>();
    private float panelWidth, panelHeight, panelY = Float.NaN;

    public ArraylistModule() { super("Arraylist", "Enabled feature list.", Category.RENDER, GLFW.GLFW_KEY_UNKNOWN, true); }
    @Override protected void onDisable() { resetLayout(); }
    private void resetLayout() { rows.clear(); panelWidth = panelHeight = 0; panelY = Float.NaN; }
    private float ease(float current, float target, float speed, float dt) {
        return animations.get() ? GuiMotion.smooth(current, target, speed, dt) : target;
    }
    private String suffix(Module module) {
        if (!showModes.get()) return "";
        if (module instanceof FastPlace fastPlace) return fastPlace.rateLabel();
        if (module instanceof Backtrack backtrack) return backtrack.statusLabel();
        if (module instanceof Blink blink) return blink.statusLabel();
        return module.settings().stream().filter(ModeSetting.class::isInstance).map(ModeSetting.class::cast)
            .map(ModeSetting::get).findFirst().orElse("");
    }

    @Subscribe(priority = -10) private void onRender(Render2DEvent event) {
        if (mc().player == null || mc().level == null) { resetLayout(); return; }
        var context = event.context();
        var ui = LogicClient.MODULES.get(InterfaceModule.class);
        float s = scale.get().floatValue(), pad = Math.round(10 * s), gap = Math.round(spacing.get().floatValue() * s);
        float dt = context.deltaTime(), rowHeight = Math.max(12, Math.round(14 * s)) + Math.round(5 * s) * 2;
        int size = Math.max(12, Math.round(14 * s)), detailSize = Math.max(12, Math.round(12 * s));
        boolean panel = layout.get().equals("Panel"), separate = layout.get().equals("Rows");
        float outerPad = panel ? Math.round(6 * s) : 0;
        float right = Math.max(1, context.width() - margin.get().floatValue());
        float targetY = yOffset.get().floatValue();
        if (belowHud.get() && ui.enabled()) targetY = Math.max(targetY, ui.hudBottom() + Math.max(8 * s, gap));
        panelY = Float.isFinite(panelY) ? ease(panelY, targetY, 18, dt) : targetY;
        float top = Math.round(panelY), maxWidth = Math.max(1, right - 8);
        var face = context.renderer().font(FontFace.MEDIUM);
        var detailFace = context.renderer().font(FontFace.REGULAR);
        var font = face != null && face.imFont() != null ? face.imFont() : ImGui.getFont();
        var detailFont = detailFace != null && detailFace.imFont() != null ? detailFace.imFont() : font;
        var entries = new ArrayList<Entry>();
        ImGui.pushFont(font, size);
        try {
            for (Module module : LogicClient.MODULES.all()) {
                if (module == this || module instanceof ClickGui || module.category() == Category.CONFIGURATION || module.configurationOnly() || module.actionOnly()) continue;
                Row row = rows.computeIfAbsent(module, ignored -> new Row());
                float target = module.enabled() ? 1 : 0;
                 
                row.alpha = ease(row.alpha, target, module.enabled() ? 12 : 20, dt);
                row.space = ease(row.space, target, module.enabled() ? 24 : 10, dt);
                if (row.alpha == 0 && row.space == 0) { row.y = Float.NaN; continue; }
                String suffix = suffix(module);
                float nameWidth = (float)Math.ceil(ImGui.calcTextSizeX(module.name()));
                float detailWidth = suffix.isEmpty() ? 0 : (float)Math.ceil(detailFont.calcTextSizeAX(detailSize, Float.MAX_VALUE, 0, suffix));
                float width = Math.min(maxWidth, nameWidth + detailWidth + (suffix.isEmpty() ? 0 : 7 * s) + pad * 2 + (accent.get() ? 3 * s : 0));
                row.width = ease(row.width, width, 20, dt);
                entries.add(new Entry(module, suffix, nameWidth, detailWidth, width, row));
            }
            Comparator<Entry> byName = Comparator.comparing(e -> e.module().name(), String.CASE_INSENSITIVE_ORDER);
            entries.sort(sort.get().equals("Alphabetical") ? byName : Comparator.comparingDouble(Entry::width).reversed().thenComparing(byName));
            float cursor = outerPad, wantedWidth = 0, brightest = 0, lastSpace = 0;
            for (Entry entry : entries) {
                Row row = entry.row();
                row.y = Float.isFinite(row.y) ? ease(row.y, cursor, 18, dt) : cursor;
                cursor += (rowHeight + gap) * row.space;
                lastSpace = row.space;
                wantedWidth = Math.max(wantedWidth, row.width);
                brightest = Math.max(brightest, row.alpha);
            }
            float wantedHeight = entries.isEmpty() ? 0 : cursor + outerPad - gap * lastSpace;
            panelWidth = ease(panelWidth, wantedWidth, 20, dt);
            panelHeight = ease(panelHeight, wantedHeight, 18, dt);
            float left = Math.round(right - panelWidth), bottom = Math.min(context.height(), top + panelHeight);
            var draw = context.drawList();
            float background = opacity.get().floatValue() / 100;
            if (panel && panelWidth > 0 && bottom > top) {
                draw.addRectFilled(left, top, right, bottom, LogicClient.THEME.color(ui.light(), "bg-primary", background * brightest), LogicClient.THEME.radiusMd * s);
                if (accent.get() && bottom - top > outerPad * 2)
                    draw.addRectFilled(right - 2 * s, top + outerPad, right, bottom - outerPad,
                        LogicClient.THEME.color(ui.light(), "accent", brightest), s);
            }
            draw.pushClipRect(panel ? Math.max(0, left) : 0, Math.max(0, top), right,
                Math.max(Math.max(0, top), panel ? bottom : context.height()), true);
            try {
                for (Entry entry : entries) {
                    Row row = entry.row();
                    if (row.alpha == 0) continue;
                    float y = Math.round(top + row.y), slide = Math.round((1 - row.alpha) * 10 * s);
                    if (y + rowHeight > context.height()) continue;
                    if (separate) draw.addRectFilled(Math.round(right - row.width + slide), y, right + slide, y + rowHeight,
                        LogicClient.THEME.color(ui.light(), "bg-primary", row.alpha * background), LogicClient.THEME.radiusMd * s);
                    if (!panel && accent.get()) draw.addRectFilled(right - 2 * s, y + 5 * s, right, y + rowHeight - 5 * s,
                        LogicClient.THEME.color(ui.light(), "accent", row.alpha), s);
                    float textRight = right - pad - (accent.get() ? 3 * s : 0) + slide;
                    if (!entry.suffix().isEmpty()) {
                        draw.addText(detailFont, detailSize, Math.round(textRight - entry.suffixWidth()), Math.round(y + (rowHeight - detailSize) / 2 - detailSize * 0.1f),
                            LogicClient.THEME.color(ui.light(), "text-muted", row.alpha), entry.suffix());
                        textRight -= entry.suffixWidth() + 7 * s;
                    }
                    draw.addText(font, size, Math.round(textRight - entry.nameWidth()), Math.round(y + (rowHeight - size) / 2 - size * 0.1f),
                        LogicClient.THEME.color(ui.light(), "text-primary", row.alpha), entry.module().name());
                }
            } finally { draw.popClipRect(); }
        } finally { ImGui.popFont(); }
    }
}

package dev.technix.logic.client.gui;

import dev.technix.mica.internal.GameInput;
import com.google.gson.JsonObject;
import dev.technix.logic.Logic;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.client.module.impl.ClickGuiConfig;
import dev.technix.logic.client.module.impl.InterfaceModule;
import dev.technix.logic.client.setting.*;
import dev.technix.logic.event.impl.Render2DEvent;
import dev.technix.mica.api.*;
import imgui.*;
import imgui.flag.ImGuiStyleVar;
import imgui.flag.ImGuiWindowFlags;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
import java.util.*;

public final class ClickGuiRenderer implements OverlayElement {
    private static final float WIDTH = 240, GAP = 12, HEADER = 44;
    private static final Category[] CATEGORIES = {Category.COMBAT, Category.RENDER, Category.MOVEMENT, Category.PLAYER, Category.CONFIGURATION};
    private static final class Panel {
        boolean expanded = true;
        float expansion = 1, scroll, scrollTarget, x = Float.NaN, y = Float.NaN;
    }
    private static final class ModuleView {
        boolean expanded;
        float expansion, hover, enabled;
        ModuleView(Module module) { enabled = module.enabled() ? 1 : 0; }
    }
    private final ClickGui clickGui;
    private final Map<Category, Panel> panels = new EnumMap<>(Category.class);
    private final List<Category> order = new ArrayList<>(List.of(CATEGORIES));
    private final Map<Module, ModuleView> modules = new IdentityHashMap<>();
    private final Map<Setting<?>, Float> animation = new IdentityHashMap<>();
    private Setting<?> expandedMode;
    private Setting<?> dragging;
    private boolean draggingUpper;
    private Panel draggingPanel;
    private float dragOffsetX, dragOffsetY;
    private RenderContext context;
    private ImDrawList draw;
    private ClickGuiConfig options;
    private float originX, originY, scale, mouseX, mouseY, opening, dt, clipTop, clipBottom;
    private boolean leftClick, rightClick, modeClicked, interactive, light;
    private Panel inputPanel;

    public ClickGuiRenderer(ClickGui clickGui) {
        this.clickGui = clickGui;
        for (Category category : CATEGORIES) panels.put(category, new Panel());
    }
    @Override public String name() { return "cgui"; }

    @Override public void render(RenderContext context) {
        Logic.events().post(new Render2DEvent(context));
        this.context = context; draw = context.drawList(); dt = context.deltaTime();
        opening = GuiMotion.smooth(opening, clickGui.open() ? 1 : 0, 16, dt);
        if (!clickGui.open()) { dragging = null; draggingPanel = null; expandedMode = null; }
        if (dragging != null && !dragging.visible()) dragging = null;
        if (expandedMode != null && !expandedMode.visible()) expandedMode = null;
        if (opening == 0) return;
        options = LogicClient.MODULES.get(ClickGuiConfig.class);
        light = LogicClient.MODULES.get(InterfaceModule.class).light();
        scale = Math.min(options.scale.get().floatValue(), (context.width() - 16) / WIDTH);
        if (scale <= 0) return;
        int columns = Math.clamp((int)((context.width() - 32 + GAP * scale) / ((WIDTH + GAP) * scale)), 1, CATEGORIES.length);
        int rows = (CATEGORIES.length + columns - 1) / columns;
        float rowHeight = Math.max(88, (context.height() - 32) / scale / rows);
        originX = Math.max(8, Math.round((context.width() - (columns * (WIDTH + GAP) - GAP) * scale) / 2));
        originY = Math.round(16 + (1 - opening) * 12);
        for (int i = 0; i < CATEGORIES.length; i++) {
            Panel panel = panels.get(CATEGORIES[i]);
            if (!Float.isFinite(panel.x)) panel.x = i % columns * (WIDTH + GAP);
            if (!Float.isFinite(panel.y)) panel.y = i / columns * rowHeight;
            clampPanel(panel);
        }
        interactive = clickGui.open() && opening > 0.8f;
        leftClick = rightClick = modeClicked = false;
        boolean editingText = false;
        for (Module module : LogicClient.MODULES.all()) for (Setting<?> setting : module.settings()) {
            if (setting instanceof StringSetting text && clickGui.isEditing(text)) {
                ModuleView view = modules.get(module);
                editingText = clickGui.open() && text.visible() && view != null && view.expanded
                    && panels.get(module.category()).expanded;
            }
        }
        if (!editingText) clickGui.editText(null);
        if (clickGui.open()) captureInput();
        if (leftClick || rightClick) clickGui.editText(null);
        if (!ImGui.isMouseDown(0)) { dragging = null; draggingPanel = null; }
        if (!options.draggable.get()) draggingPanel = null;
        if (draggingPanel != null) {
            draggingPanel.x = mouseX - dragOffsetX; draggingPanel.y = mouseY - dragOffsetY;
            clampPanel(draggingPanel); leftClick = rightClick = false;
        }
        inputPanel = null;
         
        for (int i = order.size() - 1; i >= 0; i--) {
            Panel panel = panels.get(order.get(i));
            float height = HEADER + Math.min(fullHeight(order.get(i)), viewport(panel)) * panel.expansion;
            if (!inside(panel.x, panel.y, WIDTH, height)) continue;
            inputPanel = panel;
            if (inside(panel.x, panel.y, WIDTH, HEADER)) {
                if (rightClick || leftClick && !options.draggable.get()) {
                    panel.expanded = !panel.expanded; leftClick = rightClick = false;
                } else if (leftClick) {
                    draggingPanel = panel; dragging = null;
                    dragOffsetX = mouseX - panel.x; dragOffsetY = mouseY - panel.y;
                    leftClick = false;
                }
            }
            if (ImGui.isMouseClicked(0) || ImGui.isMouseClicked(1)) {
                Category selected = order.remove(i); order.add(selected);
            }
            break;
        }
        draw.addRectFilled(0, 0, context.width(), context.height(), LogicClient.THEME.color(light, "overlay", options.backgroundOpacity.get().floatValue() / 100 * opening));
        for (Category category : order) drawPanel(category);
        drawWatermark();
        if (clickGui.updateProtectedCursor()) {
            var window = Minecraft.getInstance().getWindow();
            float x = ImGui.getMousePosX() * context.width() / Math.max(1, window.getScreenWidth());
            float y = ImGui.getMousePosY() * context.height() / Math.max(1, window.getScreenHeight());
            var foreground = ImGui.getForegroundDrawList();
            foreground.addTriangleFilled(x, y, x + 4, y + 17, x + 14, y + 12, 0xff000000);
            foreground.addTriangleFilled(x + 1, y + 2, x + 4.5f, y + 14, x + 11, y + 11, 0xffffffff);
        }
        if (leftClick && !modeClicked) expandedMode = null;
    }

    private void drawWatermark() {
        String label = "Logic Client v" + Logic.VERSION + " - by technix";
        int size = Math.max(12, Math.round(14 * scale));
        ImGui.pushFont(font(false), size);
        try {
            float padding = Math.round(10 * scale), width = ImGui.calcTextSizeX(label) + padding * 2;
            float height = size + padding * 2, right = context.width() - 14, bottom = context.height() - 14;
            float left = Math.max(0, right - width), top = Math.max(0, bottom - height);
            draw.addRectFilled(left, top, right, bottom, color("bg-primary", options.panelOpacity.get().floatValue() / 100), LogicClient.THEME.radiusMd * scale);
            draw.addText(font(false), size, Math.round(left + padding), Math.round(top + padding - size * 0.1f), color("text-primary", 1), label);
        } finally { ImGui.popFont(); }
    }

    private void clampPanel(Panel panel) {
        float minX = (8 - originX) / scale, maxX = (context.width() - originX - 8) / scale - WIDTH;
        panel.x = Math.clamp(panel.x, minX, Math.max(minX, maxX));
        panel.y = Math.clamp(panel.y, 0, Math.max(0, (context.height() - originY - 88) / scale));
    }
    private float viewport(Panel panel) {
        float bottom = (context.height() - originY - 12) / scale;
        for (Panel other : panels.values()) {
            if (other != panel && other.y >= panel.y + HEADER + 12
                    && other.x < panel.x + WIDTH && other.x + WIDTH > panel.x)
                bottom = Math.min(bottom, other.y - 12);
        }
        return Math.max(0, Math.min(422, bottom - panel.y - HEADER));
    }
    private List<Module> list(Category category) { return LogicClient.MODULES.inCategory(category).stream().filter(m -> m != clickGui).toList(); }
    private float fullHeight(Category category) {
        float h = 8;
        for (Module module : list(category)) h += 40 + settingsHeight(module) * modules.computeIfAbsent(module, ModuleView::new).expansion;
        return h;
    }

    private void captureInput() {
        int flags = ImGuiWindowFlags.NoDecoration | ImGuiWindowFlags.NoBackground | ImGuiWindowFlags.NoMove
            | ImGuiWindowFlags.NoSavedSettings | ImGuiWindowFlags.NoScrollWithMouse | ImGuiWindowFlags.NoNav;
        ImGui.setNextWindowPos(0, 0); ImGui.setNextWindowSize(context.width(), context.height());
        ImGui.pushStyleVar(ImGuiStyleVar.WindowPadding, 0, 0); ImGui.pushStyleVar(ImGuiStyleVar.WindowBorderSize, 0);
        try {
            if (ImGui.begin("##dropdown-input", flags)) {
                ImGui.setCursorScreenPos(0, 0); ImGui.invisibleButton("##surface", context.width(), context.height());
                leftClick = interactive && ImGui.isItemClicked(0); rightClick = interactive && ImGui.isItemClicked(1);
            }
            ImGui.end();
        } finally { ImGui.popStyleVar(2); }
        var window = Minecraft.getInstance().getWindow();
        mouseX = (ImGui.getMousePosX() * context.width() / Math.max(1, window.getScreenWidth()) - originX) / scale;
        mouseY = (ImGui.getMousePosY() * context.height() / Math.max(1, window.getScreenHeight()) - originY) / scale;
    }

    private void drawPanel(Category category) {
        Panel panel = panels.get(category);
        float x = panel.x, header = panel.y, body = header + HEADER;
        var list = list(category);
        panel.expansion = GuiMotion.smooth(panel.expansion, panel.expanded ? 1 : 0, 16, dt);
        for (Module module : list) {
            ModuleView view = modules.computeIfAbsent(module, ModuleView::new);
            view.expansion = GuiMotion.smooth(view.expansion, view.expanded ? 1 : 0, 16, dt);
            view.enabled = GuiMotion.smooth(view.enabled, module.enabled() ? 1 : 0, 18, dt);
            for (Setting<?> setting : module.settings()) {
                float target = setting instanceof BooleanSetting bool ? (bool.get() ? 1 : 0) : (setting instanceof ModeSetting || setting instanceof MultiSelectSetting) && expandedMode == setting ? 1 : 0;
                animation.put(setting, GuiMotion.smooth(animation.getOrDefault(setting, target), target, 18, dt));
            }
        }
        float fullHeight = fullHeight(category), visibleHeight = Math.min(fullHeight, viewport(panel)) * panel.expansion;
        float maxScroll = Math.max(0, fullHeight - viewport(panel));
        boolean active = interactive && inputPanel == panel && draggingPanel == null;
        if (active && panel.expanded && dragging == null && inside(x, body, WIDTH, visibleHeight)) panel.scrollTarget -= ImGui.getIO().getMouseWheel() * 36;
        panel.scrollTarget = Math.clamp(panel.scrollTarget, 0, maxScroll);
        panel.scroll = GuiMotion.smooth(panel.scroll, panel.scrollTarget, 20, dt);
        float alpha = options.panelOpacity.get().floatValue() / 100;
        rect(x, header, WIDTH, HEADER + visibleHeight, LogicClient.THEME.radiusMd, "bg-primary", alpha);
        rect(x, header, WIDTH, HEADER, LogicClient.THEME.radiusMd, active && inside(x, header, WIDTH, HEADER) ? "bg-elevated" : "bg-secondary", alpha);
        text(category.displayName(), x + 14, header + 14, 15, true, "text-primary");
        textRight(panel.expanded ? "-" : "+", x + WIDTH - 14, header + 14, 14, "text-secondary");
        clipTop = body; clipBottom = body + visibleHeight;
        draw.pushClipRect(px(x), py(clipTop), px(x + WIDTH), py(clipBottom), true);
        try {
            float y = body + 4 - panel.scroll;
            for (Module module : list) {
                ModuleView view = modules.get(module);
                drawModule(module, view, x, y, active && panel.expanded && panel.expansion > 0.98f);
                y += 40 + settingsHeight(module) * view.expansion;
            }
        } finally { draw.popClipRect(); }
        if (maxScroll > 0 && panel.expansion > 0.01f) {
            float thumb = Math.max(18, visibleHeight * visibleHeight / fullHeight);
            rect(x + WIDTH - 4, body + panel.scroll / maxScroll * (visibleHeight - thumb), 2, thumb, 1, "scrollbar", 1);
        }
    }

    private void drawModule(Module module, ModuleView view, float x, float y, boolean active) {
        boolean hover = active && bodyHit(x + 6, y, WIDTH - 12, 36);
        view.hover = GuiMotion.smooth(view.hover, hover ? 1 : 0, 20, dt);
        rect(x + 6, y, WIDTH - 12, 36, LogicClient.THEME.radiusSm, "bg-elevated", view.hover * options.panelOpacity.get().floatValue() / 100);
        boolean bindable = !module.configurationOnly() && !module.actionOnly();
        float labelRight = x + WIDTH - 65;
        if (bindable && view.expanded) {
            float bindRight = x + WIDTH - 49;
            float bindLeft = Math.min(x + 14 + textWidth(module.name(), 14) + 10, bindRight - 84);
            labelRight = drawBind(module, module.key(), bindLeft, bindRight, y + 7, active) - 8;
        } else if (clickGui.isBinding(module)) clickGui.startBinding(module);
        if (hover && leftClick) {
            if (module.configurationOnly()) view.expanded = !view.expanded; else module.toggle();
            leftClick = false;
        } else if (hover && rightClick) {
            view.expanded = !view.expanded; dragging = null; expandedMode = null; rightClick = false;
        }
        rowLabel(module.name(), x + 14, y + 11, labelRight, 14,
            module.enabled() || module.configurationOnly() ? "text-primary" : "text-secondary");
        if (module.actionOnly()) textRight("Unload", x + WIDTH - 15, y + 11, 13, "text-muted");
        else if (module.configurationOnly()) textRight(view.expanded ? "-" : "+", x + WIDTH - 15, y + 11, 14, "text-secondary");
        else drawSwitch(x + WIDTH - 41, y + 10, view.enabled);
        float top = Math.max(clipTop, y + 38), bottom = Math.min(clipBottom, y + 38 + settingsHeight(module) * view.expansion);
        draw.pushClipRect(px(x + 8), py(top), px(x + WIDTH - 8), py(Math.max(top, bottom)), true);
        float parentTop = clipTop, parentBottom = clipBottom; clipTop = top; clipBottom = bottom;
        try {
            float row = y + 38;
            for (Setting<?> setting : module.settings()) {
                if (!setting.visible()) continue;
                drawSetting(setting, x, row, active && view.expanded && view.expansion > 0.98f);
                row += settingHeight(setting);
            }
        } finally { clipTop = parentTop; clipBottom = parentBottom; draw.popClipRect(); }
    }
    private float settingsHeight(Module module) { float h = 4; for (Setting<?> setting : module.settings()) h += settingHeight(setting); return h; }
    private float settingHeight(Setting<?> setting) {
        if (!setting.visible()) return 0;
        if (setting instanceof ModeSetting || setting instanceof MultiSelectSetting)
            return 64 + animation.getOrDefault(setting, 0f) * dropdownOptions(setting).size() * 29;
        return setting instanceof NumberSetting || setting instanceof RangeSetting || setting instanceof StringSetting ? 60 : 38;
    }
    private void drawSetting(Setting<?> setting, float x, float y, boolean active) {
        if (y >= clipBottom || y + settingHeight(setting) <= clipTop) return;
        float left = x + 16, right = x + WIDTH - 16;
        if (setting instanceof KeybindSetting bind) {
            float bindLeft = drawBind(bind, bind.get(), right - 84, right, y + 8, active);
            rowLabel(setting.name(), left, y + 11, bindLeft - 6, 13, "text-secondary");
        } else text(setting.name(), left, y + 11, 13, false, "text-secondary");
        if (setting instanceof BooleanSetting bool) {
            if (active && leftClick && bodyHit(left, y + 2, right - left, 32)) { bool.toggle(); leftClick = false; }
            drawSwitch(right - 25, y + 10, animation.getOrDefault(bool, bool.get() ? 1f : 0f));
        } else if (setting instanceof NumberSetting number) {
            textRight(number.valueLabel(), right, y + 11, 13, "text-primary");
            float width = right - left;
            if (active && leftClick && bodyHit(left - 3, y + 29, width + 6, 23)) { dragging = number; leftClick = false; }
            if (active && dragging == number && ImGui.isMouseDown(0)) number.set(number.min() + Math.clamp((mouseX - left) / width, 0, 1) * (number.max() - number.min()));
            float fraction = (float)((number.get() - number.min()) / (number.max() - number.min()));
            rect(left, y + 40, width, 3, 1.5f, "disabled", 1);
            rect(left, y + 40, width * fraction, 3, 1.5f, "accent", 1);
            draw.addCircleFilled(px(left + width * fraction), py(y + 41), 3.5f * scale, color("text-primary", 1));
        } else if (setting instanceof RangeSetting range) {
            textRight(range.valueLabel(), right, y + 11, 13, "text-primary");
            float width = right - left;
            float low = (float)((range.low() - range.min()) / (range.max() - range.min()));
            float high = (float)((range.high() - range.min()) / (range.max() - range.min()));
            if (active && leftClick && bodyHit(left - 3, y + 29, width + 6, 23)) {
                float clicked = (mouseX - left) / width;
                dragging = range;
                draggingUpper = Math.abs(clicked - high) < Math.abs(clicked - low) || low == high && clicked > low;
                leftClick = false;
            }
            if (active && dragging == range && ImGui.isMouseDown(0)) {
                double value = range.min() + Math.clamp((mouseX - left) / width, 0, 1) * (range.max() - range.min());
                if (draggingUpper) range.setHigh(value); else range.setLow(value);
                low = (float)((range.low() - range.min()) / (range.max() - range.min()));
                high = (float)((range.high() - range.min()) / (range.max() - range.min()));
            }
            rect(left, y + 40, width, 3, 1.5f, "disabled", 1);
            rect(left + width * low, y + 40, width * (high - low), 3, 1.5f, "accent", 1);
            draw.addCircleFilled(px(left + width * low), py(y + 41), 3.5f * scale, color("text-primary", 1));
            draw.addCircleFilled(px(left + width * high), py(y + 41), 3.5f * scale, color("text-primary", 1));
        } else if (setting instanceof StringSetting text) {
            boolean editing = clickGui.isEditing(text);
            if (active && leftClick && bodyHit(left, y + 29, right - left, 27)) {
                clickGui.editText(text);
                editing = true;
                leftClick = false;
            }
            rect(left, y + 29, right - left, 27, LogicClient.THEME.radiusSm, editing ? "bg-elevated" : "bg-secondary", 1);
            String value = text.get();
            if (editing) value = value.substring(0, clickGui.textCursor()) + "|" + value.substring(clickGui.textCursor());
            float offset = editing ? Math.max(0, textWidth(value.substring(0, clickGui.textCursor() + 1), 13) - (right - left - 20)) : 0;
            draw.pushClipRect(px(left + 4), py(Math.max(clipTop, y + 29)), px(right - 4), py(Math.min(clipBottom, y + 56)), true);
            try { text(value, left + 8 - offset, y + 36, 13, false, "text-primary"); }
            finally { draw.popClipRect(); }
        } else if (setting instanceof ModeSetting || setting instanceof MultiSelectSetting) {
            var choices = dropdownOptions(setting);
            boolean multiple = setting instanceof MultiSelectSetting;
            if (active && leftClick && bodyHit(left, y + 29, right - left, 27)) {
                expandedMode = expandedMode == setting ? null : setting; modeClicked = true; leftClick = false;
            }
            rect(left, y + 29, right - left, 27, LogicClient.THEME.radiusSm, "bg-secondary", options.panelOpacity.get().floatValue() / 100);
            text(multiple ? ((MultiSelectSetting)setting).summary() : ((ModeSetting)setting).get(), left + 8, y + 36, 13, false, "text-primary");
            textRight(expandedMode == setting ? "-" : "+", right - 8, y + 36, 13, "text-muted");
            float expansion = animation.getOrDefault(setting, 0f), top = Math.max(clipTop, y + 62);
            draw.pushClipRect(px(left), py(top), px(right), py(Math.max(top, Math.min(clipBottom, y + 62 + choices.size() * 29 * expansion))), true);
            try {
                for (int i = 0; i < choices.size(); i++) {
                    String option = choices.get(i); float row = y + 62 + i * 29;
                    boolean selected = multiple ? ((MultiSelectSetting)setting).selected(option) : option.equals(setting.get());
                    boolean hover = active && expansion > 0.98f && expandedMode == setting && bodyHit(left, row, right - left, 28);
                    if (hover) rect(left, row, right - left, 28, LogicClient.THEME.radiusSm, "accent-subtle", 1);
                    text(option, left + 8, row + 7, 13, false, selected ? "text-primary" : "text-secondary");
                    if (multiple) drawSwitch(right - 33, row + 7, selected ? 1 : 0);
                    if (hover && leftClick) {
                        if (multiple) ((MultiSelectSetting)setting).toggleOption(option);
                        else { ((ModeSetting)setting).set(option); expandedMode = null; }
                        modeClicked = true; leftClick = false;
                    }
                }
            } finally { draw.popClipRect(); }
        }
    }
    private static List<String> dropdownOptions(Setting<?> setting) {
        return setting instanceof ModeSetting mode ? mode.options() : ((MultiSelectSetting)setting).options();
    }

    private float drawBind(Object target, int key, float areaLeft, float areaRight, float y, boolean active) {
        String idleLabel = key == GLFW.GLFW_KEY_UNKNOWN ? "Bind"
            : GameInput.describe(key, target instanceof KeybindSetting && key <= GLFW.GLFW_MOUSE_BUTTON_LAST).getDisplayName().getString();
        boolean listening = clickGui.isBinding(target);
        String label = listening ? "Press key" : idleLabel;
        float width = Math.clamp(textWidth(label, 12) + 10, 32, 84);
        float left = (areaLeft + areaRight - width) / 2;
        boolean hover = active && bodyHit(left, y, width, 22);
        if (hover && leftClick) {
            clickGui.startBinding(target);
            leftClick = false;
            listening = clickGui.isBinding(target);
            label = listening ? "Press key" : idleLabel;
            width = Math.clamp(textWidth(label, 12) + 10, 32, 84);
            left = (areaLeft + areaRight - width) / 2;
        }
        float right = left + width;
        if (hover || listening) rect(left, y, width, 22, LogicClient.THEME.radiusSm, "accent-subtle", 1);
        draw.pushClipRect(px(left), py(y), px(right), py(y + 22), true);
        float textHeight = Math.max(12, Math.round(12 * scale)) / scale;
        try { text(label, left + (width - textWidth(label, 12)) / 2, y + (22 - textHeight) / 2,
            12, false, listening || hover ? "text-primary" : "text-muted"); }
        finally { draw.popClipRect(); }
        return left;
    }

    private void rowLabel(String value, float x, float y, float right, int size, String token) {
        draw.pushClipRect(px(x), py(y), px(right), py(y + 20), true);
        try { text(value, x, y, size, false, token); }
        finally { draw.popClipRect(); }
    }

    private void drawSwitch(float x, float y, float amount) {
        rect(x, y, 25, 14, 7, amount > 0.5f ? "accent" : "disabled", options.panelOpacity.get().floatValue() / 100);
        float left = px(x), right = px(x + 25), top = py(y), bottom = py(y + 14);
        float radius = 4 * scale;
        draw.addCircleFilled(left + 7 * scale + (right - left - 14 * scale) * amount,
            (top + bottom) / 2, radius, color(amount > 0.5f ? "text-primary" : "text-secondary", 1));
    }
    private boolean bodyHit(float x, float y, float width, float height) { return interactive && draggingPanel == null && mouseY >= clipTop && mouseY < clipBottom && inside(x, y, width, height); }
    private boolean inside(float x, float y, float width, float height) { return interactive && mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height; }
    private float px(float x) { return Math.round(originX + x * scale); }
    private float py(float y) { return Math.round(originY + y * scale); }
    private int color(String token, float alpha) { return LogicClient.THEME.color(light, token, alpha * opening); }
    private void rect(float x, float y, float width, float height, float radius, String token, float alpha) {
        if (width > 0 && height > 0) draw.addRectFilled(px(x), py(y), px(x + width), py(y + height), color(token, alpha), radius * scale);
    }
    private imgui.ImFont font(boolean bold) {
        var face = context.renderer().font(bold ? FontFace.BOLD : FontFace.REGULAR);
        return face != null && face.imFont() != null ? face.imFont() : ImGui.getFont();
    }
    private void text(String value, float x, float y, int size, boolean bold, String token) {
        draw.addText(font(bold), Math.max(12, Math.round(size * scale)), px(x), py(y), color(token, 1), value);
    }
    private void textRight(String value, float right, float y, int size, String token) {
        text(value, right - textWidth(value, size), y, size, false, token);
    }
    private float textWidth(String value, int size) {
        ImGui.pushFont(font(false), Math.max(12, Math.round(size * scale)));
        try { return ImGui.calcTextSizeX(value) / scale; } finally { ImGui.popFont(); }
    }

    public JsonObject saveLayout() {
        JsonObject root = new JsonObject();
        for (Category category : CATEGORIES) {
            Panel panel = panels.get(category); JsonObject entry = new JsonObject();
            if (Float.isFinite(panel.x) && Float.isFinite(panel.y)) { entry.addProperty("x", panel.x); entry.addProperty("y", panel.y); }
            entry.addProperty("expanded", panel.expanded); root.add(category.name(), entry);
        }
        return root;
    }
    public void loadLayout(JsonObject root) {
        for (Category category : CATEGORIES) {
            try {
                if (!root.has(category.name())) continue;
                JsonObject entry = root.getAsJsonObject(category.name()); Panel panel = panels.get(category);
                if (entry.has("x") && entry.has("y")) {
                    float x = entry.get("x").getAsFloat(), y = entry.get("y").getAsFloat();
                    if (Float.isFinite(x) && Float.isFinite(y)) { panel.x = x; panel.y = y; }
                }
                if (entry.has("expanded")) { panel.expanded = entry.get("expanded").getAsBoolean(); panel.expansion = panel.expanded ? 1 : 0; }
            } catch (RuntimeException ignored) {   }
        }
    }
}

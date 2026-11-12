package dev.technix.logic.client.gui;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.impl.InterfaceModule;
import dev.technix.mica.api.FontFace;
import dev.technix.mica.api.OverlayElement;
import dev.technix.mica.api.RenderContext;
import imgui.ImGui;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public final class ToastOverlay implements OverlayElement {
    private static final long LIFETIME = 4_000_000_000L;
    private static final long TRANSITION = 220_000_000L;
    private static final long STAGGER = 140_000_000L;
    private static final int FONT_SIZE = 14;
    private static final float MARGIN = 14, PADDING = 12, HEIGHT = 34, GAP = 6;

    private record Toast(String message, long startedAt) {}

    private final InterfaceModule ui;
    private final Queue<String> pending = new ConcurrentLinkedQueue<>();
    private final List<Toast> active = new ArrayList<>();

    public ToastOverlay(InterfaceModule ui) {
        this.ui = ui;
    }

    public void enqueue(String message) {
        if (ui.showNotifications.get() && message != null && !message.isBlank()) pending.add(message);
    }

    @Override
    public String name() {
        return "toasts";
    }

    @Override
    public void render(RenderContext context) {
        if (active.isEmpty() && pending.isEmpty()) return;
        if (!ui.showNotifications.get()) {
            pending.clear();
            active.clear();
            return;
        }

        long now = System.nanoTime();
        active.removeIf(toast -> now - toast.startedAt() >= LIFETIME);
        float maxWidth = context.width() - 2 * MARGIN;
        int slots = (int) ((context.height() - 2 * MARGIN + GAP) / (HEIGHT + GAP));
        if (maxWidth <= 2 * PADDING || slots <= 0) return;

        if (active.size() < slots && (active.isEmpty() || now - active.getLast().startedAt() >= STAGGER)) {
            String message = pending.poll();
            if (message != null) active.add(new Toast(message, now));
        }

        var face = context.renderer().font(FontFace.MEDIUM);
        var font = face != null && face.imFont() != null ? face.imFont() : ImGui.getFont();
        var draw = context.drawList();
        var theme = LogicClient.THEME;
        boolean light = ui.light();
        float bottom = context.height() - MARGIN;

        for (Toast toast : active) {
            float top = bottom - HEIGHT;
            if (top < MARGIN) break;

            long age = now - toast.startedAt();
            float enter = Math.min(1, age / (float) TRANSITION);
            float exit = Math.clamp(1 - (LIFETIME - age) / (float) TRANSITION, 0, 1);
            float hidden = Math.max(1 - enter, exit);
            float alpha = 1 - hidden * hidden * hidden;

            float width = Math.min(maxWidth, (float) Math.ceil(font.calcTextSizeAX(FONT_SIZE,
                Float.MAX_VALUE, 0, toast.message())) + 2 * PADDING);
            float right = context.width() - MARGIN + (1 - alpha) * (width + MARGIN);
            float left = right - width;
            draw.addRectFilled(left, top, right, bottom, theme.color(light, "bg-primary", .96f * alpha), theme.radiusMd);
            draw.addRect(left, top, right, bottom, theme.color(light, "border", alpha), theme.radiusMd);
            draw.addText(font, FONT_SIZE, left + PADDING, top + (HEIGHT - FONT_SIZE) / 2,
                theme.color(light, "text-primary", alpha), toast.message(), 0,
                left + PADDING, top, right - PADDING, bottom);

            bottom -= (HEIGHT + GAP) * (1 - exit * exit * exit);
        }
    }
}

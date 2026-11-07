package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.Render2DEvent;
import dev.technix.mica.api.FontFace;
import imgui.ImGui;
import org.lwjgl.glfw.GLFW;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;


public final class InterfaceModule extends Module {
    public final BooleanSetting screenshareProtection = registerSetting(new BooleanSetting("Screenshare protection",
        "Exclude all overlays from supported screen capture. Turn off to render them in the game.",
        System.getProperty("os.name", "").startsWith("Windows")));
    public final BooleanSetting fps = registerSetting(new BooleanSetting("FPS", "", true));
    public final BooleanSetting ping = registerSetting(new BooleanSetting("Ping", "", true));
    public final BooleanSetting username = registerSetting(new BooleanSetting("Username", "", true));
    public final BooleanSetting coordinates = registerSetting(new BooleanSetting("Coordinates", "", false));
    public final BooleanSetting server = registerSetting(new BooleanSetting("Server", "", false));
    public final BooleanSetting clock = registerSetting(new BooleanSetting("Clock", "", false));
    public final ModeSetting theme = registerSetting(new ModeSetting("Theme", "", "Dark", "Dark", "Light"));
    public final NumberSetting scale = registerSetting(new NumberSetting("Scale", "", 1, 0.75, 2, 0.05));
    public final NumberSetting yOffset = registerSetting(new NumberSetting("Y offset", "", 14, 0, 400, 1));
    public final NumberSetting spacing = registerSetting(new NumberSetting("Spacing", "", 6, 0, 32, 1));
    public final NumberSetting margin = registerSetting(new NumberSetting("Right margin", "", 14, 0, 200, 1));
    public final NumberSetting padding = registerSetting(new NumberSetting("Padding", "", 10, 4, 20, 1));
    public final NumberSetting opacity = registerSetting(new NumberSetting("Background opacity", "", 100, 0, 100, 1));
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm");
    private float hudBottom;

    public InterfaceModule() { super("Interface", "Status widgets and shared theme.", Category.CONFIGURATION, GLFW.GLFW_KEY_UNKNOWN, true); }
    public boolean light() { return theme.get().equals("Light"); }
    public float hudBottom() { return hudBottom; }
    @Override protected void onDisable() { hudBottom = 0; }

    public static String pingLabel(boolean localServer, int latency) {
        return localServer ? "0 ms" : latency >= 0 ? latency + " ms" : "-- ms";
    }

    @Subscribe private void onRender(Render2DEvent event) {
        hudBottom = 0;
        var mc = mc();
        if (mc.player == null || mc.level == null) return;
        var context = event.context();
        var values = new ArrayList<String>();
        if (fps.get()) values.add(mc.getFps() + " fps");
        if (ping.get()) {
            var connection = mc.getConnection();
            var info = connection == null ? null : connection.getPlayerInfo(mc.player.getUUID());
            int latency = info == null ? -1 : info.getLatency();
            values.add(pingLabel(mc.isLocalServer(), latency));
        }
        if (username.get()) values.add(LogicClient.displayName(mc.getUser().getName()));
        if (coordinates.get()) values.add((int)Math.floor(mc.player.getX()) + ", " + (int)Math.floor(mc.player.getY()) + ", " + (int)Math.floor(mc.player.getZ()));
        if (server.get()) values.add(mc.isLocalServer() ? "Singleplayer" : mc.getCurrentServer() == null ? "Multiplayer" : mc.getCurrentServer().ip);
        if (clock.get()) values.add(LocalTime.now().format(CLOCK));
        if (values.isEmpty()) return;
        float s = scale.get().floatValue(), pad = Math.round(padding.get().floatValue() * s);
        int fontSize = Math.max(12, Math.round(14 * s));
        float gap = Math.round(spacing.get().floatValue() * s), height = fontSize + pad * 2;
        float right = Math.max(1, context.width() - margin.get().floatValue());
        float y = Math.clamp(yOffset.get().floatValue(), 0, Math.max(0, context.height() - height));
        var face = context.renderer().font(FontFace.MEDIUM);
        var font = face != null && face.imFont() != null ? face.imFont() : ImGui.getFont();
        ImGui.pushFont(font, fontSize);
        try {
            float[] widths = new float[values.size()];
            for (int i = 0; i < widths.length; i++) widths[i] = Math.min(right, (float)Math.ceil(ImGui.calcTextSizeX(values.get(i))) + pad * 2);

            int first = 0;
            while (first < widths.length && y + height <= context.height()) {
                int end = first + 1; float total = widths[first];
                while (end < widths.length && total + gap + widths[end] <= right) total += gap + widths[end++];
                float x = Math.round(right - total);
                for (int i = first; i < end; i++) {
                    var draw = context.drawList();
                    draw.addRectFilled(x, Math.round(y), x + widths[i], Math.round(y + height),
                        LogicClient.THEME.color(light(), "bg-primary", opacity.get().floatValue() / 100), Math.min(LogicClient.THEME.radiusMd * s, height / 2));
                    draw.pushClipRect(x, y, x + widths[i], y + height, true);
                    try { draw.addText(font, fontSize, Math.round(x + (widths[i] - ImGui.calcTextSizeX(values.get(i))) / 2), Math.round(y + pad - fontSize * 0.1f), LogicClient.THEME.color(light(), "text-primary", 1), values.get(i)); }
                    finally { draw.popClipRect(); }
                    x += widths[i] + gap;
                }
                first = end; y += height + gap;
                hudBottom = y - gap;
            }
        } finally { ImGui.popFont(); }
    }
}

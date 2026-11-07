package dev.technix.logic.client.gui;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import imgui.ImColor;
import java.util.LinkedHashMap;
import java.util.Map;


public final class UiTheme {
    private final Map<String, String> dark = new LinkedHashMap<>();
    private final Map<String, String> light = new LinkedHashMap<>();
    public float radiusSm = 4, radiusMd = 6, radiusLg = 10;

    public UiTheme() {
        String[] names = {"bg-primary", "bg-secondary", "bg-elevated", "border", "text-primary", "text-secondary", "text-muted", "accent", "accent-hover", "accent-subtle", "success", "danger", "warning", "info", "overlay", "disabled", "scrollbar"};
        String[] colors = {"#2A3140", "#323B48", "#3D4653", "#4A5564", "#E2E8F0", "#A7B3C4", "#8491A4", "#6B8CA7", "#82A3BF", "#394D62", "#6BAF8F", "#D67F8F", "#D4A574", "#6B8CA7", "#00000080", "#4A5564", "#3D4653"};
        for (int i = 0; i < names.length; i++) dark.put(names[i], colors[i]);
        light.putAll(dark);
        light.put("bg-primary", "#EDF1F6"); light.put("bg-secondary", "#E2E8F0");
        light.put("bg-elevated", "#D4DDE8"); light.put("border", "#B5C1CF");
        light.put("text-primary", "#263344"); light.put("text-secondary", "#47596F");
        light.put("text-muted", "#61748B"); light.put("accent", "#486E90");
        light.put("accent-hover", "#365C7D"); light.put("accent-subtle", "#CAD9E8");
        light.put("disabled", "#BAC6D4"); light.put("scrollbar", "#ACBACB");
    }

    public static int parse(String hex, float opacity) {
        if (hex == null || !hex.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?"))
            throw new IllegalArgumentException("Expected #RRGGBB or #RRGGBBAA");
        int r = Integer.parseInt(hex.substring(1, 3), 16), g = Integer.parseInt(hex.substring(3, 5), 16), b = Integer.parseInt(hex.substring(5, 7), 16);
        int a = hex.length() == 9 ? Integer.parseInt(hex.substring(7, 9), 16) : 255;
        return ImColor.rgba(r, g, b, Math.clamp(Math.round(a * opacity), 0, 255));
    }

    public int color(boolean isLight, String token, float opacity) {
        return parse((isLight ? light : dark).getOrDefault(token, "#E2E8F0"), opacity);
    }

    public JsonObject save() {
        JsonObject root = new JsonObject();
        root.add("dark", palette(dark)); root.add("light", palette(light));
        root.addProperty("radius-sm", radiusSm); root.addProperty("radius-md", radiusMd); root.addProperty("radius-lg", radiusLg);
        return root;
    }

    private static JsonObject palette(Map<String, String> colors) {
        JsonObject object = new JsonObject();
        colors.forEach(object::addProperty);
        return object;
    }

    public void load(JsonObject root) {
        loadPalette(root.get("dark"), dark); loadPalette(root.get("light"), light);
        radiusSm = radius(root, "radius-sm", radiusSm); radiusMd = radius(root, "radius-md", radiusMd); radiusLg = radius(root, "radius-lg", radiusLg);
    }

    private static float radius(JsonObject root, String key, float fallback) {
        try { float n = root.get(key).getAsFloat(); return Float.isFinite(n) ? Math.clamp(n, 0, 32) : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static void loadPalette(JsonElement element, Map<String, String> target) {
        if (element == null || !element.isJsonObject()) return;
        for (var entry : element.getAsJsonObject().entrySet()) {
            if (!target.containsKey(entry.getKey())) continue;
            try {
                String value = entry.getValue().getAsString();
                parse(value, 1); target.put(entry.getKey(), value);
            } catch (RuntimeException ignored) {   }
        }
    }
}

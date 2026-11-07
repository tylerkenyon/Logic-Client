package dev.technix.mica;

import dev.technix.mica.api.FontFace;
import dev.technix.mica.api.FontRegistry;
import net.minecraft.resources.Identifier;
import dev.technix.mica.internal.ImGuiFonts;
import imgui.ImGui;
import imgui.type.ImInt;
import java.util.List;

 
public final class FontAtlasCheck {
    public static void main(String[] args) {
        for (int cycle = 0; cycle < 3; cycle++) {
            ImGui.createContext();
            try {
                FontRegistry registry = new FontRegistry(Identifier.fromNamespaceAndPath("mica", "font"));
                registry.add("body-lg", "sf-pro-display-medium.otf", 18);
                ImGuiFonts.load(List.of(registry));
                build();
                ImGuiFonts.reload();
                build();
            } finally { ImGui.destroyContext(); ImGuiFonts.reset(); }
        }
        System.out.println("Font atlas check passed: bundled fonts rasterised, reloaded and recreated.");
    }
    private static void build() {
         
        System.gc();
        byte[][] pressure = new byte[32][];
        for (int i = 0; i < pressure.length; i++) {
            pressure[i] = new byte[256 * 1024];
            java.util.Arrays.fill(pressure[i], (byte) 0xA5);
        }
        ImInt width = new ImInt(), height = new ImInt();
        var pixels = ImGui.getIO().getFonts().getTexDataAsRGBA32(width, height);
        if (pixels == null || width.get() <= 0 || height.get() <= 0) throw new AssertionError("Missing font atlas pixels");
        if (ImGuiFonts.byName(FontFace.REGULAR).imFont() == null) throw new AssertionError("Missing regular font");
    }
}

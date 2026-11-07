package dev.technix.mica.internal;

import dev.technix.mica.api.FontFace;
import dev.technix.mica.api.FontRegistry;
import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImFontConfig;
import imgui.ImGui;
import imgui.internal.ImGuiContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


public final class ImGuiFonts {

    private static final Logger LOGGER = LoggerFactory.getLogger("mica");

    private static final String FONT_PATH = "assets/mica/font/";

    private static final float TEXT_SIZE = 14.0f;
    private static final float LOGO_SIZE = 18.0f;

    
    private static final Map<String, FontFace> FACES = new HashMap<>();

    
    private static final List<FontRegistry> REGISTRIES = new ArrayList<>();

    private static boolean loaded;
    private static long revision;

    public static long revision() { return revision; }

    private ImGuiFonts() {
    }

     




    public static boolean isContextAlive() {
        ImGuiContext context = ImGui.getCurrentContext();
        return context != null && context.isValidPtr();
    }

    
    public static synchronized void load(@org.jetbrains.annotations.NotNull List<FontRegistry> registries) {
        if (!isContextAlive()) {
            return;
        }
        if (loaded) {

            for (FontRegistry registry : registries) {
                if (!REGISTRIES.contains(registry)) {
                    REGISTRIES.add(registry);
                    registry.rasterise(ImGui.getIO().getFonts());
                    for (FontFace face : registry.all()) {
                        FACES.put(face.name(), face);
                    }
                }
            }
            return;
        }
        loaded = true;

        ImFontAtlas atlas = ImGui.getIO().getFonts();
        atlas.addFontDefault();



        for (FontRegistry registry : registries) {
            REGISTRIES.add(registry);
            registry.rasterise(atlas);
            for (FontFace face : registry.all()) {
                FACES.put(face.name(), face);
            }
        }

        FontFace regular = addBundled(atlas, "sf-pro-display-regular.otf", FontFace.REGULAR, TEXT_SIZE);
        FontFace medium = addBundled(atlas, "sf-pro-display-medium.otf", FontFace.MEDIUM, TEXT_SIZE);
        FontFace bold = addBundled(atlas, "sf-pro-display-bold.otf", FontFace.BOLD, TEXT_SIZE);
        FontFace logo = addBundled(atlas, "sf-pro-display-medium.otf", FontFace.LOGO, LOGO_SIZE);

        FACES.put(regular.name(), regular);
        FACES.put(medium.name(), medium);
        FACES.put(bold.name(), bold);
        FACES.put(logo.name(), logo);

        LOGGER.info("Mica fonts loaded: bundled regular={} medium={} bold={} logo={}, user faces={}",
                regular != null, medium != null, bold != null, logo != null,
                countUserFaces());
    }


    public static synchronized void reload() {
        if (!loaded || !isContextAlive()) {
            return;
        }
        ImFontAtlas atlas = ImGui.getIO().getFonts();
        atlas.clear();
        atlas.addFontDefault();

        FACES.clear();
        for (FontRegistry registry : REGISTRIES) {
            registry.rasterise(atlas);
            for (FontFace face : registry.all()) {
                FACES.put(face.name(), face);
            }
        }
        FontFace regular = addBundled(atlas, "sf-pro-display-regular.otf", FontFace.REGULAR, TEXT_SIZE);
        FontFace medium = addBundled(atlas, "sf-pro-display-medium.otf", FontFace.MEDIUM, TEXT_SIZE);
        FontFace bold = addBundled(atlas, "sf-pro-display-bold.otf", FontFace.BOLD, TEXT_SIZE);
        FontFace logo = addBundled(atlas, "sf-pro-display-medium.otf", FontFace.LOGO, LOGO_SIZE);
        FACES.put(regular.name(), regular);
        FACES.put(medium.name(), medium);
        FACES.put(bold.name(), bold);
        FACES.put(logo.name(), logo);
        LOGGER.info("Mica fonts reloaded.");
    }

     




    public static synchronized void reset() {
        FACES.clear();
        REGISTRIES.clear();
        loaded = false;
        revision++;
    }

    private static int countUserFaces() {
        int count = 0;
        for (FontRegistry registry : REGISTRIES) {
            count += registry.all().size();
        }
        return count;
    }

    private static FontFace addBundled(ImFontAtlas atlas, String name, String faceName, float sizePixels) {
        byte[] data = read(FONT_PATH + name);
        if (data == null) {
            return new FontFace(faceName, Math.max(1, (int) Math.round(sizePixels)), null);
        }
        try {
            int rounded = Math.max(1, (int) Math.round(sizePixels));
            return new FontFace(faceName, rounded, addFont(atlas, data, rounded));
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not rasterise bundled {} at {}px", name, sizePixels, exception);
            return new FontFace(faceName, Math.max(1, (int) Math.round(sizePixels)), null);
        }
    }

    public static ImFont addFont(ImFontAtlas atlas, byte[] data, int size) {
        ImFontConfig config = new ImFontConfig();
        Path fontFile = null;
        try {
             
             
            fontFile = Files.createTempFile("mica-font-", ".ttf");
            Files.write(fontFile, data);
            config.setFontDataOwnedByAtlas(true);
            config.setPixelSnapH(true);
            config.setOversampleH(2);
            config.setOversampleV(2);
            ImFont font = atlas.addFontFromFileTTF(fontFile.toString(), size, config);
            if (font == null) throw new IllegalArgumentException("Could not load font at " + size + "px");
            revision++;
            return font;
        } catch (IOException failure) {
            throw new UncheckedIOException("Could not stage font data for native loading", failure);
        } finally {
            config.destroy();
            if (fontFile != null) {
                try { Files.deleteIfExists(fontFile); }
                catch (IOException failure) { LOGGER.warn("Could not remove temporary font file {}", fontFile, failure); }
            }
        }
    }

    private static byte[] read(String resource) {
        try (InputStream stream = ImGuiFonts.class.getClassLoader().getResourceAsStream(resource)) {
            if (stream == null) {
                LOGGER.warn("Font resource missing: {}", resource);
                return null;
            }
            return stream.readAllBytes();
        } catch (IOException exception) {
            LOGGER.warn("Could not read font resource {}", resource, exception);
            return null;
        }
    }

    @org.jetbrains.annotations.NotNull
    public static FontFace regular() {
        return FACES.get(FontFace.REGULAR);
    }

    @org.jetbrains.annotations.NotNull
    public static FontFace medium() {
        return FACES.get(FontFace.MEDIUM);
    }

    @org.jetbrains.annotations.NotNull
    public static FontFace bold() {
        return FACES.get(FontFace.BOLD);
    }

    @org.jetbrains.annotations.NotNull
    public static FontFace logo() {
        return FACES.get(FontFace.LOGO);
    }


    @org.jetbrains.annotations.Nullable
    public static FontFace byName(@org.jetbrains.annotations.NotNull String name) {
        return FACES.get(name);
    }


    @org.jetbrains.annotations.NotNull
    public static Collection<FontFace> all() {
        return Collections.unmodifiableCollection(FACES.values());
    }


    public static boolean push(@org.jetbrains.annotations.Nullable FontFace face) {
        if (face == null || face.imFont() == null) {
            return false;
        }
        ImGui.pushFont(face.imFont(), face.pixelSize());
        return true;
    }


    public static void pop(boolean pushed) {
        if (pushed) {
            ImGui.popFont();
        }
    }
}

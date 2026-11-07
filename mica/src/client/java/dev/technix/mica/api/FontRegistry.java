package dev.technix.mica.api;

import dev.technix.mica.internal.FontLoader;
import dev.technix.mica.internal.ImGuiFonts;
import imgui.ImFont;
import imgui.ImFontAtlas;
import imgui.ImGui;
import net.minecraft.resources.Identifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


public final class FontRegistry {

    private static final Logger LOGGER = LoggerFactory.getLogger("mica");

    private final Identifier root;
    private final Map<String, FontFace> faces = new LinkedHashMap<>();
    private final Map<String, FontSource> sources = new LinkedHashMap<>();

    private record FontSource(byte[] data, int pixelSize) {
    }

    public FontRegistry(@NotNull Identifier root) {
        this.root = root;
    }

    @NotNull
    public Identifier root() {
        return root;
    }

    @Nullable
    public FontFace add(@NotNull String name, @NotNull String fileName, float sizePixels) {
        Identifier resourceLocation = Identifier.fromNamespaceAndPath(root.getNamespace(),
                root.getPath() + "/" + fileName);
        byte[] data = FontLoader.read(resourceLocation);
        if (data == null) {
            return null;
        }
        int requestedSize = Math.max(1, (int) Math.round(sizePixels));
        ImFont imFont = rasteriseIfAtlasAlive(data, requestedSize);
        FontFace face = new FontFace(name, requestedSize, imFont);
        faces.put(name, face);
        sources.put(name, new FontSource(data, requestedSize));
        return face;
    }

    @Nullable
    private static ImFont rasteriseIfAtlasAlive(byte[] data, int size) {
        if (!ImGuiFonts.isContextAlive()) {
            return null;
        }
        try {
            return ImGuiFonts.addFont(ImGui.getIO().getFonts(), data, size);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not rasterise font at {}px", size, exception);
            return null;
        }
    }

     





    public void rasterise(@NotNull ImFontAtlas atlas) {
        for (Map.Entry<String, FontSource> entry : sources.entrySet()) {
            String name = entry.getKey();
            FontSource source = entry.getValue();
            try {
                ImFont imFont = ImGuiFonts.addFont(atlas, source.data(), source.pixelSize());
                faces.put(name, new FontFace(name, source.pixelSize(), imFont));
            } catch (RuntimeException exception) {
                LOGGER.warn("Could not rasterise {} at {}px", name, source.pixelSize(), exception);
            }
        }
    }

    @Nullable
    public FontFace get(@NotNull String name) {
        return faces.get(name);
    }

    @NotNull
    public Collection<FontFace> all() {
        List<FontFace> snapshot = new ArrayList<>(faces.values());
        return Collections.unmodifiableList(snapshot);
    }

    public void commitPendingFaces() {
        if (faces.isEmpty()) {
            return;
        }
        ImGuiFonts.reload();
    }
}

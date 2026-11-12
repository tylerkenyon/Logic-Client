# mica — public API

Mica draws Dear ImGui overlays on Minecraft 26.2 and 26.3. Use the Mica jar built for the Minecraft version you target.

## Quick start

```java
import dev.technix.mica.api.OverlayRenderer;
import dev.technix.mica.api.OverlayElement;
import dev.technix.mica.api.RenderContext;
import dev.technix.mica.api.MinecraftCompat;
import dev.technix.mica.internal.util.Draw;
import dev.technix.mica.internal.ImGuiFonts;

public final class MyHud implements OverlayElement {
    @Override public String name() { return "MyHud"; }
    @Override public boolean isVisible(RenderContext c) { return true; }
    @Override public void render(RenderContext c) {
        Draw.frostedPanel(c, 12, 12, 200, 40, 6);
        Draw.text(c, ImGuiFonts.regular(), 0xFFFFFFFF, "Hello overlay", 24, 24);
    }
}

// On client start
OverlayRenderer renderer = OverlayRenderer.builder()
        .withMinecraftCompat(MinecraftCompat.current())
        .build();
renderer.registerElement(new MyHud());

// On client stop
renderer.close();
```

The bundle's mixins (`GuiRendererMixin`, `MouseHandlerMixin`, `KeyboardHandlerMixin`) hook
Minecraft's render loop and input automatically once an `OverlayRenderer` is registered
through the platform's `ActiveRenderers` facade.

## Public surface

| Type | Purpose |
|------|---------|
| `OverlayRenderer` | The main entrypoint. Build with `OverlayRenderer.builder()`. Custom render drivers can call `renderFrame()` once per frame. |
| `MinecraftCompat` | The game adapter. `MinecraftCompat.current()` selects the adapter for the Stonecutter build. Provides Vulkan context, host command buffer, texture image views, and atlas sprite lookups. |
| `OverlayElement` | Author-side interface. Implement `name`, `isVisible`, `render(context)`. |
| `RenderContext` | Per-frame draw list, framebuffer size, blur texture ID, elapsed time. |
| `Palette` | Shared colour tokens (`PANEL`, `ACCENT`, `TEXT`, ...). |
| `SpriteBounds` | Normalised UV rect inside a host atlas. |
| `TextureHandle` | Cached registration of a host atlas or stand-alone texture. |
| `TextureFilter` | `LINEAR` (default) or `NEAREST` (HUD pixel art). |
| `VanillaAtlases` | `ITEMS`, `BLOCKS`, `DEFAULT_SKIN` identifiers. |

## Drawing helpers

`dev.technix.mica.internal.util.Draw` provides:

- `frostedPanel(context, x, y, w, h, rounding)` — blur + tint + rim, with fallback when the
  blur is not ready yet.
- `roundedRect`, `roundedRectOutline`, `verticalDivider`, `progressBar`.
- `image(context, handle, x, y, w, h)` and a UV-bearing overload for arbitrary sub-regions.
- `text`, `textVCentered`, `textCentered`, `textWidth`, `textHeight`.

## What is NOT public

Anything under `dev.technix.mica.internal` is private to the platform and may change without
notice. Application code only imports from `dev.technix.mica.api` (and `internal.util.Draw`,
which is treated as a public helper).

## Forward compatibility

Add a Stonecutter target and select its adapter in `MinecraftCompat.current()`.
The bundled render hook uses `GuiRenderer.render()` on 26.2 and the tail of
`GameRenderer.render()` on 26.3.

# Capture exclusion

Mica 0.2 can render into a separate transparent Windows window instead of drawing
over Minecraft's framebuffer. The window is click-through and follows Minecraft.
It is hidden when the game is minimized or unfocused.

```java
OverlayRenderer renderer = OverlayRenderer.builder()
    .withMinecraftCompat(MinecraftCompat.current())
    .withStreamproof(true)
    .build();

// Changes are applied on the next render frame.
renderer.setStreamproof(true);
CaptureStatus status = renderer.captureStatus();
boolean requested = renderer.isStreamproofRequested();

// Explicitly return to drawing into the game's framebuffer.
renderer.setStreamproof(false);
```

For an existing renderer, use `ActiveRenderers.get().setStreamproof(true)` on the
client thread after startup. The bundled initializer also accepts the JVM option
`-Dmica.streamproof=true`. Protection is enabled by default on Windows. Explicitly
set `-Dmica.streamproof=false` or `.withStreamproof(false)` to disable it.

Status is `DISABLED`, `PENDING`, `ACTIVE`, `UNSUPPORTED`, or `FAILED`. ACTIVE means
Windows accepted the exclusion request and its native flag was verified; it cannot prove how a particular recorder
captures content. If initialization fails while requested, Mica hides its overlays
instead of silently drawing them into the game. Disable the request before retrying.
The native exclusion flag is checked before every protected frame. If it is lost,
the window is hidden and rendering fails closed. Unsupported Windows builds or
disabled desktop composition also prevent the protected overlay from starting.

Capture exclusion requires Windows 10 version 2004 or later and desktop composition.
On Minecraft 26.3, protection switches exclusive fullscreen to borderless fullscreen
and restores the selected fullscreen mode when protection ends. On 26.2, use windowed
mode or borderless fullscreen.
The protected window uses OpenGL 3.3 while Minecraft can continue using Vulkan.
Capture exclusion is requested with `SetWindowDisplayAffinity(WDA_EXCLUDEFROMCAPTURE)`.
Game capture normally captures the game without the separate overlay. Display/window
capture behavior depends on the capture API and recorder: test the actual recording.
This is not DRM and cannot prevent every kind of capture.

Text, shapes, controls and ESP use the protected window. Game-framebuffer blur and
Vulkan atlas/raw textures are unavailable in this mode; `hasBlur()` is false,
atlas registration returns empty, and raw Vulkan texture registration throws.
Switching modes rebuilds the ImGui context: reacquire font faces from the renderer
and re-register Vulkan textures after returning to normal mode. Do not cache native
ImGui font, texture or draw-list handles across a mode change.

Reference: [Microsoft SetWindowDisplayAffinity documentation](https://learn.microsoft.com/en-us/windows/win32/api/winuser/nf-winuser-setwindowdisplayaffinity).

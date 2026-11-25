package dev.technix.mica;

import dev.technix.mica.internal.ProtectedOverlay;
import dev.technix.mica.internal.ImGuiFonts;
import dev.technix.mica.api.FontRegistry;
import net.minecraft.resources.Identifier;
import imgui.ImGui;
//? if <26.3 {
import static org.lwjgl.glfw.GLFW.*;
//?} else {
/*import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLEvents.SDL_PumpEvents;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty;
*///?}

 
public final class ProtectedOverlayCheck {
    public static void main(String[] args) {
        //? if <26.3 {
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
        //?} else {
        /*if (!SDL_Init(SDL_INIT_VIDEO)) throw new AssertionError("SDL initialization failed");
        *///?}
        try {
            for (int cycle = 0; cycle < 2; cycle++) {
                ImGui.createContext();
                ImGui.getIO().setIniFilename(null);
                FontRegistry fonts = new FontRegistry(Identifier.fromNamespaceAndPath("mica", "font"));
                fonts.add("body-lg", "sf-pro-display-medium.otf", 18);
                ImGuiFonts.load(java.util.List.of(fonts));
                try (ProtectedOverlay overlay = new ProtectedOverlay()) {
                    overlay.init();
                    overlay.verifyCaptureProtection();
                    if (dev.technix.mica.internal.MinecraftAccess.hasOpenGlContext()) throw new AssertionError("Initialization leaked a GL context");
                    for (int frame = 0; frame < 12; frame++) {
                        if (frame == 3) {
                            ImGuiFonts.reload();
                            ImGui.getIO().getFonts().getTexDataAsRGBA32(new imgui.type.ImInt(), new imgui.type.ImInt());
                        }
                        overlay.newFrame();
                        ImGui.getIO().setDisplaySize(64, 64);
                        ImGui.getIO().setDeltaTime(1f / 60);
                        ImGui.newFrame();
                        
                        int size = 12 + frame;
                        var face = frame % 2 == 0 ? ImGuiFonts.regular() : ImGuiFonts.bold();
                        ImGui.pushFont(face.imFont(), size);
                        ImGui.calcTextSizeX("Interface");
                        ImGui.popFont();
                        ImGui.getBackgroundDrawList().addText(face.imFont(), size, 4, 24, 0xffffffff, "Interface");
                        ImGui.render();
                        assertTextPixels(overlay);
                        overlay.drawFrame();
                        if (dev.technix.mica.internal.MinecraftAccess.hasOpenGlContext()) throw new AssertionError("Rendering leaked a GL context");
                    }
                    assertWindowStacking(overlay);
                     
                    {
                        long handle = overlay.windowHandle();
                        //? if <26.3 {
                        long hwnd = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle);
                        //?} else {
                        /*long hwnd = SDL_GetPointerProperty(SDL_GetWindowProperties(handle), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
                        *///?}
                        var nativeWindow = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(hwnd));
                        //? if >=26.3 {
                        /*var windows = com.sun.jna.platform.win32.User32.INSTANCE;
                        var focusedBefore = windows.GetForegroundWindow();
                        int style = windows.GetWindowLong(nativeWindow, -20);
                        if ((style & 0x08080020) != 0x08080020)
                            throw new AssertionError("Overlay must be non-activating, layered and mouse-transparent");
                        if (!windows.SetWindowPos(nativeWindow, null, 32, 32, 160, 120, 0x0014))
                            throw new AssertionError("Could not position the SDL overlay");
                        windows.ShowWindow(nativeWindow, 4);
                        SDL_PumpEvents();
                        if (!java.util.Objects.equals(focusedBefore, windows.GetForegroundWindow()))
                            throw new AssertionError("Showing the overlay stole foreground focus");
                        overlay.verifyCaptureProtection();
                        *///?}
                        var api = com.sun.jna.Native.load("user32", ProtectedOverlay.CaptureApi.class,
                            com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
                        if (!api.SetWindowDisplayAffinity(nativeWindow, 0)) throw new AssertionError("Cannot simulate exclusion loss");
                        boolean rejected = false;
                        try { overlay.drawFrame(); } catch (IllegalStateException expected) { rejected = true; }
                        //? if <26.3 {
                        boolean visible = glfwGetWindowAttrib(handle, GLFW_VISIBLE) != GLFW_FALSE;
                        //?} else {
                        /*boolean visible = com.sun.jna.platform.win32.User32.INSTANCE.IsWindowVisible(nativeWindow);
                        *///?}
                        if (!rejected || visible) {
                            throw new AssertionError("Overlay must hide and reject rendering when protection is lost");
                        }
                    }
                } finally {
                    ImGui.destroyContext();
                    ImGuiFonts.reset();
                }
            }
        } finally {
            //? if <26.3 {
            glfwTerminate();
            //?} else {
            /*SDL_Quit();
            *///?}
        }
        System.out.println("Protected overlay check passed: native exclusion verified, text rendered, protection loss blocked, contexts restored, windows cleaned up.");
    }
    private static void assertWindowStacking(ProtectedOverlay overlay) {
        //? if <26.3 {
        glfwDefaultWindowHints();
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        long host = glfwCreateWindow(160, 120, "Mica visibility host", 0, 0);
        long covering = glfwCreateWindow(80, 120, "Mica visibility cover", 0, 0);
        //?} else {
        /*long host = SDL_CreateWindow("Mica visibility host", 160, 120, SDL_WINDOW_HIDDEN);
        long covering = SDL_CreateWindow("Mica visibility cover", 80, 120, SDL_WINDOW_HIDDEN);
        *///?}
        try {
            if (host == 0 || covering == 0) throw new AssertionError("Cannot create visibility test windows");
            //? if <26.3 {
            long nativeHost = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(host);
            long nativeCover = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(covering);
            long nativeOverlay = org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(overlay.windowHandle());
            //?} else {
            /*long nativeHost = SDL_GetPointerProperty(SDL_GetWindowProperties(host), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
            long nativeCover = SDL_GetPointerProperty(SDL_GetWindowProperties(covering), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
            long nativeOverlay = SDL_GetPointerProperty(SDL_GetWindowProperties(overlay.windowHandle()), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
            *///?}
            var hostWindow = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(nativeHost));
            var coverWindow = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(nativeCover));
            var overlayWindow = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(nativeOverlay));
            var api = com.sun.jna.Native.load("user32", ProtectedOverlay.CaptureApi.class,
                com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
            if (!api.SetWindowPos(hostWindow, null, 40, 40, 160, 120, 0x50)
                    || !api.SetWindowPos(coverWindow, null, 40, 40, 80, 120, 0x50)) {
                throw new AssertionError("Cannot position visibility test windows");
            }
            for (int frame = 0; frame < 3; frame++) {
                overlay.render(host, true);
                if (!api.IsWindowVisible(overlayWindow) || !overlayWindow.equals(api.GetWindow(hostWindow, 3)))
                    throw new AssertionError("Overlay must sit directly above its visible host");
                boolean coverAbove = false;
                for (var above = api.GetWindow(overlayWindow, 3); above != null; above = api.GetWindow(above, 3)) {
                    if (coverWindow.equals(above)) { coverAbove = true; break; }
                }
                if (!coverAbove) throw new AssertionError("Overlay appeared above the window covering its host");
            }
            if (!api.SetWindowPos(coverWindow, new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(-1L)),
                    40, 40, 80, 120, 0x10)) throw new AssertionError("Cannot make the covering window topmost");
            overlay.render(host, true);
            if ((api.GetWindowLong(overlayWindow, -20) & 0x8) != 0)
                throw new AssertionError("Overlay became topmost underneath a topmost covering window");
            com.sun.jna.platform.win32.User32.INSTANCE.ShowWindow(hostWindow, 7);
            overlay.render(host, true);
            if (api.IsWindowVisible(overlayWindow)) throw new AssertionError("Overlay remained visible for a minimized host");
            com.sun.jna.platform.win32.User32.INSTANCE.ShowWindow(hostWindow, 4);
            overlay.render(host, true);
            if (!api.IsWindowVisible(overlayWindow)) throw new AssertionError("Overlay did not return with its restored host");
            overlay.render(host, false);
            if (api.IsWindowVisible(overlayWindow)) throw new AssertionError("Overlay remained visible for an inactive host");
            com.sun.jna.platform.win32.User32.INSTANCE.ShowWindow(hostWindow, 0);
            overlay.render(host, true);
            if (api.IsWindowVisible(overlayWindow)) throw new AssertionError("Overlay remained visible for a hidden host");
        } finally {
            //? if <26.3 {
            if (covering != 0) glfwDestroyWindow(covering);
            if (host != 0) glfwDestroyWindow(host);
            glfwDefaultWindowHints();
            //?} else {
            /*if (covering != 0) SDL_DestroyWindow(covering);
            if (host != 0) SDL_DestroyWindow(host);
            *///?}
        }
    }

    private static void assertTextPixels(ProtectedOverlay overlay) {
        try {
            var context = ProtectedOverlay.class.getDeclaredMethod("withContext", Runnable.class);
            context.setAccessible(true);
            var backendField = ProtectedOverlay.class.getDeclaredField("backend");
            backendField.setAccessible(true);
            var backend = (imgui.gl3.ImGuiImplGl3)backendField.get(overlay);
            context.invoke(overlay, (Runnable)() -> {
                org.lwjgl.opengl.GL11.glViewport(0, 0, 64, 64);
                org.lwjgl.opengl.GL11.glClearColor(0, 0, 0, 0);
                org.lwjgl.opengl.GL11.glClear(org.lwjgl.opengl.GL11.GL_COLOR_BUFFER_BIT);
                backend.renderDrawData(ImGui.getDrawData());
                java.nio.ByteBuffer pixels = org.lwjgl.BufferUtils.createByteBuffer(64 * 64 * 4);
                org.lwjgl.opengl.GL11.glReadPixels(0, 0, 64, 64, org.lwjgl.opengl.GL11.GL_RGBA, org.lwjgl.opengl.GL11.GL_UNSIGNED_BYTE, pixels);
                int lit = 0;
                for (int i = 0; i < pixels.capacity(); i += 4) if ((pixels.get(i) & 255) > 40) lit++;
                if (lit < 20) throw new AssertionError("Text draw produced no readable glyph pixels: " + lit);
            });
        } catch (ReflectiveOperationException failure) { throw new AssertionError(failure); }
    }

}

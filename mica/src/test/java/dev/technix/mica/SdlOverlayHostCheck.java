package dev.technix.mica;

//? if >=26.3 {
/*import dev.technix.mica.internal.ProtectedOverlay;
import imgui.ImGui;
import org.lwjgl.opengl.GL;
import static org.lwjgl.sdl.SDLInit.*;
import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLEvents.*;
import static org.lwjgl.opengl.GL11.*;
*///?}

/** Exercises the production show/hide path with a separate live host GL context. */
public final class SdlOverlayHostCheck {
    public interface HitTest extends com.sun.jna.win32.StdCallLibrary {
        com.sun.jna.platform.win32.WinDef.HWND WindowFromPoint(com.sun.jna.platform.win32.WinDef.POINT.ByValue point);
    }
    public static void main(String[] args) throws Exception {
        //? if >=26.3 {
        /*if (!SDL_Init(SDL_INIT_VIDEO)) throw new AssertionError("SDL init failed");
        long host = 0, context = 0;
        try {
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, SDL_GL_CONTEXT_DEBUG_FLAG);
            host = SDL_CreateWindow("Mica host regression test", 320, 240, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN);
            context = SDL_GL_CreateContext(host);
            if (host == 0 || context == 0 || !SDL_GL_MakeCurrent(host, context))
                throw new AssertionError("Cannot create host context");
            var hostCaps = GL.createCapabilities();
            glClearColor(.2f, .3f, .4f, 1);
            ImGui.createContext();
            ImGui.getIO().setIniFilename(null);
            try (ProtectedOverlay overlay = new ProtectedOverlay()) {
                overlay.init();
                try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                    var value = stack.mallocInt(1);
                    SDL_GL_GetAttribute(SDL_GL_CONTEXT_FLAGS, value);
                    if (value.get(0) != SDL_GL_CONTEXT_DEBUG_FLAG)
                        throw new AssertionError("Overlay changed host GL creation attributes");
                }
                var windowField = ProtectedOverlay.class.getDeclaredField("window");
                windowField.setAccessible(true);
                long overlayWindow = windowField.getLong(overlay);
                long nativeHandle = org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty(SDL_GetWindowProperties(overlayWindow), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
                var hwnd = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(nativeHandle));
                var user32 = com.sun.jna.platform.win32.User32.INSTANCE;
                var hitTest = com.sun.jna.Native.load("user32", HitTest.class);
                var focusedBefore = user32.GetForegroundWindow();
                for (int frame = 0; frame < 60; frame++) {
                    SDL_PumpEvents();
                    if (frame % 10 == 0) SDL_SetWindowSize(host, 320 + frame, 240 + frame);
                    overlay.newFrame();
                    ImGui.getIO().setDisplaySize(320, 240);
                    ImGui.getIO().setDeltaTime(1f / 60);
                    ImGui.newFrame();
                    ImGui.getBackgroundDrawList().addRectFilled(5, 5, 100, 100, 0xffffffff);
                    ImGui.render();
                    overlay.render(host, frame % 5 != 0);
                    SDL_PumpEvents();
                    overlay.verifyCaptureProtection();
                    if (frame % 5 != 0) {
                        if (!java.util.Objects.equals(focusedBefore, user32.GetForegroundWindow()))
                            throw new AssertionError("Overlay stole foreground focus");
                        var bounds = new com.sun.jna.platform.win32.WinDef.RECT();
                        if (!user32.GetWindowRect(hwnd, bounds)) throw new AssertionError("Overlay HWND disappeared");
                        if (hwnd.equals(hitTest.WindowFromPoint(new com.sun.jna.platform.win32.WinDef.POINT.ByValue(bounds.left + 40, bounds.top + 40))))
                            throw new AssertionError("Overlay intercepts native mouse hit tests");
                        if ((SDL_GetWindowFlags(overlayWindow) & SDL_WINDOW_HIDDEN) != 0)
                            throw new AssertionError("Shown overlay is still hidden in SDL at frame " + frame);
                        try (var stack = org.lwjgl.system.MemoryStack.stackPush()) {
                            var width = stack.mallocInt(1); var height = stack.mallocInt(1);
                            SDL_GetWindowSizeInPixels(overlayWindow, width, height);
                            if (width.get(0) != 320 + frame / 10 * 10 || height.get(0) != 240 + frame / 10 * 10)
                                throw new AssertionError("Overlay framebuffer did not follow host size: " + width.get(0) + "x" + height.get(0));
                        }
                    }
                    if (SDL_GL_GetCurrentWindow() != host || SDL_GL_GetCurrentContext() != context
                            || GL.getCapabilities() != hostCaps)
                        throw new AssertionError("Overlay did not restore the host GL context at frame " + frame);
                    glClear(GL_COLOR_BUFFER_BIT);
                    if (!SDL_GL_SwapWindow(host)) throw new AssertionError("Host stopped presenting");
                }
            } finally { ImGui.destroyContext(); }
            if (SDL_GL_GetCurrentContext() != context) throw new AssertionError("Close lost host context");
            System.out.println("SDL host check passed: 60 frames, resize/show/hide, protection and host context preserved.");
        } finally {
            if (context != 0) SDL_GL_DestroyContext(context);
            if (host != 0) SDL_DestroyWindow(host);
            GL.setCapabilities(null);
            SDL_Quit();
        }
        *///?}
    }
}

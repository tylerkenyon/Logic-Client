package dev.technix.mica.internal;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinNT.OSVERSIONINFOEX;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;
import imgui.ImGui;
import imgui.gl3.ImGuiImplGl3;
import net.minecraft.client.Minecraft;
//? if <26.3 {
import org.lwjgl.glfw.GLFWNativeWin32;
//?} else {
/*import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty;
*///?}
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

//? if <26.3
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

 
public final class ProtectedOverlay implements AutoCloseable {
    public interface CaptureApi extends StdCallLibrary {
        boolean SetWindowDisplayAffinity(HWND window, int affinity);
        boolean GetWindowDisplayAffinity(HWND window, IntByReference affinity);
        int GetWindowLong(HWND window, int index);
        int SetWindowLong(HWND window, int index, int value);
        boolean SetLayeredWindowAttributes(HWND window, int colorKey, byte alpha, int flags);
    }

    public interface VersionApi extends StdCallLibrary {
        int RtlGetVersion(OSVERSIONINFOEX version);
    }

    public interface CompositionApi extends StdCallLibrary {
        int DwmIsCompositionEnabled(IntByReference enabled);
    }

    private long window;
    //? if >=26.3
    /*private long glContext;*/
    private ImGuiImplGl3 backend;
    private GLCapabilities capabilities;
    private boolean shown;
    private CaptureApi captureApi;
    private HWND hwnd;
    private long fontRevision = -1;
    //? if >=26.3
    /*private int lastX = Integer.MIN_VALUE, lastY, lastWidth, lastHeight;*/

    private static void requireSupportedWindows() {
        OSVERSIONINFOEX version = new OSVERSIONINFOEX();
        VersionApi api = Native.load("ntdll", VersionApi.class, W32APIOptions.DEFAULT_OPTIONS);
        if (api.RtlGetVersion(version) != 0 || version.getMajor() < 10 || version.getBuildNumber() < 19041) {
            throw new UnsupportedOperationException("Capture exclusion requires Windows 10 build 19041 or later");
        }
        IntByReference composed = new IntByReference();
        CompositionApi dwm = Native.load("dwmapi", CompositionApi.class, W32APIOptions.DEFAULT_OPTIONS);
        if (dwm.DwmIsCompositionEnabled(composed) != 0 || composed.getValue() == 0) {
            throw new UnsupportedOperationException("Capture exclusion requires desktop composition");
        }
    }

     
    public void verifyCaptureProtection() {
        IntByReference affinity = new IntByReference();
        if (window == 0 || captureApi == null || !captureApi.GetWindowDisplayAffinity(hwnd, affinity)
                || affinity.getValue() != 0x11) {
            if (window != 0) hide();
            shown = false;
            throw new IllegalStateException("Capture exclusion was lost; overlay hidden");
        }
    }

    public void init() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new UnsupportedOperationException("Capture exclusion requires Windows 10 2004 or later");
        }
        requireSupportedWindows();
        //? if <26.3 {
        long previous = glfwGetCurrentContext();
        //?} else {
        /*long previous = SDL_GL_GetCurrentWindow();
        long previousContext = SDL_GL_GetCurrentContext();
        int[] attributes = { SDL_GL_CONTEXT_MAJOR_VERSION, SDL_GL_CONTEXT_MINOR_VERSION,
            SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_FLAGS, SDL_GL_SHARE_WITH_CURRENT_CONTEXT };
        int[] oldAttributes = new int[attributes.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var value = stack.mallocInt(1);
            for (int i = 0; i < attributes.length; i++) {
                if (!SDL_GL_GetAttribute(attributes[i], value))
                    throw new IllegalStateException("Cannot preserve host OpenGL attributes");
                oldAttributes[i] = value.get(0);
            }
        }
        *///?}
        GLCapabilities previousCapabilities = capabilitiesOrNull();
        try {
            //? if <26.3 {
            glfwDefaultWindowHints();
            glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
            glfwWindowHint(GLFW_DECORATED, GLFW_FALSE);
            glfwWindowHint(GLFW_FLOATING, GLFW_TRUE);
            glfwWindowHint(GLFW_FOCUSED, GLFW_FALSE);
            glfwWindowHint(GLFW_FOCUS_ON_SHOW, GLFW_FALSE);
            glfwWindowHint(GLFW_MOUSE_PASSTHROUGH, GLFW_TRUE);
            glfwWindowHint(GLFW_TRANSPARENT_FRAMEBUFFER, GLFW_TRUE);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 3);
            glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 3);
            glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
            window = glfwCreateWindow(64, 64, "Mica overlay", 0, 0);
            if (window == 0) throw new IllegalStateException("Cannot create the protected overlay window");
            if (glfwGetWindowAttrib(window, GLFW_TRANSPARENT_FRAMEBUFFER) != GLFW_TRUE) {
                throw new IllegalStateException("Transparent overlay framebuffers are unavailable");
            }
            hwnd = new HWND(Pointer.createConstant(GLFWNativeWin32.glfwGetWin32Window(window)));
            //?} else {
            /*SDL_GL_SetAttribute(SDL_GL_CONTEXT_MAJOR_VERSION, 3);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_MINOR_VERSION, 3);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_PROFILE_MASK, SDL_GL_CONTEXT_PROFILE_CORE);
            SDL_GL_SetAttribute(SDL_GL_CONTEXT_FLAGS, 0);
            SDL_GL_SetAttribute(SDL_GL_SHARE_WITH_CURRENT_CONTEXT, 0);
            window = SDL_CreateWindow("Mica overlay", 64, 64, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN
                | SDL_WINDOW_BORDERLESS | SDL_WINDOW_ALWAYS_ON_TOP | SDL_WINDOW_NOT_FOCUSABLE | SDL_WINDOW_TRANSPARENT);
            if (window == 0) throw new IllegalStateException("Cannot create the protected overlay window");
            glContext = SDL_GL_CreateContext(window);
            if (glContext == 0 || !SDL_GL_MakeCurrent(window, glContext))
                throw new IllegalStateException("Cannot create the protected OpenGL context");
            long hwndAddress = SDL_GetPointerProperty(SDL_GetWindowProperties(window), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
            if (hwndAddress == 0) throw new IllegalStateException("Cannot find the overlay window handle");
            hwnd = new HWND(Pointer.createConstant(hwndAddress));
            *///?}
            CaptureApi api = Native.load("user32", CaptureApi.class, W32APIOptions.DEFAULT_OPTIONS);
            captureApi = api;
             
            int style = api.GetWindowLong(hwnd, -20);
            Native.setLastError(0);
            int previousStyle = api.SetWindowLong(hwnd, -20, style | 0x80 | 0x08000000 | 0x20);
            if (previousStyle == 0 && Native.getLastError() != 0) {
                throw new IllegalStateException("Cannot configure the overlay window: " + Native.getLastError());
            }
            //? if >=26.3 {
            /*// Windows mouse passthrough requires both TRANSPARENT and LAYERED.
            api.SetWindowLong(hwnd, -20, api.GetWindowLong(hwnd, -20) | 0x00080000);
            if (!api.SetLayeredWindowAttributes(hwnd, 0, (byte) 255, 2)) {
                throw new IllegalStateException("Cannot configure overlay mouse passthrough: " + Native.getLastError());
            }
            *///?}
            if (!api.SetWindowDisplayAffinity(hwnd, 0x11)) {
                throw new IllegalStateException("Windows rejected capture exclusion: " + Native.getLastError());
            }
            verifyCaptureProtection();
            //? if <26.3 {
            glfwMakeContextCurrent(window);
            capabilities = GL.createCapabilities();
            glfwSwapInterval(0);
            //?} else {
            /*capabilities = GL.createCapabilities();
            SDL_GL_SetSwapInterval(0);
            *///?}
            backend = new ImGuiImplGl3();
            if (!backend.init("#version 330 core")) throw new IllegalStateException("Cannot initialize overlay OpenGL");
        } finally {
            //? if <26.3 {
            glfwDefaultWindowHints();
            glfwMakeContextCurrent(previous);
            //?} else {
            /*for (int i = 0; i < attributes.length; i++) SDL_GL_SetAttribute(attributes[i], oldAttributes[i]);
            SDL_GL_MakeCurrent(previous, previousContext);
            *///?}
            GL.setCapabilities(previousCapabilities);
        }
    }

    public void newFrame() {
        withContext(() -> {
             
             
            if (fontRevision != ImGuiFonts.revision() || !ImGui.getIO().getFonts().isBuilt()) {
                backend.destroyFontsTexture();
            }
            backend.newFrame();
            fontRevision = ImGuiFonts.revision();
        });
    }

    public void render() {
        var mc = Minecraft.getInstance();
        render(mc.getWindow().handle(), mc.isWindowActive());
    }

    public void render(long host, boolean hostActive) {
         
        //? if <26.3 {
        boolean visible = hostActive && glfwGetWindowAttrib(host, GLFW_ICONIFIED) == GLFW_FALSE
            && glfwGetWindowMonitor(host) == 0;
        //?} else {
        /*boolean visible = hostActive && (SDL_GetWindowFlags(host) & SDL_WINDOW_MINIMIZED) == 0
            && SDL_GetWindowFullscreenMode(host) == null;
        *///?}
        if (!visible) {
            if (shown) hide();
            shown = false;
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1); var y = stack.mallocInt(1);
            var w = stack.mallocInt(1); var h = stack.mallocInt(1);
            //? if <26.3 {
            glfwGetWindowPos(host, x, y);
            glfwGetWindowSize(host, w, h);
            glfwSetWindowPos(window, x.get(0), y.get(0));
            glfwSetWindowSize(window, Math.max(1, w.get(0)), Math.max(1, h.get(0)));
            //?} else {
            /*SDL_GetWindowPosition(host, x, y);
            SDL_GetWindowSize(host, w, h);
            int width = Math.max(1, w.get(0));
            int height = Math.max(1, h.get(0));
            if (lastX != x.get(0) || lastY != y.get(0) || lastWidth != width || lastHeight != height) {
                lastX = x.get(0);
                lastY = y.get(0);
                lastWidth = width;
                lastHeight = height;
                if (!SDL_SetWindowPosition(window, lastX, lastY) || !SDL_SetWindowSize(window, width, height)) {
                    throw new IllegalStateException("Cannot position the protected overlay");
                }
            }
            *///?}
        }
        //? if <26.3 {
        drawFrame();
        if (!shown) glfwShowWindow(window);
        //?} else {
        /*if (!shown) {
            if (!captureApi.SetWindowDisplayAffinity(hwnd, 0x11)) {
                throw new IllegalStateException("Windows rejected overlay capture exclusion: " + Native.getLastError());
            }
            verifyCaptureProtection();
            drawFrame();
            if (!SDL_ShowWindow(window)) throw new IllegalStateException("Cannot show the protected overlay");
        }
        drawFrame();
        *///?}
        shown = true;
    }

    public void drawFrame() {
        verifyCaptureProtection();
        withContext(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var w = stack.mallocInt(1); var h = stack.mallocInt(1);
                //? if <26.3 {
                glfwGetFramebufferSize(window, w, h);
                //?} else {
                /*SDL_GetWindowSizeInPixels(window, w, h);
                *///?}
                glViewport(0, 0, w.get(0), h.get(0));
            }
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            backend.renderDrawData(ImGui.getDrawData());
            //? if <26.3 {
            glfwSwapBuffers(window);
            //?} else {
            /*SDL_GL_SwapWindow(window);
            *///?}
        });
    }

    private static GLCapabilities capabilitiesOrNull() {
        try { return GL.getCapabilities(); } catch (IllegalStateException ignored) { return null; }
    }

    private void withContext(Runnable action) {
        //? if <26.3 {
        long previous = glfwGetCurrentContext();
        //?} else {
        /*long previous = SDL_GL_GetCurrentWindow();
        long previousContext = SDL_GL_GetCurrentContext();
        *///?}
        GLCapabilities previousCapabilities = capabilitiesOrNull();
        try {
            //? if <26.3 {
            glfwMakeContextCurrent(window);
            //?} else {
            /*if (!SDL_GL_MakeCurrent(window, glContext)) throw new IllegalStateException("Cannot activate protected OpenGL context: "
                + org.lwjgl.sdl.SDLError.SDL_GetError());
            *///?}
            GL.setCapabilities(capabilities);
            action.run();
        } finally {
            //? if <26.3 {
            glfwMakeContextCurrent(previous);
            //?} else {
            /*SDL_GL_MakeCurrent(previous, previousContext);
            *///?}
            GL.setCapabilities(previousCapabilities);
        }
    }

    private void hide() {
        //? if <26.3 {
        glfwHideWindow(window);
        //?} else {
        /*SDL_HideWindow(window);
        *///?}
    }

    @Override
    public void close() {
        if (window == 0) return;
        hide();
        try { if (backend != null) withContext(() -> backend.shutdown()); }
        catch (IllegalStateException unavailable) {
            // Destroying the owning context also frees its GL objects. Continue cleanup
            // when a lost context cannot be made current for explicit backend cleanup.
            org.slf4j.LoggerFactory.getLogger("mica").warn("Overlay context unavailable during cleanup", unavailable);
        }
        finally {
            //? if <26.3 {
            glfwDestroyWindow(window);
            //?} else {
            /*if (glContext != 0) SDL_GL_DestroyContext(glContext);
            glContext = 0;
            SDL_DestroyWindow(window);
            *///?}
            window = 0;
            backend = null;
            captureApi = null;
            hwnd = null;
            fontRevision = -1;
        }
    }
}

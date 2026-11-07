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
import org.lwjgl.glfw.GLFWNativeWin32;
import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11.*;

 
public final class ProtectedOverlay implements AutoCloseable {
    public interface CaptureApi extends StdCallLibrary {
        boolean SetWindowDisplayAffinity(HWND window, int affinity);
        boolean GetWindowDisplayAffinity(HWND window, IntByReference affinity);
        int GetWindowLong(HWND window, int index);
        int SetWindowLong(HWND window, int index, int value);
    }

    public interface VersionApi extends StdCallLibrary {
        int RtlGetVersion(OSVERSIONINFOEX version);
    }

    public interface CompositionApi extends StdCallLibrary {
        int DwmIsCompositionEnabled(IntByReference enabled);
    }

    private long window;
    private ImGuiImplGl3 backend;
    private GLCapabilities capabilities;
    private boolean shown;
    private CaptureApi captureApi;
    private HWND hwnd;
    private long fontRevision = -1;

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
            if (window != 0) glfwHideWindow(window);
            shown = false;
            throw new IllegalStateException("Capture exclusion was lost; overlay hidden");
        }
    }

    public void init() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new UnsupportedOperationException("Capture exclusion requires Windows 10 2004 or later");
        }
        requireSupportedWindows();
        long previous = glfwGetCurrentContext();
        GLCapabilities previousCapabilities = capabilitiesOrNull();
        try {
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
            CaptureApi api = Native.load("user32", CaptureApi.class, W32APIOptions.DEFAULT_OPTIONS);
            captureApi = api;
             
            int style = api.GetWindowLong(hwnd, -20);
            Native.setLastError(0);
            int previousStyle = api.SetWindowLong(hwnd, -20, style | 0x80 | 0x08000000 | 0x20);
            if (previousStyle == 0 && Native.getLastError() != 0) {
                throw new IllegalStateException("Cannot configure the overlay window: " + Native.getLastError());
            }
            if (!api.SetWindowDisplayAffinity(hwnd, 0x11)) {
                throw new IllegalStateException("Windows rejected capture exclusion: " + Native.getLastError());
            }
            verifyCaptureProtection();
            glfwMakeContextCurrent(window);
            capabilities = GL.createCapabilities();
            glfwSwapInterval(0);
            backend = new ImGuiImplGl3();
            if (!backend.init("#version 330 core")) throw new IllegalStateException("Cannot initialize overlay OpenGL");
        } finally {
            glfwDefaultWindowHints();
            glfwMakeContextCurrent(previous);
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
        long host = mc.getWindow().handle();
         
        boolean visible = mc.isWindowActive() && glfwGetWindowAttrib(host, GLFW_ICONIFIED) == GLFW_FALSE
            && glfwGetWindowMonitor(host) == 0;
        if (!visible) {
            if (shown) glfwHideWindow(window);
            shown = false;
            return;
        }
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1); var y = stack.mallocInt(1);
            var w = stack.mallocInt(1); var h = stack.mallocInt(1);
            glfwGetWindowPos(host, x, y);
            glfwGetWindowSize(host, w, h);
            glfwSetWindowPos(window, x.get(0), y.get(0));
            glfwSetWindowSize(window, Math.max(1, w.get(0)), Math.max(1, h.get(0)));
        }
        drawFrame();
        if (!shown) glfwShowWindow(window);
        shown = true;
    }

    public void drawFrame() {
        verifyCaptureProtection();
        withContext(() -> {
            try (MemoryStack stack = MemoryStack.stackPush()) {
                var w = stack.mallocInt(1); var h = stack.mallocInt(1);
                glfwGetFramebufferSize(window, w, h);
                glViewport(0, 0, w.get(0), h.get(0));
            }
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            backend.renderDrawData(ImGui.getDrawData());
            glfwSwapBuffers(window);
        });
    }

    private static GLCapabilities capabilitiesOrNull() {
        try { return GL.getCapabilities(); } catch (IllegalStateException ignored) { return null; }
    }

    private void withContext(Runnable action) {
        long previous = glfwGetCurrentContext();
        GLCapabilities previousCapabilities = capabilitiesOrNull();
        try {
            glfwMakeContextCurrent(window);
            GL.setCapabilities(capabilities);
            action.run();
        } finally {
            glfwMakeContextCurrent(previous);
            GL.setCapabilities(previousCapabilities);
        }
    }

    @Override
    public void close() {
        if (window == 0) return;
        try { if (backend != null) withContext(() -> backend.shutdown()); }
        finally { glfwDestroyWindow(window); window = 0; backend = null; captureApi = null; hwnd = null; fontRevision = -1; }
    }
}

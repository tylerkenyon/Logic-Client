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

    private final ProtectedWindow window = new ProtectedWindow();
    private ImGuiImplGl3 backend;
    private boolean shown;
    private CaptureApi captureApi;
    private HWND hwnd;
    private long fontRevision = -1;

    public long windowHandle() { return window.handle(); }

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
        if (window.handle() == 0 || captureApi == null || !captureApi.GetWindowDisplayAffinity(hwnd, affinity)
                || affinity.getValue() != 0x11) {
            if (window.handle() != 0) hide();
            shown = false;
            throw new IllegalStateException("Capture exclusion was lost; overlay hidden");
        }
    }

    public void init() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) {
            throw new UnsupportedOperationException("Capture exclusion requires Windows 10 2004 or later");
        }
        requireSupportedWindows();
        window.init(() -> {
            long nativeHandle = window.nativeHandle();
            if (nativeHandle == 0) throw new IllegalStateException("Cannot find the overlay window handle");
            hwnd = new HWND(Pointer.createConstant(nativeHandle));
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
            backend = new ImGuiImplGl3();
            if (!backend.init("#version 330 core")) throw new IllegalStateException("Cannot initialize overlay OpenGL");
        });
    }

    boolean prepareHost() {
        if (window.prepareHost()) return true;
        if (shown) hide();
        return false;
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
        if (!hostActive || !window.canShow(host)) {
            if (shown) hide();
            return;
        }
        window.follow(host);
        //? if >=26.3 {
        /*if (!shown && !captureApi.SetWindowDisplayAffinity(hwnd, 0x11)) {
            throw new IllegalStateException("Windows rejected overlay capture exclusion: " + Native.getLastError());
        }
        *///?}
        drawFrame();
        if (!shown) {
            verifyCaptureProtection();
            window.show();
            shown = true;
        }
    }

    public void drawFrame() {
        verifyCaptureProtection();
        withContext(() -> {
            window.setViewport();
            glClearColor(0, 0, 0, 0);
            glClear(GL_COLOR_BUFFER_BIT);
            backend.renderDrawData(ImGui.getDrawData());
            window.swap();
        });
    }

    private void withContext(Runnable action) {
        window.withContext(action);
    }

    private void hide() {
        window.hide();
        shown = false;
    }

    @Override
    public void close() {
        if (window.handle() == 0) return;
        try {
            hide();
            if (backend != null) withContext(backend::shutdown);
        } catch (IllegalStateException unavailable) {
            org.slf4j.LoggerFactory.getLogger("mica").warn("Overlay context unavailable during cleanup", unavailable);
        } finally {
            window.close();
            shown = false;
            backend = null;
            captureApi = null;
            hwnd = null;
            fontRevision = -1;
        }
    }
}

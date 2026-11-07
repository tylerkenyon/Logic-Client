package dev.technix.mica;

import dev.technix.mica.internal.ProtectedOverlay;
import dev.technix.mica.internal.ImGuiFonts;
import dev.technix.mica.api.FontRegistry;
import net.minecraft.resources.Identifier;
import imgui.ImGui;
import static org.lwjgl.glfw.GLFW.*;

 
public final class ProtectedOverlayCheck {
    public static void main(String[] args) {
        if (!glfwInit()) throw new AssertionError("GLFW initialization failed");
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
                    if (glfwGetCurrentContext() != 0) throw new AssertionError("Initialization leaked a GL context");
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
                        if (glfwGetCurrentContext() != 0) throw new AssertionError("Rendering leaked a GL context");
                    }
                     
                    try {
                        var field = ProtectedOverlay.class.getDeclaredField("window");
                        field.setAccessible(true);
                        long handle = field.getLong(overlay);
                        var nativeWindow = new com.sun.jna.platform.win32.WinDef.HWND(com.sun.jna.Pointer.createConstant(
                            org.lwjgl.glfw.GLFWNativeWin32.glfwGetWin32Window(handle)));
                        var api = com.sun.jna.Native.load("user32", ProtectedOverlay.CaptureApi.class,
                            com.sun.jna.win32.W32APIOptions.DEFAULT_OPTIONS);
                        if (!api.SetWindowDisplayAffinity(nativeWindow, 0)) throw new AssertionError("Cannot simulate exclusion loss");
                        boolean rejected = false;
                        try { overlay.drawFrame(); } catch (IllegalStateException expected) { rejected = true; }
                        if (!rejected || glfwGetWindowAttrib(handle, GLFW_VISIBLE) != GLFW_FALSE) {
                            throw new AssertionError("Overlay must hide and reject rendering when protection is lost");
                        }
                    } catch (ReflectiveOperationException failure) {
                        throw new AssertionError(failure);
                    }
                } finally {
                    ImGui.destroyContext();
                    ImGuiFonts.reset();
                }
            }
        } finally {
            glfwTerminate();
        }
        System.out.println("Protected overlay check passed: native exclusion verified, text rendered, protection loss blocked, contexts restored, windows cleaned up.");
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

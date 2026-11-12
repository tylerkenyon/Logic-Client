package dev.technix.mica.internal;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GLCapabilities;
import org.lwjgl.system.MemoryStack;

//? if <26.3 {
import org.lwjgl.glfw.GLFWNativeWin32;
import static org.lwjgl.glfw.GLFW.*;
//?} else {
/*import static org.lwjgl.sdl.SDLVideo.*;
import static org.lwjgl.sdl.SDLProperties.SDL_GetPointerProperty;
import static org.lwjgl.sdl.SDLError.SDL_GetError;
*///?}

final class ProtectedWindow implements AutoCloseable {
    private long window;
    private GLCapabilities capabilities;

    //? if >=26.3 {
    /*private long glContext;
    private int lastX = Integer.MIN_VALUE, lastY, lastWidth, lastHeight;
    private boolean fullscreenChangeQueued;
    private boolean usingBorderlessFullscreen;
    private static final int[] GL_ATTRIBUTES = {
        SDL_GL_CONTEXT_MAJOR_VERSION, SDL_GL_CONTEXT_MINOR_VERSION, SDL_GL_CONTEXT_PROFILE_MASK,
        SDL_GL_CONTEXT_FLAGS, SDL_GL_SHARE_WITH_CURRENT_CONTEXT
    };
    private static final int[] OVERLAY_ATTRIBUTES = {3, 3, SDL_GL_CONTEXT_PROFILE_CORE, 0, 0};
    *///?}

    static final class ContextException extends IllegalStateException {
        ContextException(String message) { super(message); }
    }

    long handle() { return window; }

    long nativeHandle() {
        //? if <26.3 {
        return GLFWNativeWin32.glfwGetWin32Window(window);
        //?} else {
        /*return SDL_GetPointerProperty(SDL_GetWindowProperties(window), SDL_PROP_WINDOW_WIN32_HWND_POINTER, 0);
        *///?}
    }

    void init(Runnable initializeRenderer) {
        try (HostContext host = HostContext.capture()) {
            //? if <26.3 {
            glfwDefaultWindowHints();
            try {
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
            } finally {
                glfwDefaultWindowHints();
            }
            if (window == 0) throw new IllegalStateException("Cannot create the protected overlay window");
            if (glfwGetWindowAttrib(window, GLFW_TRANSPARENT_FRAMEBUFFER) != GLFW_TRUE) {
                throw new IllegalStateException("Transparent overlay framebuffers are unavailable");
            }
            glfwMakeContextCurrent(window);
            glfwSwapInterval(0);
            //?} else {
            /*createSdlWindow();
            makeCurrent(window, glContext, "activate new protected OpenGL context");
            SDL_GL_SetSwapInterval(0);
            *///?}
            capabilities = GL.createCapabilities();
            initializeRenderer.run();
        }
    }

    //? if >=26.3 {
    /*private void createSdlWindow() {
        int[] previous = new int[GL_ATTRIBUTES.length];
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var value = stack.mallocInt(1);
            for (int i = 0; i < GL_ATTRIBUTES.length; i++) {
                if (!SDL_GL_GetAttribute(GL_ATTRIBUTES[i], value)) {
                    throw sdlFailure("read protected OpenGL creation attributes");
                }
                previous[i] = value.get(0);
            }
        }
        try {
            for (int i = 0; i < GL_ATTRIBUTES.length; i++) {
                if (!SDL_GL_SetAttribute(GL_ATTRIBUTES[i], OVERLAY_ATTRIBUTES[i])) {
                    throw sdlFailure("set protected OpenGL creation attributes");
                }
            }
            window = SDL_CreateWindow("Mica overlay", 64, 64, SDL_WINDOW_OPENGL | SDL_WINDOW_HIDDEN
                | SDL_WINDOW_BORDERLESS | SDL_WINDOW_ALWAYS_ON_TOP | SDL_WINDOW_NOT_FOCUSABLE | SDL_WINDOW_TRANSPARENT);
            if (window == 0) throw sdlFailure("create protected overlay window");
            glContext = SDL_GL_CreateContext(window);
            if (glContext == 0) throw sdlFailure("create protected OpenGL context");
        } finally {
            for (int i = 0; i < GL_ATTRIBUTES.length; i++) {
                if (!SDL_GL_SetAttribute(GL_ATTRIBUTES[i], previous[i])) {
                    org.slf4j.LoggerFactory.getLogger("mica").warn("Could not restore SDL GL attribute {}: {}",
                        GL_ATTRIBUTES[i], SDL_GetError());
                }
            }
        }
    }

    private static ContextException sdlFailure(String operation) {
        String error = SDL_GetError();
        if (error.isBlank()) {
            error = "SDL returned false without an error";
        }
        return new ContextException("Cannot " + operation + ": " + error);
    }

    private static void makeCurrent(long window, long context, String operation) {
        if (!SDL_GL_MakeCurrent(context == 0 ? 0 : window, context)) throw sdlFailure(operation);
    }
    *///?}

    void withContext(Runnable action) {
        try (HostContext host = HostContext.capture()) {
            //? if <26.3 {
            glfwMakeContextCurrent(window);
            //?} else {
            /*makeCurrent(window, glContext, "activate protected OpenGL context");
            *///?}
            GL.setCapabilities(capabilities);
            action.run();
        }
    }

    private record HostContext(long window, long context, GLCapabilities capabilities) implements AutoCloseable {
        static HostContext capture() {
            GLCapabilities capabilities;
            try { capabilities = GL.getCapabilities(); }
            catch (IllegalStateException ignored) { capabilities = null; }
            //? if <26.3 {
            return new HostContext(glfwGetCurrentContext(), 0, capabilities);
            //?} else {
            /*return new HostContext(SDL_GL_GetCurrentWindow(), SDL_GL_GetCurrentContext(), capabilities);
            *///?}
        }

        @Override
        public void close() {
            boolean restored = false;
            try {
                //? if <26.3 {
                glfwMakeContextCurrent(window);
                //?} else {
                /*makeCurrent(window, context, "restore Minecraft OpenGL context");
                *///?}
                restored = true;
            } finally {
                GL.setCapabilities(restored ? capabilities : null);
            }
        }
    }

    boolean prepareHost() {
        //? if <26.3 {
        return true;
        //?} else {
        /*var game = net.minecraft.client.Minecraft.getInstance();
        var host = game.getWindow();
        if (!host.isExclusiveFullscreen()) return true;
        if (!fullscreenChangeQueued) {
            fullscreenChangeQueued = true;
            // Change the surface before the next frame, outside render submission.
            game.schedule(() -> {
                fullscreenChangeQueued = false;
                if (window == 0 || !game.isRunning() || !host.isExclusiveFullscreen()) return;
                host.setExclusiveFullscreen(false);
                usingBorderlessFullscreen = true;
            });
        }
        return false;
        *///?}
    }

    boolean canShow(long host) {
        //? if <26.3 {
        return glfwGetWindowAttrib(host, GLFW_ICONIFIED) == GLFW_FALSE && glfwGetWindowMonitor(host) == 0;
        //?} else {
        /*return (SDL_GetWindowFlags(host) & SDL_WINDOW_MINIMIZED) == 0;
        *///?}
    }

    void follow(long host) {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var x = stack.mallocInt(1);
            var y = stack.mallocInt(1);
            var w = stack.mallocInt(1);
            var h = stack.mallocInt(1);
            //? if <26.3 {
            glfwGetWindowPos(host, x, y);
            glfwGetWindowSize(host, w, h);
            glfwSetWindowPos(window, x.get(0), y.get(0));
            glfwSetWindowSize(window, Math.max(1, w.get(0)), Math.max(1, h.get(0)));
            //?} else {
            /*if (!SDL_GetWindowPosition(host, x, y) || !SDL_GetWindowSize(host, w, h)) {
                throw sdlFailure("read Minecraft window bounds");
            }
            int width = Math.max(1, w.get(0));
            int height = Math.max(1, h.get(0));
            if (lastX == x.get(0) && lastY == y.get(0) && lastWidth == width && lastHeight == height) return;
            if (!SDL_SetWindowPosition(window, x.get(0), y.get(0)) || !SDL_SetWindowSize(window, width, height)) {
                throw sdlFailure("position protected overlay window");
            }
            lastX = x.get(0);
            lastY = y.get(0);
            lastWidth = width;
            lastHeight = height;
            *///?}
        }
    }

    void setViewport() {
        try (MemoryStack stack = MemoryStack.stackPush()) {
            var w = stack.mallocInt(1);
            var h = stack.mallocInt(1);
            //? if <26.3 {
            glfwGetFramebufferSize(window, w, h);
            //?} else {
            /*if (!SDL_GetWindowSizeInPixels(window, w, h)) throw sdlFailure("read protected framebuffer size");
            *///?}
            org.lwjgl.opengl.GL11.glViewport(0, 0, w.get(0), h.get(0));
        }
    }

    void show() {
        //? if <26.3 {
        glfwShowWindow(window);
        //?} else {
        /*if (!SDL_ShowWindow(window)) throw sdlFailure("show protected overlay window");
        *///?}
    }

    void hide() {
        //? if <26.3 {
        glfwHideWindow(window);
        //?} else {
        /*if (!SDL_HideWindow(window)) throw sdlFailure("hide protected overlay window");
        *///?}
    }

    void swap() {
        //? if <26.3 {
        glfwSwapBuffers(window);
        //?} else {
        /*if (!SDL_GL_SwapWindow(window)) throw sdlFailure("swap protected OpenGL buffers");
        *///?}
    }

    @Override
    public void close() {
        if (window == 0) return;
        //? if <26.3 {
        glfwDestroyWindow(window);
        //?} else {
        /*if (glContext != 0) SDL_GL_DestroyContext(glContext);
        glContext = 0;
        SDL_DestroyWindow(window);
        lastX = Integer.MIN_VALUE;
        if (usingBorderlessFullscreen) {
            usingBorderlessFullscreen = false;
            var game = net.minecraft.client.Minecraft.getInstance();
            if (game.isRunning()) game.schedule(() -> {
                if (game.isRunning()) game.getWindow().setExclusiveFullscreen(game.options.exclusiveFullscreen().get());
            });
        }
        *///?}
        window = 0;
        capabilities = null;
    }
}

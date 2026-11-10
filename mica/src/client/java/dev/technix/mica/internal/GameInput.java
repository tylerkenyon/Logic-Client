package dev.technix.mica.internal;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.glfw.GLFW;

// Logic stores GLFW-style binding IDs in its config. Translate at the game boundary.
public final class GameInput {
    private GameInput() {}

    public static int key(int gameKey) {
        //? if <26.3 {
        return gameKey;
        //?} else {
        /*return Keys.FROM_GAME.getOrDefault(gameKey, GLFW.GLFW_KEY_UNKNOWN);
        *///?}
    }

    public static int mouse(int gameButton) {
        //? if <26.3 {
        return gameButton;
        //?} else {
        /*return gameButton == 2 ? 2 : gameButton == 3 ? 1 : gameButton - 1;
        *///?}
    }

    public static boolean keyDown(long window, int binding) {
        //? if <26.3 {
        return GLFW.glfwGetKey(window, binding) == GLFW.GLFW_PRESS;
        //?} else {
        /*Integer key = Keys.TO_GAME.get(binding);
        return key != null && InputConstants.isKeyDown(key);
        *///?}
    }

    public static boolean mouseDown(long window, int gameButton) {
        //? if <26.3 {
        return GLFW.glfwGetMouseButton(window, gameButton) == GLFW.GLFW_PRESS;
        //?} else {
        /*return gameButton > 0 && gameButton <= 32
            && (org.lwjgl.sdl.SDLMouse.SDL_GetMouseState(null, null) & (1 << (gameButton - 1))) != 0;
        *///?}
    }

    public static boolean boundMouseDown(long window, int binding) {
        //? if <26.3 {
        return mouseDown(window, binding);
        //?} else {
        /*return mouseDown(window, binding == 1 ? 3 : binding == 2 ? 2 : binding + 1);
        *///?}
    }

    public static InputConstants.Key describe(int binding, boolean mouse) {
        //? if <26.3 {
        return (mouse ? InputConstants.Type.MOUSE : InputConstants.Type.KEYSYM).getOrCreate(binding);
        //?} else {
        /*return mouse ? InputConstants.Type.MOUSE.getOrCreate(binding == 1 ? 3 : binding == 2 ? 2 : binding + 1)
            : Keys.TO_GAME.containsKey(binding) ? InputConstants.Type.KEYBOARD.getOrCreate(Keys.TO_GAME.get(binding)) : InputConstants.UNKNOWN;
        *///?}
    }

    //? if >=26.3 {
    /*private static final class Keys {
        static final java.util.Map<Integer, Integer> TO_GAME = new java.util.HashMap<>();
        static final java.util.Map<Integer, Integer> FROM_GAME = new java.util.HashMap<>();

        static {
            for (char c = 'a'; c <= 'z'; c++) add(Character.toUpperCase(c), String.valueOf(c));
            for (char c = '0'; c <= '9'; c++) {
                add(c, String.valueOf(c));
                add(GLFW.GLFW_KEY_KP_0 + c - '0', "keypad." + c);
            }
            for (int i = 1; i <= 24; i++) add(GLFW.GLFW_KEY_F1 + i - 1, "f" + i);
            add(GLFW.GLFW_KEY_RIGHT_SHIFT, "right.shift");
            add(GLFW.GLFW_KEY_LEFT_SHIFT, "left.shift");
            add(GLFW.GLFW_KEY_RIGHT_CONTROL, "right.control");
            add(GLFW.GLFW_KEY_LEFT_CONTROL, "left.control");
            add(GLFW.GLFW_KEY_LEFT_ALT, "left.alt");
            add(GLFW.GLFW_KEY_RIGHT_ALT, "right.alt");
            add(GLFW.GLFW_KEY_LEFT_SUPER, "left.win");
            add(GLFW.GLFW_KEY_RIGHT_SUPER, "right.win");
            add(GLFW.GLFW_KEY_ESCAPE, "escape");
            add(GLFW.GLFW_KEY_ENTER, "enter");
            add(GLFW.GLFW_KEY_KP_ENTER, "keypad.enter");
            add(GLFW.GLFW_KEY_BACKSPACE, "backspace");
            add(GLFW.GLFW_KEY_DELETE, "delete");
            add(GLFW.GLFW_KEY_HOME, "home");
            add(GLFW.GLFW_KEY_END, "end");
            add(GLFW.GLFW_KEY_LEFT, "left");
            add(GLFW.GLFW_KEY_RIGHT, "right");
            add(GLFW.GLFW_KEY_UP, "up");
            add(GLFW.GLFW_KEY_DOWN, "down");
            add(GLFW.GLFW_KEY_SPACE, "space");
            add(GLFW.GLFW_KEY_TAB, "tab");
            add(GLFW.GLFW_KEY_PAGE_UP, "page.up");
            add(GLFW.GLFW_KEY_PAGE_DOWN, "page.down");
            add(GLFW.GLFW_KEY_INSERT, "insert");
            add(GLFW.GLFW_KEY_CAPS_LOCK, "caps.lock");
            add(GLFW.GLFW_KEY_NUM_LOCK, "num.lock");
            add(GLFW.GLFW_KEY_SCROLL_LOCK, "scroll.lock");
            add(GLFW.GLFW_KEY_PRINT_SCREEN, "print.screen");
            add(GLFW.GLFW_KEY_PAUSE, "pause");
            add(GLFW.GLFW_KEY_APOSTROPHE, "apostrophe");
            add(GLFW.GLFW_KEY_COMMA, "comma");
            add(GLFW.GLFW_KEY_MINUS, "minus");
            add(GLFW.GLFW_KEY_PERIOD, "period");
            add(GLFW.GLFW_KEY_SLASH, "slash");
            add(GLFW.GLFW_KEY_SEMICOLON, "semicolon");
            add(GLFW.GLFW_KEY_EQUAL, "equal");
            add(GLFW.GLFW_KEY_LEFT_BRACKET, "left.bracket");
            add(GLFW.GLFW_KEY_RIGHT_BRACKET, "right.bracket");
            add(GLFW.GLFW_KEY_BACKSLASH, "backslash");
            add(GLFW.GLFW_KEY_GRAVE_ACCENT, "grave.accent");
            add(GLFW.GLFW_KEY_KP_DECIMAL, "keypad.decimal");
            add(GLFW.GLFW_KEY_KP_DIVIDE, "keypad.divide");
            add(GLFW.GLFW_KEY_KP_MULTIPLY, "keypad.multiply");
            add(GLFW.GLFW_KEY_KP_SUBTRACT, "keypad.subtract");
            add(GLFW.GLFW_KEY_KP_ADD, "keypad.add");
            add(GLFW.GLFW_KEY_KP_EQUAL, "keypad.equal");
        }

        private static void add(int binding, String name) {
            try {
                int key = InputConstants.getKey("key.keyboard." + name).getValue();
                TO_GAME.put(binding, key);
                FROM_GAME.put(key, binding);
            } catch (IllegalArgumentException ignored) {
                // A key absent from this game's registry cannot be bound.
            }
        }
    }
    *///?}
}

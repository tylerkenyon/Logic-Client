package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.Setting;
import dev.technix.logic.client.setting.StringSetting;
import dev.technix.logic.client.setting.KeybindSetting;
import dev.technix.mica.api.CaptureStatus;
import dev.technix.mica.internal.ActiveRenderers;
import net.minecraft.client.Minecraft;
import net.minecraft.client.KeyMapping;
import org.lwjgl.glfw.GLFW;



public final class ClickGui extends Module {

    public static final int TOGGLE_KEY = GLFW.GLFW_KEY_RIGHT_SHIFT;

    private boolean open;
    private boolean wasMouseGrabbed, cursorHidden;
    private int previousCursorMode;
    private Object bindingTarget;
    private StringSetting editingText;
    private int textCursor;

    public ClickGui() {
        super("ClickGUI", "", Category.PLAYER, TOGGLE_KEY, false);
    }

    public boolean open() {
        return open;
    }

    public boolean isBinding(Object target) { return bindingTarget == target; }
    public void startBinding(Object target) {
        if (!(target instanceof Module) && !(target instanceof KeybindSetting)) return;
        editingText = null;
        bindingTarget = target == bindingTarget ? null : target;
    }

    public boolean isEditing(StringSetting setting) { return editingText == setting; }
    public boolean bindMouse(int button, int action) {
        if (!(bindingTarget instanceof KeybindSetting bind) || !bind.visible() || action != GLFW.GLFW_PRESS) return false;
        bind.set(button);
        bindingTarget = null;
        return true;
    }
    public int textCursor() { return textCursor; }
    public void editText(StringSetting setting) {
        editingText = setting;
        textCursor = setting == null ? 0 : setting.get().length();
        if (setting != null) bindingTarget = null;
    }

    public void handleCharacter(int codepoint) {
        if (editingText != null && editingText.visible() && Character.isValidCodePoint(codepoint))
            insertText(new String(Character.toChars(codepoint)));
    }

    private void insertText(String text) {
        String current = editingText.get();
        String next = current.substring(0, textCursor) + text + current.substring(textCursor);
        try {
            editingText.set(next);
            textCursor += text.length();
        } catch (IllegalArgumentException ignored) { }
    }

    public void handleKey(int key, int action) {
        if (action != GLFW.GLFW_PRESS && action != GLFW.GLFW_REPEAT) return;
        if (bindingTarget instanceof Setting<?> setting && !setting.visible()) bindingTarget = null;
        if (editingText != null && !editingText.visible()) editText(null);
        if (editingText != null && key != TOGGLE_KEY && key != key()) {
            String value = editingText.get();
            switch (key) {
                case GLFW.GLFW_KEY_ESCAPE, GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> editText(null);
                case GLFW.GLFW_KEY_LEFT -> textCursor = value.offsetByCodePoints(textCursor, textCursor > 0 ? -1 : 0);
                case GLFW.GLFW_KEY_RIGHT -> textCursor = value.offsetByCodePoints(textCursor, textCursor < value.length() ? 1 : 0);
                case GLFW.GLFW_KEY_HOME -> textCursor = 0;
                case GLFW.GLFW_KEY_END -> textCursor = value.length();
                case GLFW.GLFW_KEY_BACKSPACE -> {
                    if (textCursor > 0) {
                        int previous = value.offsetByCodePoints(textCursor, -1);
                        editingText.set(value.substring(0, previous) + value.substring(textCursor));
                        textCursor = previous;
                    }
                }
                case GLFW.GLFW_KEY_DELETE -> {
                    if (textCursor < value.length()) editingText.set(value.substring(0, textCursor) + value.substring(value.offsetByCodePoints(textCursor, 1)));
                }
                case GLFW.GLFW_KEY_V -> {
                    long window = mc().getWindow().handle();
                    if (GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_CONTROL) == GLFW.GLFW_PRESS
                            || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_CONTROL) == GLFW.GLFW_PRESS)
                        insertText(mc().keyboardHandler.getClipboard());
                }
                default -> { }
            }
            return;
        }
        if (action != GLFW.GLFW_PRESS) return;
        if (bindingTarget == null) {
            if (key == TOGGLE_KEY || key == key() || key == GLFW.GLFW_KEY_ESCAPE) setEnabled(false);
            return;
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            bindingTarget = null;
            return;
        }
        if (key < GLFW.GLFW_KEY_SPACE || key > GLFW.GLFW_KEY_LAST || key == TOGGLE_KEY || key == key()) return;
        int binding = key == GLFW.GLFW_KEY_BACKSPACE || key == GLFW.GLFW_KEY_DELETE ? GLFW.GLFW_KEY_UNKNOWN : key;
        if (bindingTarget instanceof KeybindSetting bind) bind.set(binding);
        else if (bindingTarget instanceof Module module) module.setKey(binding);
        bindingTarget = null;
        editText(null);
    }

    public static boolean shouldHideCursor(boolean open, boolean wasGrabbed, boolean focused, CaptureStatus status) {
        return open && wasGrabbed && focused && status == CaptureStatus.ACTIVE;
    }

    public boolean updateProtectedCursor() {
        Minecraft mc = mc();
        if (mc == null || mc.getWindow() == null) return false;
        long window = mc.getWindow().handle();
        var overlay = ActiveRenderers.get();
        boolean hide = shouldHideCursor(open, wasMouseGrabbed, mc.isWindowActive()
            && GLFW.glfwGetWindowMonitor(window) == 0
            && GLFW.glfwGetWindowAttrib(window, GLFW.GLFW_ICONIFIED) == GLFW.GLFW_FALSE,
            overlay == null ? CaptureStatus.DISABLED : overlay.captureStatus());
        if (hide && !cursorHidden) {
            previousCursorMode = GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR);
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
            cursorHidden = true;
        } else if (!hide && cursorHidden) {
            if (GLFW.glfwGetInputMode(window, GLFW.GLFW_CURSOR) == GLFW.GLFW_CURSOR_HIDDEN)
                GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, previousCursorMode);
            cursorHidden = false;
        }
        return cursorHidden;
    }

    @Override
    protected void onEnable() {
        open = true;
        KeyMapping.releaseAll();
        Minecraft mc = mc();
        if (mc != null && mc.mouseHandler != null) {
            wasMouseGrabbed = mc.mouseHandler.isMouseGrabbed();
            mc.mouseHandler.releaseMouse();
        }
    }

    @Override
    protected void onDisable() {
        open = false;
        bindingTarget = null;
        editText(null);
        updateProtectedCursor();
        Minecraft mc = mc();
        if (wasMouseGrabbed && mc != null && mc.player != null && mc.gui.screen() == null && mc.mouseHandler != null) {
            mc.mouseHandler.grabMouse();
        }
        wasMouseGrabbed = false;
    }
}

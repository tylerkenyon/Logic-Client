package dev.technix.logic.inject;

import dev.technix.mica.internal.GameInput;
import dev.technix.logic.Logic;
import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.impl.Backtrack;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.client.module.impl.FastPlace;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.event.impl.FrameUpdateEvent;
import dev.technix.logic.event.impl.KeyPressEvent;
import dev.technix.mica.internal.ActiveRenderers;
import dev.technix.mica.api.InjectedMica;
import net.minecraft.client.Minecraft;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.PacketListener;
import net.minecraft.network.Connection;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.world.entity.Entity;
import org.joml.Matrix4f;
import org.lwjgl.glfw.GLFW;

import java.lang.reflect.Field;


public final class InjectionHooks {
    static volatile boolean active;
    static boolean ownsMica;
    private static long lastFrame;
    private static String overlayState;
    private static Field useDelay;
    private static Field worldProjectionBuffer;

    private InjectionHooks() { }

    public static Object dispatch(String hook, Object[] args) {
        return switch (hook) {
            case "keyboard" -> keyboard((int)args[0], (KeyEvent)args[1]);
            case "mouse" -> mouse((MouseButtonInfo)args[0], (int)args[1]);
            case "scroll" -> scroll((double)args[0], (double)args[1]);
            case "character" -> character((CharacterEvent)args[0]);
            case "textInput" -> textInput((String)args[0]);
            //? if >=26.3 {
            /*case "foreignWindowEvent" -> {
                var window = (com.mojang.blaze3d.platform.Window) args[0];
                var event = (org.lwjgl.sdl.SDL_Event) args[1];
                int type = event.type();
                yield active && type >= org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_FIRST
                    && type <= org.lwjgl.sdl.SDLEvents.SDL_EVENT_WINDOW_LAST
                    && event.window().windowID() != org.lwjgl.sdl.SDLVideo.SDL_GetWindowID(window.handle());
            }
            *///?}
            case "packet" -> packet((PacketListener)args[0], (Packet<?>)args[1]);
            case "hideVanillaNametag" -> active && LogicClient.hideVanillaNametag(args[0]);
            case "rewriteOutgoing" -> active ? LogicClient.rewriteOutgoing((Packet<?>)args[0]) : args[0];
            case "rewriteDisplayText" -> active ? LogicClient.rewriteDisplayText(args[0]) : args[0];
            case "outgoing" -> active && LogicClient.interceptOutgoing(
                (Connection)args[0], (Packet<?>)args[1], (ChannelFutureListener)args[2], (boolean)args[3]);
            case "useItem" -> useItem();
            case "blockAttack" -> active && LogicClient.blockAttack();
            case "tickDuration" -> active ? LogicClient.tickDuration((float) args[0]) : args[0];
            case "attackClick" -> active ? LogicClient.attackClick((boolean)args[0]) : args[0];
            default -> {
                switch (hook) {
                    case "mouseMove" -> mouseMove((double)args[0], (double)args[1]);
                    case "beforeGuiRender" -> beforeGuiRender();
                    case "afterGuiRender" -> afterGuiRender();
                    case "frame" -> frame((DeltaTracker)args[0]);
                    case "projection" -> projection(args[0], (Matrix4f)args[1]);
                    case "attack" -> attack((Entity)args[0]);
                    case "keybinds" -> keybinds();
                    case "afterKeybinds" -> { if (active) LogicClient.afterKeybinds(); }
                    case "startTick" -> LogicClient.startTick();
                    case "endTick" -> LogicClient.endTick();
                    case "stopClient" -> LogicClient.stopClient();
                    default -> throw new IllegalArgumentException("Unknown runtime hook: " + hook);
                }
                yield null;
            }
        };
    }

    static void clear() {
        active = false;
        ownsMica = false;
        lastFrame = 0;
        useDelay = worldProjectionBuffer = null;
    }

    static void prepare() throws ReflectiveOperationException {
        useDelay = Minecraft.class.getDeclaredField("rightClickDelay");
        useDelay.setAccessible(true);
        worldProjectionBuffer = net.minecraft.client.renderer.GameRenderer.class.getDeclaredField("levelProjectionMatrixBuffer");
        worldProjectionBuffer.setAccessible(true);
    }

    private static boolean textInput(String text) {
        if (!active || !gui().open()) return false;
        text.codePoints().forEach(gui()::handleCharacter);
        return true;
    }

    private static ClickGui gui() { return LogicClient.MODULES.get(ClickGui.class); }

    public static boolean keyboard(int action, KeyEvent key) {
        if (!active) return false;
        ClickGui gui = gui();
        if (gui.open()) {
            gui.handleKey(GameInput.key(key.key()), action);
            return true;
        }
        Logic.events().post(new KeyPressEvent(GameInput.key(key.key()), action));
        if (gui.open()) return true;
        if (ownsMica) {
            //? if <26.3 {
ActiveRenderers.feedKey(key.key(), key.scancode(), action);
//?} else {
/*ActiveRenderers.feedKey(GameInput.key(key.key()), key.key(), action);
*///?}
            return ActiveRenderers.wantsKeyboard();
        }
        return false;
    }

    public static boolean mouse(MouseButtonInfo button, int action) {
        if (!active) return false;
        if (gui().open() && gui().bindMouse(GameInput.mouse(button.button()), action)) return true;
        if (gui().open() || ownsMica) ActiveRenderers.feedMouseButton(GameInput.mouse(button.button()), action != GLFW.GLFW_RELEASE);
        return gui().open() || (ownsMica && ActiveRenderers.wantsMouse());
    }

    public static boolean scroll(double x, double y) {
        if (!active) return false;
        if (gui().open() || ownsMica) ActiveRenderers.feedMouseWheel(x, y);
        return gui().open() || (ownsMica && ActiveRenderers.wantsMouse());
    }

    public static void mouseMove(double x, double y) {
        if (active && ownsMica) ActiveRenderers.feedMouseMove(x, y);
    }

    public static boolean character(CharacterEvent event) {
        if (!active) return false;
        if (gui().open()) {
            gui().handleCharacter(event.codepoint());
            return true;
        }
        if (!ownsMica) return false;
        ActiveRenderers.feedChar(event.codepoint());
        return ActiveRenderers.wantsKeyboard();
    }

    public static void beforeGuiRender() { if (active && ownsMica) InjectedMica.beforeRender(); }
    public static void afterGuiRender() {
        if (!active || !ownsMica) return;
        InjectedMica.afterRender();
        var overlay = ActiveRenderers.get();
        if (overlay == null) return;
        var renderer = overlay.internalRenderer();
        String state = "enabled=" + renderer.isEnabled() + ", OpenGL=" + renderer.isOpenGl()
            + ", capture=" + overlay.captureStatus();
        if (!state.equals(overlayState)) {
            overlayState = state;
            NativeBridge.log("overlay frame: " + state);
        }
    }

    public static void frame(DeltaTracker tracker) {
        if (!active) return;
        long now = System.nanoTime();
        double elapsed = lastFrame == 0 ? 0 : (now - lastFrame) / 1_000_000_000.0;
        lastFrame = now;
        Logic.events().post(new FrameUpdateEvent(tracker.getGameTimeDeltaPartialTick(false), elapsed));
    }

    public static void projection(Object buffer, Matrix4f matrix) {
        if (!active) return;
        var renderer = Minecraft.getInstance().gameRenderer;
        try {
            if (buffer == worldProjectionBuffer.get(renderer)) {
                WorldProjection.capture(matrix, renderer.gameRenderState().levelRenderState.cameraRenderState);
            }
        } catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }

    public static void attack(Entity target) {
        if (!active) return;
        Logic.events().post(new dev.technix.logic.event.impl.AttackEvent(target));
        Backtrack module = LogicClient.MODULES.get(Backtrack.class);
        if (module.enabled()) module.recordAttack(target);
    }

    public static boolean packet(PacketListener listener, Packet<?> packet) {
        if (active && listener instanceof net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl common
                && !(listener instanceof ClientPacketListener) && LogicClient.interceptCommonPacket(packet, common)) return true;
        if (!active || !(listener instanceof ClientPacketListener client)
                || client != Minecraft.getInstance().getConnection()) return false;
        return LogicClient.interceptPlayPacket(packet, client);
    }

    public static boolean useItem() {
        return active && !LogicClient.allowUse();
    }

    public static void keybinds() {
        if (!active) return;
        LogicClient.beforeKeybinds();
        if (!LogicClient.MODULES.get(FastPlace.class).applicable()) return;
        try { useDelay.setInt(Minecraft.getInstance(), 0); }
        catch (IllegalAccessException failure) { throw new IllegalStateException(failure); }
    }
}

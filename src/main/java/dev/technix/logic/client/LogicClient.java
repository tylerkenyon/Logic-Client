package dev.technix.logic.client;

import dev.technix.logic.Logic;
import dev.technix.logic.client.combat.HitSelectTiming.AttackSource;
import dev.technix.logic.client.gui.ClickGuiRenderer;
import dev.technix.logic.client.module.ModuleManager;
import dev.technix.logic.client.module.impl.ClickGui;
import dev.technix.logic.client.module.impl.Sprint;
import dev.technix.logic.client.module.impl.AimAssist;
import dev.technix.logic.client.module.impl.Triggerbot;
import dev.technix.logic.client.module.impl.HitSelect;
import dev.technix.logic.client.module.impl.ESP;
import dev.technix.logic.client.module.impl.Indicators;
import dev.technix.logic.client.module.impl.Trajectories;
import dev.technix.logic.client.module.impl.Nametags;
import dev.technix.logic.client.module.impl.BlockESP;
import dev.technix.logic.client.module.impl.Blink;
import dev.technix.logic.client.module.impl.ClientSpoofer;
import dev.technix.logic.client.module.impl.NameHider;
import dev.technix.logic.client.module.impl.AntiBot;
import dev.technix.logic.client.module.impl.AntiAction;
import dev.technix.logic.client.module.impl.AutoShieldBreaker;
import dev.technix.logic.client.module.impl.LagRange;
import dev.technix.logic.client.module.impl.AnchorMacro;
import dev.technix.logic.client.module.impl.TimerModule;
import dev.technix.logic.client.module.impl.Backtrack;
import dev.technix.logic.client.module.impl.FastPlace;
import dev.technix.logic.client.module.impl.AutoTool;
import dev.technix.logic.client.module.impl.AutoArmor;
import dev.technix.logic.client.module.impl.ChestStealer;
import dev.technix.logic.client.module.impl.InterfaceModule;
import dev.technix.logic.client.module.impl.ClickGuiConfig;
import dev.technix.logic.client.module.impl.ArraylistModule;
import dev.technix.logic.client.module.impl.SnapTap;
import dev.technix.logic.client.module.impl.Eagle;
import dev.technix.logic.client.module.impl.JumpReset;
import dev.technix.logic.client.module.impl.SprintReset;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundOpenSignEditorPacket;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket;
import dev.technix.logic.client.config.ConfigStore;
import dev.technix.logic.client.gui.UiTheme;
import dev.technix.logic.event.impl.ClientTickEvent;
import dev.technix.mica.api.OverlayRenderer;
import dev.technix.mica.internal.ActiveRenderers;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public final class LogicClient implements ClientModInitializer {
    private static boolean initialized;
    private static volatile LogicClient instance;
    private volatile boolean running;
    private boolean shutdownRequested;
    private boolean previousPauseOnLostFocus;
    private OverlayRenderer attachedOverlay;
    private boolean previousCaptureProtection;

    public static boolean running() { return instance != null && instance.running; }
    public static void requestShutdown() {
        if (instance != null && instance.running) instance.shutdownRequested = true;
    }

    public static final ModuleManager MODULES = new ModuleManager();
    public static final UiTheme THEME = new UiTheme();

    private final ClickGui clickGui = new ClickGui();
    private final ClickGuiRenderer clickGuiRenderer = new ClickGuiRenderer(clickGui);
    private final ClickGuiConfig guiConfig = new ClickGuiConfig();
    private final InterfaceModule interfaceModule = new InterfaceModule();
    private final SnapTap snapTap = new SnapTap();
    private final Eagle eagle = new Eagle();
    private final ClientSpoofer clientSpoofer = new ClientSpoofer();
    private final NameHider nameHider = new NameHider();
    private final AntiBot antiBot = new AntiBot();
    private final HitSelect hitSelect = new HitSelect();
    private final AnchorMacro anchorMacro = new AnchorMacro();
    private final TimerModule timer = new TimerModule();
    private ConfigStore config;

    @Override
    public void onInitializeClient() {
        initialize(false, null);
    }

    public void initializeInjected(java.nio.file.Path configFile) {
        initialize(true, configFile);
    }

    private void initialize(boolean injected, java.nio.file.Path configFile) {
        if (initialized) return;
        initialized = true;
        instance = this;
        running = true;
        Logic.events().register(MODULES);
        MODULES.register(new Sprint());
        MODULES.register(timer);
        MODULES.register(clickGui);
        MODULES.register(new AimAssist());
        MODULES.register(antiBot);
        MODULES.register(new AntiAction());
        MODULES.register(new AutoShieldBreaker());
        MODULES.register(new Triggerbot());
        MODULES.register(hitSelect);
        MODULES.register(new SprintReset());
        MODULES.register(new JumpReset());
        MODULES.register(new ESP());
        MODULES.register(new Indicators());
        MODULES.register(new Trajectories());
        MODULES.register(new Nametags());
        MODULES.register(nameHider);
        MODULES.register(new BlockESP());
        MODULES.register(new LagRange());
        MODULES.register(new Blink());
        MODULES.register(clientSpoofer);
        MODULES.register(new Backtrack());
        MODULES.register(new FastPlace());
        MODULES.register(new AutoTool());
        MODULES.register(new AutoArmor());
        MODULES.register(new ChestStealer());
        MODULES.register(anchorMacro);
        MODULES.register(snapTap);
        MODULES.register(eagle);
        MODULES.register(interfaceModule);
        MODULES.register(guiConfig);
        MODULES.register(new ArraylistModule());
        MODULES.register(new dev.technix.logic.client.module.impl.SelfDestruct());
        config = configFile == null ? new ConfigStore(MODULES, clickGuiRenderer, THEME)
            : new ConfigStore(configFile, MODULES, clickGuiRenderer, THEME);
        if (injected) config.setDiagnostics(dev.technix.logic.inject.NativeBridge::log);
        config.load();
        if (injected) dev.technix.logic.inject.NativeBridge.log("configuration: file=" + configFile
            + ", exists=" + (configFile != null && java.nio.file.Files.exists(configFile))
            + ", captureProtection=" + interfaceModule.screenshareProtection.get());

         
        if (injected) startClient();
        else {
            ClientTickEvents.START_CLIENT_TICK.register(client -> startTick());
            ClientTickEvents.END_CLIENT_TICK.register(client -> endTick());
            ClientLifecycleEvents.CLIENT_STOPPING.register(client -> stopClient());
            ClientLifecycleEvents.CLIENT_STARTED.register(client -> startClient());
        }
    }

    public static void startTick() {
        LogicClient client = instance;
        if (client == null || !client.running || client.shutdownRequested) return;
        client.hitSelect.startTick();
        MODULES.get(JumpReset.class).startTick();
    }

    public static void afterPlayerInput(LocalPlayer player) {
        if (running() && Minecraft.getInstance().player == player) instance.eagle.applyInput(player);
    }

    public static void beforeKeybinds() {
        if (!running()) return;
        MODULES.get(Triggerbot.class).beginKeybinds();
        MODULES.get(AutoShieldBreaker.class).beforeKeybinds();
    }

    public static boolean attackClick(boolean manualClick) {
        if (!running()) return manualClick;
        var hitSelect = instance.hitSelect;
        hitSelect.clearClick();
        var shieldBreaker = MODULES.get(AutoShieldBreaker.class);
        if (shieldBreaker.busy()) return shieldBreaker.attackClick();
        var triggerbot = MODULES.get(Triggerbot.class);
        if (hitSelect.managesEntityClick()) return hitSelect.attackClick(manualClick, triggerbot::automaticAttackReady);
        MODULES.get(AutoTool.class).beforeAttack(manualClick);
        return triggerbot.attackClick(manualClick);
    }

    public static void attackDispatched() {
        if (running() && instance.hitSelect.attackDispatched() == AttackSource.TRIGGERBOT) {
            MODULES.get(Triggerbot.class).recordAutomaticAttack();
        }
    }

    public static void afterKeybinds() {
        if (!running()) return;
        instance.hitSelect.clearClick();
        MODULES.get(SprintReset.class).afterKeybinds();
    }

    public static boolean hideVanillaNametag(Object entity) {
        return running() && ActiveRenderers.get() != null && MODULES.get(Nametags.class).hidesVanilla(entity);
    }

    public static boolean interceptOutgoing(Connection connection, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (running() && instance.clientSpoofer.suppress(packet)) return true;
        var mc = Minecraft.getInstance();
        if (mc == null || !mc.isSameThread() || !running()) return false;
        var blink = MODULES.get(Blink.class);
        var lagRange = MODULES.get(LagRange.class);
        if (!blink.replaying() && !lagRange.replaying()) instance.anchorMacro.observeOutgoing(packet);
        if (blink.enabled() || blink.replaying()) lagRange.suspend();
        if (blink.intercept(connection, packet, listener, flush)) return true;
        return lagRange.intercept(connection, packet, listener, flush, blink.replaying());
    }

    public static Packet<?> rewriteOutgoing(Packet<?> packet) {
        LogicClient client = instance;
        return client != null && client.running ? client.clientSpoofer.rewriteBrand(packet) : packet;
    }

    public static Object rewriteDisplayText(Object text) {
        LogicClient client = instance;
        return client != null && client.running ? client.nameHider.replace(text) : text;
    }

    public static String displayName(String text) { return (String) rewriteDisplayText(text); }

    public static boolean isBot(net.minecraft.world.entity.Entity entity) {
        LogicClient client = instance;
        return client != null && client.running && client.antiBot.isBot(entity);
    }

    public static boolean anchorBusy() {
        LogicClient client = instance;
        return client != null && client.running && client.anchorMacro.busy();
    }

    public static boolean shieldBreakerBusy() {
        return running() && MODULES.get(AutoShieldBreaker.class).busy();
    }

    public static float tickDuration(float original) {
        LogicClient client = instance;
        if (client == null || !client.running) return original;
        var mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || client.clickGui.open()) return original;
        return client.timer.tickDuration(original);
    }

    public static boolean anchorControlsUse() {
        LogicClient client = instance;
        return client != null && client.running && client.anchorMacro.blocksVanillaUse();
    }

    public static boolean allowUse() {
        return !anchorControlsUse() && !shieldBreakerBusy() && !MODULES.get(AntiAction.class).blocksUse() && MODULES.get(FastPlace.class).allowUse();
    }

    public static boolean blockAttack() {
        return running() && MODULES.get(AntiAction.class).blocksAttack();
    }

    public static boolean interceptCommonPacket(Packet<?> packet, ClientCommonPacketListenerImpl listener) {
        if (!running() || !instance.clientSpoofer.spoofPack(packet)) return false;
        var pack = (ClientboundResourcePackPushPacket) packet;
        for (var response : ClientSpoofer.packResponses(pack)) listener.send(response);
        return true;
    }

    public static boolean interceptPlayPacket(Packet<?> packet, ClientPacketListener connection) {
        if (!running()) return false;
        if (interceptCommonPacket(packet, connection)) return true;
        if (instance.clientSpoofer.protectsTranslations() && packet instanceof ClientboundOpenSignEditorPacket open
                && Minecraft.getInstance().level != null
                //? if <26.3 {
                && Minecraft.getInstance().level.getBlockEntity(open.getPos()) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                //?} else {
                /*&& Minecraft.getInstance().level.getBlockEntity(open.pos()) instanceof net.minecraft.world.level.block.entity.SignBlockEntity sign) {
                *///?}
            //? if <26.3 {
            instance.clientSpoofer.protectSign(sign, open.isFrontText());
            //?} else {
            /*instance.clientSpoofer.protectSign(sign, open.slot() == net.minecraft.world.level.block.entity.SignTextSlot.FRONT);
            *///?}
        }
        MODULES.get(Blink.class).observeIncoming(packet);
        MODULES.get(LagRange.class).observeIncoming(packet);
        instance.anchorMacro.observeIncoming(packet);
        var jumpReset = MODULES.get(JumpReset.class);
        var backtrack = MODULES.get(Backtrack.class);
        if (jumpReset.handlesPackets() || instance.clientSpoofer.enabled() && packet instanceof ClientboundBundlePacket) {
            if (packet instanceof ClientboundBundlePacket bundle) {
                for (Packet<?> child : bundle.subPackets()) {
                    if (!interceptPlayPacket(child, connection)) applyPlayPacket(child, connection);
                }
                return true;
            }
            if (jumpReset.intercept(packet, connection, backtrack)) return true;
        }
        return backtrack.enabled() && backtrack.intercept(packet, connection);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    public static void applyPlayPacket(Packet packet, ClientPacketListener connection) {
        if (!connection.shouldHandleMessage(packet)) return;
        try {
            packet.handle(connection);
        } catch (Exception failure) {
            connection.onPacketError(packet, failure);
        }
    }
    public static void endTick() {
        LogicClient client = instance;
        if (client == null || !client.running) return;
        if (client.shutdownRequested) { client.shutdown(); return; }
        MODULES.get(SprintReset.class).finishMovementTick();
        client.guiConfig.updateMovement(client.clickGui.open());
        client.snapTap.update();
        Logic.events().post(new ClientTickEvent());
        client.applyCaptureSettings();
        client.clickGui.updateProtectedCursor();
        client.config.tick();
    }
    private static void startClient() {
        LogicClient client = instance;
        if (client == null || !client.running) return;
        var mc = net.minecraft.client.Minecraft.getInstance();
        client.previousPauseOnLostFocus = mc.options.pauseOnLostFocus;
        mc.options.pauseOnLostFocus = false;
        client.attachOverlay();
    }
    public static void stopClient() {
        LogicClient client = instance;
        if (client != null && client.running) client.shutdown();
    }
    private void shutdown() {
        if (!running) return;
        running = false;
        try { config.save(); } catch (Throwable failure) { Logic.LOGGER.warn("Could not save settings during unload", failure); }
         
        for (var module : java.util.List.copyOf(MODULES.all())) {
            try { module.setEnabled(false); }
            catch (Throwable failure) { Logic.LOGGER.warn("Cleanup failed for {}", module.name(), failure); }
        }
        try { guiConfig.updateMovement(false); } catch (Throwable failure) { Logic.LOGGER.warn("Input cleanup failed", failure); }
        Logic.events().clear();
        dev.technix.logic.client.render.WorldProjection.clear();
        try {
            detachOverlay();
            if (attachedOverlay != null && attachedOverlay.elements().isEmpty()) {
                if (ActiveRenderers.get() == attachedOverlay) ActiveRenderers.set(null);
                attachedOverlay.close();
            } else if (attachedOverlay != null) attachedOverlay.setStreamproof(previousCaptureProtection);
        } catch (Throwable failure) { Logic.LOGGER.warn("Overlay cleanup failed", failure); }
        attachedOverlay = null;
        try {
            var mc = net.minecraft.client.Minecraft.getInstance();
            mc.options.pauseOnLostFocus = previousPauseOnLostFocus;
            net.minecraft.client.KeyMapping.releaseAll();
            if (mc.player != null && mc.gui.screen() == null && mc.isWindowActive()) {
                net.minecraft.client.KeyMapping.setAll();
                mc.mouseHandler.grabMouse();
            }
        } catch (Throwable failure) { Logic.LOGGER.warn("Could not restore input state", failure); }
        try { dev.technix.logic.inject.Bootstrap.stop(); }
        catch (Throwable failure) { Logic.LOGGER.warn("Native unload failed; client hooks remain disabled", failure); }
        MODULES.clear();
        config = null;
        instance = null;
        Logic.LOGGER.info("Logic unloaded for this session");
    }


    private void attachOverlay() {
        OverlayRenderer overlay = ActiveRenderers.get();
        if (overlay == null) {
            Logic.LOGGER.warn("No active Mica renderer; the click GUI will not draw.");
            return;
        }
        attachedOverlay = overlay;
        previousCaptureProtection = overlay.isStreamproofRequested();
        overlay.setStreamproof(interfaceModule.screenshareProtection.get());
        overlay.registerElement(clickGuiRenderer);
        Logic.LOGGER.info("click GUI attached to the Mica renderer");
    }

    private void detachOverlay() {
        OverlayRenderer overlay = attachedOverlay;
        if (overlay != null) {
            overlay.unregisterElement(clickGuiRenderer);
        }
    }

    private void applyCaptureSettings() {
        OverlayRenderer overlay = ActiveRenderers.get();
        if (overlay != null && overlay.isStreamproofRequested() != interfaceModule.screenshareProtection.get()) {
            overlay.setStreamproof(interfaceModule.screenshareProtection.get());
        }
    }
}


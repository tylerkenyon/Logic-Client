package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.WorldBox;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import dev.technix.logic.event.impl.Render2DEvent;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.network.protocol.ping.ServerboundPingRequestPacket;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayDeque;

public final class Blink extends Module {
    private static final int MAX_PACKETS = 512;
    private final NumberSetting timeout = registerSetting(new NumberSetting("Auto release (ms)", "", 1000, 250, 3000, 50));
    private final BooleanSetting marker = registerSetting(new BooleanSetting("Start marker", "", true));
    private record Pending(Packet<?> packet, ChannelFutureListener listener, boolean flush) {}
    private final ArrayDeque<Pending> pending = new ArrayDeque<>();
    private Connection connection;
    private ClientLevel level;
    private LocalPlayer player;
    private Vec3 origin;
    private long started;
    private boolean replaying;

    public boolean replaying() { return replaying; }

    public Blink() { super("Blink", "Buffer movement and actions until release.", Category.PLAYER); }

    @Override
    protected void onEnable() {
        var mc = mc();
        player = mc.player;
        level = mc.level;
        var listener = mc.getConnection();
        connection = listener == null ? null : listener.getConnection();
        origin = player == null ? null : player.position();
        started = System.nanoTime();
    }

    private boolean sameSession() {
        var mc = mc();
        var listener = mc.getConnection();
        return connection != null && connection.isConnected() && player != null
            && mc.player == player && mc.level == level && listener != null && listener.getConnection() == connection;
    }

    public boolean intercept(Connection sending, Packet<?> packet, ChannelFutureListener listener, boolean flush) {
        if (!enabled() || sending != connection || !sameSession() || !buffersGameplayPacket(packet)) return false;
        if (pending.size() >= MAX_PACKETS || expired() || player.isPassenger() || !player.isAlive()) {
            setEnabled(false);
            return false;
        }
        pending.addLast(new Pending(packet, listener, flush));
        return true;
    }

    public static boolean buffersGameplayPacket(Packet<?> packet) {
         
        return packet instanceof ServerboundMovePlayerPacket || packet instanceof ServerboundPlayerInputPacket
            || packet instanceof ServerboundPingRequestPacket
            || packet instanceof ServerboundPlayerCommandPacket || packet instanceof ServerboundClientTickEndPacket
            || packet instanceof ServerboundAttackPacket || packet instanceof ServerboundInteractPacket
            || packet instanceof ServerboundSwingPacket || packet instanceof ServerboundUseItemPacket
            || packet instanceof ServerboundUseItemOnPacket || packet instanceof ServerboundPlayerActionPacket
            || packet instanceof ServerboundSetCarriedItemPacket;
    }

    public void observeIncoming(Packet<?> packet) {
        if (!enabled()) return;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (Packet<?> child : bundle.subPackets()) observeIncoming(child);
        } else if (packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket || packet instanceof ClientboundDisconnectPacket || packet.isTerminal()) {
            pending.clear();
            setEnabled(false);
        }
    }

    private boolean expired() { return System.nanoTime() - started >= timeout.get() * 1_000_000; }

    @Subscribe
    private void tick(ClientTickEvent event) {
        if (!sameSession() || !player.isAlive()) {
            pending.clear();
            setEnabled(false);
        } else if (expired() || player.isPassenger()) setEnabled(false);
    }

    public String statusLabel() { return pending.size() + " queued"; }

    @Subscribe
    private void render(Render2DEvent event) {
        var frame = WorldProjection.current();
        if (marker.get() && origin != null && frame != null && frame.level() == level && sameSession()) {
            WorldBox.draw(event.context(), frame, origin, player.getBbWidth(), player.getBbHeight(), 0, "Blue", false, 1.5f);
        }
    }

    @Override
    protected void onDisable() {
        replaying = true;
        try {
            if (!pending.isEmpty() && sameSession() && player.isAlive()) {
                boolean flushAfter = !pending.getLast().flush();
                Pending entry;
                while ((entry = pending.pollFirst()) != null) {
                    connection.send(entry.packet(), entry.listener(), entry.flush());
                }
                if (flushAfter) connection.flushChannel();
            }
        } finally {
            replaying = false;
            pending.clear();
            connection = null;
            level = null;
            player = null;
            origin = null;
        }
    }
}

package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.combat.PacketDelayQueue;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.WorldBox;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.*;
import io.netty.channel.ChannelFutureListener;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.common.ServerboundKeepAlivePacket;
import net.minecraft.network.protocol.game.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

import java.util.List;

 
public final class LagRange extends Module {
    private final NumberSetting latency = registerSetting(new NumberSetting("Packet delay",
        "Maximum time in milliseconds to hold outgoing gameplay packets while approaching a target.", 150, 0, 500, 10));
    private final NumberSetting range = registerSetting(new NumberSetting("Range",
        "Start delaying packets within this distance of a target, in blocks.", 12.5, 1, 20, 0.5));
    private final BooleanSetting weapon = registerSetting(new BooleanSetting("Weapons only",
        "Activate while holding a sword or axe.", true));
    private final BooleanSetting sprintRelease = registerSetting(new BooleanSetting("Release on sprint stop",
        "Send queued packets when sprinting ends, including sprint-reset actions.", true));
    private final BooleanSetting potionRelease = registerSetting(new BooleanSetting("Pause for potions",
        "Release packets while holding a splash potion and pause for 15 ticks afterward.", true));
    private final BooleanSetting marker = registerSetting(new BooleanSetting("Show server position",
        "Draw your last transmitted position while approaching in third person.", true));
    private final ModeSetting color = registerSetting(new ModeSetting("Marker colour",
        "Colour of the server-position box.", "Blue", "Silver", "Blue", "Green", "Rose", "Amber"));
    private final NumberSetting thickness = registerSetting(new NumberSetting("Marker thickness",
        "Outline thickness of the server-position box.", 1.5, 0.5, 3, 0.5));
    private final BooleanSetting filled = registerSetting(new BooleanSetting("Fill marker",
        "Draw translucent faces inside the server-position box.", false));
    private record Pending(Packet<?> packet, ChannelFutureListener listener, boolean flush) { }
    private final PacketDelayQueue<Pending> pending = new PacketDelayQueue<>(512);
    private Connection connection;
    private ClientLevel level;
    private LocalPlayer player;
    private LivingEntity target;
    private Vec3 serverPosition;
    private boolean active, approaching, released, replaying;
    private int potionPause;

    public boolean replaying() { return replaying; }

    public LagRange() {
        super("Lag Range", "Delay outgoing movement while closing on an opponent.", Category.COMBAT);
        color.visibleWhen(marker::get);
        thickness.visibleWhen(marker::get);
        filled.visibleWhen(marker::get);
    }

    private boolean sameSession() {
        var mc = mc();
        return connection != null && connection.isConnected() && player != null && player.isAlive()
            && mc.player == player && mc.level == level && mc.getConnection() != null
            && mc.getConnection().getConnection() == connection;
    }

    private void bindSession() {
        clear();
        var mc = mc();
        if (mc.player == null || mc.level == null || mc.getConnection() == null) return;
        player = mc.player;
        level = mc.level;
        connection = mc.getConnection().getConnection();
        serverPosition = player.position();
    }

    @Subscribe(priority = 50)
    private void tick(ClientTickEvent event) {
        if (!sameSession()) bindSession();
        if (!sameSession()) return;
        var blink = LogicClient.MODULES.get(Blink.class);
        if (blink.enabled() || blink.replaying()) { suspend(); return; }
        if (potionRelease.get() && (player.getMainHandItem().is(Items.SPLASH_POTION)
                || player.getOffhandItem().is(Items.SPLASH_POTION))) potionPause = 15;
        else if (!potionRelease.get()) potionPause = 0;
        else if (potionPause > 0) { potionPause--; deactivate(); return; }
        if (!CombatTargets.canAct(mc()) || player.isPassenger() || potionPause > 0 || latency.get() <= 0
                || weapon.get() && !player.getMainHandItem().is(ItemTags.SWORDS) && !player.getMainHandItem().is(ItemTags.AXES)) {
            deactivate();
            return;
        }
        target = null;
        double best = range.get();
        best *= best;
        for (var candidate : level.players()) {
            double distance = player.distanceToSqr(candidate);
            if (distance < best && CombatTargets.valid(mc(), candidate, true, true, false)) {
                target = candidate;
                best = distance;
            }
        }
        if (target == null) { deactivate(); return; }
        active = true;
        pump();
    }

    public boolean intercept(Connection sending, Packet<?> packet, ChannelFutureListener listener, boolean flush, boolean blinkReplay) {
        if (!enabled() || sending != connection || !sameSession()) return false;
        if (replaying) return false;
        if (blinkReplay) { observeSent(packet); return false; }
        pump();
        if (packet instanceof ServerboundPlayerCommandPacket command && sprintRelease.get()
                && command.getAction() == ServerboundPlayerCommandPacket.Action.STOP_SPRINTING) releaseForAction();
        if (packet instanceof ServerboundAttackPacket && target != null && target.hurtTime < 5) releaseForAction();
        if (packet instanceof ServerboundUseItemPacket use && potionRelease.get()
                //? if <26.3 {
                && player.getItemInHand(use.getHand()).is(Items.SPLASH_POTION)) {
                //?} else {
                /*&& player.getItemInHand(use.hand()).is(Items.SPLASH_POTION)) {
                *///?}
            potionPause = 15;
            deactivate();
        }
        if (!Blink.buffersGameplayPacket(packet)) {
             
            if (!(packet instanceof ServerboundKeepAlivePacket)) releaseForAction();
            return false;
        }
        if (packet instanceof ServerboundMovePlayerPacket move && move.hasPosition() && target != null) {
            Vec3 position = new Vec3(move.getX(serverPosition.x), move.getY(serverPosition.y), move.getZ(serverPosition.z));
            Vec3 opponent = LogicClient.MODULES.get(Backtrack.class).serverPosition(target);
            approaching = opponent.distanceToSqr(serverPosition) > opponent.distanceToSqr(position);
        }
        if (active && approaching && !released && latency.get() > 0) {
            if (pending.offer(new Pending(packet, listener, flush), System.nanoTime())) return true;
            releaseForAction();
        } else if (pending.size() > 0) releasePending();
        observeSent(packet);
        return false;
    }

    private void observeSent(Packet<?> packet) {
        if (packet instanceof ServerboundMovePlayerPacket move) {
            released = false;
            if (move.hasPosition()) serverPosition = new Vec3(move.getX(serverPosition.x), move.getY(serverPosition.y), move.getZ(serverPosition.z));
        }
    }

    private void releaseForAction() { releasePending(); released = true; }

    private void send(List<Pending> packets) {
        if (packets.isEmpty() || !sameSession()) return;
        replaying = true;
        try {
            for (var entry : packets) {
                connection.send(entry.packet(), entry.listener(), entry.flush());
                observeSent(entry.packet());
            }
            if (!packets.getLast().flush()) connection.flushChannel();
        } finally { replaying = false; }
    }

    private void releasePending() {
        if (!replaying) send(pending.drain());
    }

    public void suspend() {
        active = approaching = false;
        releaseForAction();
    }

    private void deactivate() {
        active = approaching = released = false;
        target = null;
        releasePending();
    }

    @Subscribe(priority = 100)
    private void attack(AttackEvent event) {
        if (sameSession() && event.target() instanceof LivingEntity living && living.hurtTime < 5) releaseForAction();
    }

    @Subscribe private void toggle(ModuleToggleEvent event) {
        if (event.module() instanceof Blink && event.enabled()) suspend();
    }

    public void observeIncoming(Packet<?> packet) {
        if (!enabled()) return;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (var child : bundle.subPackets()) observeIncoming(child);
        } else if (packet instanceof ClientboundPlayerPositionPacket || packet instanceof ClientboundRespawnPacket
                || packet instanceof ClientboundLoginPacket || packet instanceof ClientboundDisconnectPacket || packet.isTerminal()) clear();
        //? if <26.3 {
        else if (target != null && packet instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(target.getId())) deactivate();
        //?} else {
        /*else if (target != null && packet instanceof ClientboundRemoveEntitiesPacket remove && remove.entityIds().contains(target.getId())) deactivate();
        *///?}
    }

    private void pump() {
        if (!sameSession()) { clear(); return; }
        if (active && (!CombatTargets.canAct(mc()) || target == null || target.isRemoved()
                || !CombatTargets.valid(mc(), target, true, true, false))) { deactivate(); return; }
        send(pending.release(System.nanoTime(), (long)(latency.get() * 1_000_000)));
    }

    @Subscribe private void frame(FrameUpdateEvent event) { pump(); }

    @Subscribe private void render(Render2DEvent event) {
        var frame = WorldProjection.current();
        if (!active || pending.size() == 0 || !marker.get() || serverPosition == null
                || !sameSession() || mc().options.getCameraType().isFirstPerson() || frame == null || frame.level() != level) return;
         
        if (serverPosition.distanceToSqr(WorldProjection.entityPosition(player, frame.partialTick())) < 1.0e-6) return;
        WorldBox.draw(event.context(), frame, serverPosition,
            player.getBbWidth(), player.getBbHeight(), 0, color.get(), filled.get(), thickness.get().floatValue());
    }

    private void clear() {
        pending.clear();
        connection = null;
        level = null;
        player = null;
        target = null;
        serverPosition = null;
        active = approaching = released = false;
        potionPause = 0;
    }

    @Override protected void onDisable() { releasePending(); clear(); }
}

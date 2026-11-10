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
import dev.technix.logic.event.impl.ClientTickEvent;
import dev.technix.logic.event.impl.FrameUpdateEvent;
import dev.technix.logic.event.impl.Render2DEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.*;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;


public final class Backtrack extends Module {
    private final ModeSetting mode = registerSetting(new ModeSetting("Mode", "How incoming updates are delayed", "Standard", "Standard", "Freeze"));
    private final NumberSetting releaseDistance = registerSetting(new NumberSetting("Release distance", "Release when the real target exceeds this distance in blocks", 15, 1, 50, 0.5));
    private final NumberSetting releaseTicks = registerSetting(new NumberSetting("Release after ticks", "Release everything after this many ticks; 0 disables", 0, 0, 400, 1));
    private final BooleanSetting batchRelease = registerSetting(new BooleanSetting("Partial release", "Periodically release part of the queued packet history", false));
    private final NumberSetting releaseAmount = registerSetting(new NumberSetting("Release amount", "Ticks of queued history to release each interval", 1, 1, 100, 1));
    private final NumberSetting releaseInterval = registerSetting(new NumberSetting("Release interval", "Ticks between partial releases", 20, 1, 400, 1));
    private final NumberSetting delay = registerSetting(new NumberSetting("Delay", "Maximum packet hold in milliseconds", 150, 0, 500, 10));
    private final NumberSetting minRange = registerSetting(new NumberSetting("Min distance", "Minimum target distance", 1, 0, 6, 0.1));
    private final NumberSetting maxRange = registerSetting(new NumberSetting("Max distance", "Maximum target distance", 4, 1, 8, 0.1));
    private final NumberSetting maxHurt = registerSetting(new NumberSetting("Max hurt time", "Maximum target hurt ticks", 10, 0, 10, 1));
    private final NumberSetting cooldown = registerSetting(new NumberSetting("Cooldown", "Ticks to wait after releasing", 10, 0, 40, 1));
    private final BooleanSetting disableOnHit = registerSetting(new BooleanSetting("Disable on hit", "Release on your knockback packet", true));
    private final BooleanSetting playersOnly = registerSetting(new BooleanSetting("Players only", "Disable to track living mobs as well as players.", false));
    private final BooleanSetting requireMouse = registerSetting(new BooleanSetting("Hold attack", "Require the attack button", true));
    private final BooleanSetting requireWeapon = registerSetting(new BooleanSetting("Require weapon", "Require a sword or axe", true));
    private final BooleanSetting indicator = registerSetting(new BooleanSetting("Real position", "Show the target's undelayed position", true));
    private final BooleanSetting rotate = registerSetting(new BooleanSetting("Rotate indicator", "Follow target body rotation", false));
    private final BooleanSetting filled = registerSetting(new BooleanSetting("Filled indicator", "Fill the position box", false));
    private final NumberSetting lineWidth = registerSetting(new NumberSetting("Line width", "Indicator outline thickness", 1, 0.5, 3, 0.5));
    private final ModeSetting color = registerSetting(new ModeSetting("Indicator color", "", "Blue", "Silver", "Blue", "Green", "Rose", "Amber"));
    private final PacketDelayQueue<Packet<?>> pending = new PacketDelayQueue<>(4096);
    private final VecDeltaCodec codec = new VecDeltaCodec();
    private LivingEntity target;
    private ClientLevel level;
    private ClientPacketListener connection;
    private long lastAttack;
    private boolean holding;
    private String activeMode = "Standard";
    private int cooldownTicks, smoothing, freezeTicks;
    private Vec3 serverPosition, previousIndicator, currentIndicator;
    private float serverYaw;

    public Backtrack() {
        super("Backtrack", "Delays incoming updates during a recent fight.", Category.COMBAT);
        for (var setting : settings()) {
            if (setting != mode) setting.visibleWhen(() -> !freezeMode());
        }
        releaseDistance.visibleWhen(this::freezeMode);
        releaseTicks.visibleWhen(this::freezeMode);
        batchRelease.visibleWhen(this::freezeMode);
        releaseAmount.visibleWhen(() -> freezeMode() && batchRelease.get());
        releaseInterval.visibleWhen(() -> freezeMode() && batchRelease.get());
        rotate.visibleWhen(() -> !freezeMode() && indicator.get());
        filled.visibleWhen(() -> !freezeMode() && indicator.get());
        lineWidth.visibleWhen(() -> !freezeMode() && indicator.get());
        color.visibleWhen(() -> !freezeMode() && indicator.get());
    }

    private boolean freezeMode() { return mode.get().equals("Freeze"); }

    private void syncMode() {
        if (!activeMode.equals(mode.get())) {
            stop(true);
            discard();
            activeMode = mode.get();
        }
    }

    public static boolean acceptsTargetType(Class<? extends Entity> type, boolean playersOnly) {
        return LivingEntity.class.isAssignableFrom(type) && (!playersOnly || Player.class.isAssignableFrom(type));
    }

    public void recordAttack(Entity entity) {
        syncMode();
        if (entity == null || !acceptsTargetType(entity.getClass(), freezeMode() || playersOnly.get())
                || entity == mc().player || !entity.isAlive() || entity.isRemoved() || LogicClient.isBot(entity)) return;
        LivingEntity living = (LivingEntity) entity;
        if (target != living) {
            stop(true);
            target = living;
            level = mc().level;
            connection = mc().getConnection();
            serverPosition = living.getPositionCodec().getBase();
            codec.setBase(serverPosition);
            serverYaw = living.getYRot();
        }
        lastAttack = System.nanoTime();
        if (freezeMode() && sameSession() && mc().player.position().distanceTo(serverPosition) <= releaseDistance.get() && !holding) {
            holding = true;
            freezeTicks = 0;
            previousIndicator = currentIndicator = living.position();
            smoothing = 3;
        }
    }

    private boolean sameSession() {
        return level != null && level == mc().level && connection == mc().getConnection()
            && connection != null && connection.isAcceptingMessages();
    }

    private boolean eligible() {
        if (!sameSession() || !CombatTargets.canAct(mc()) || target == null || target.isRemoved()
                || !target.isAlive() || LogicClient.isBot(target) || target.hurtTime > maxHurt.get()
                || System.nanoTime() - lastAttack > 1_000_000_000L || cooldownTicks > 0 || delay.get() <= 0) return false;
        if (playersOnly.get() && !(target instanceof Player)) return false;
        if (requireMouse.get() && !mc().options.keyAttack.isDown()) return false;
        var stack = mc().player.getMainHandItem();
        if (requireWeapon.get() && !stack.is(ItemTags.SWORDS) && !stack.is(ItemTags.AXES)) return false;
        double distance = mc().player.distanceTo(target);
        return distance >= minRange.get() && distance <= maxRange.get();
    }

    public String statusLabel() {
        if (holding && pending.size() > 0) return pending.size() + " queued";
        if (!sameSession() || target == null) return "No target";
        if (freezeMode()) return "Ready";
        if (cooldownTicks > 0) return "Cooldown";
        if (requireMouse.get() && !mc().options.keyAttack.isDown()) return "Hold attack";
        if (requireWeapon.get()) {
            var stack = mc().player.getMainHandItem();
            if (!stack.is(ItemTags.SWORDS) && !stack.is(ItemTags.AXES)) return "Need weapon";
        }
        return "Ready";
    }

    public boolean intercept(Packet<?> packet, ClientPacketListener listener) {
        syncMode();
        if (!sameSession()) { discard(); return false; }
        if (packet.isTerminal() || packet instanceof net.minecraft.network.protocol.common.ClientboundDisconnectPacket
                || packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket) {
            discard();
            return false;
        }
        if (target == null) return false;
        if (packet instanceof ClientboundBundlePacket bundle) {
            for (var child : bundle.subPackets()) if (!intercept(child, listener)) apply(child, listener);
            return true;
        }
        boolean barrier = packet instanceof ClientboundPlayerPositionPacket
            || (packet instanceof ClientboundSetEntityMotionPacket motion && !freezeMode() && disableOnHit.get()
                && mc().player != null && motion.id() == mc().player.getId())
            || (packet instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(target.getId()));
        if (barrier) { stop(true); return false; }

        if (packet instanceof ClientboundSetHealthPacket || packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundSoundEntityPacket || packet instanceof ClientboundDamageEventPacket
                || packet instanceof ClientboundHurtAnimationPacket
                || (packet instanceof ClientboundEntityEventPacket status && status.getEntity(level) == target)) return false;
        if (freezeMode()) {
            if (!holding) return false;
            trackPosition(packet);
            if (!CombatTargets.canAct(mc()) || !target.isAlive() || target.isRemoved()
                    || mc().player.position().distanceTo(serverPosition) > releaseDistance.get()) {
                stop(true);
                target = null;
                return false;
            }
            if (!pending.offer(packet, freezeTicks)) { stop(true); target = null; return false; }
            return true;
        }
        if (!eligible()) { stop(true); return false; }

        if (!holding) {
            serverPosition = target.getPositionCodec().getBase();
            codec.setBase(serverPosition);
        }
        boolean moved = trackPosition(packet);
        double shownDistance = mc().player.position().distanceTo(target.position());
        double realDistance = mc().player.position().distanceTo(serverPosition);
        if (holding && realDistance < shownDistance - 0.05) { stop(true); return false; }
        if (!holding && moved && realDistance > shownDistance + 0.01) {
            holding = true;
            previousIndicator = currentIndicator = target.position();
            smoothing = 3;
        }
        if (!holding) return false;
        if (!pending.offer(packet, System.nanoTime())) { stop(true); return false; }
        return true;
    }

    private boolean trackPosition(Packet<?> packet) {
        boolean moved = false;
        if (packet instanceof ClientboundMoveEntityPacket move && move.getEntity(level) == target) {
            if (move.hasPosition()) {
                serverPosition = codec.decode(move.getXa(), move.getYa(), move.getZa());
                codec.setBase(serverPosition);
                moved = true;
            }
            if (move.hasRotation()) serverYaw = move.getYRot();
        } else if (packet instanceof ClientboundEntityPositionSyncPacket sync && sync.id() == target.getId()) {
            serverPosition = sync.values().position();
            serverYaw = sync.values().yRot();
            codec.setBase(serverPosition);
            moved = true;
        } else if (packet instanceof ClientboundTeleportEntityPacket teleport && teleport.id() == target.getId()) {
            var absolute = PositionMoveRotation.calculateAbsolute(new PositionMoveRotation(serverPosition, Vec3.ZERO,
                serverYaw, target.getXRot()), teleport.change(), teleport.relatives());
            serverPosition = absolute.position();
            serverYaw = absolute.yRot();
            codec.setBase(serverPosition);
            moved = true;
        }
        if (moved) smoothing = 3;
        return moved;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static void apply(Packet packet, ClientPacketListener listener) {
        if (listener.shouldHandleMessage(packet)) packet.handle(listener);
    }

    public void flushPending() { stop(true); }

    public Vec3 serverPosition(LivingEntity entity) {
        return enabled() && sameSession() && target == entity && serverPosition != null
            ? serverPosition : entity.getPositionCodec().getBase();
    }

    private void stop(boolean release) {
        boolean wasHolding = holding;
        holding = false;
        if (release && sameSession()) {
            var packets = pending.drain();
            for (Packet<?> packet : packets) apply(packet, connection);
            if (target != null && !target.isRemoved()) {
                serverPosition = target.getPositionCodec().getBase();
                codec.setBase(serverPosition);
            }
        } else pending.clear();
        if (wasHolding && !freezeMode()) cooldownTicks = cooldown.get().intValue();
        previousIndicator = currentIndicator = null;
    }

    public void discard() {
        pending.clear();
        holding = false;
        target = null;
        level = null;
        connection = null;
        previousIndicator = currentIndicator = serverPosition = null;
        cooldownTicks = 0;
        freezeTicks = 0;
    }

    private void pump() {
        syncMode();
        if (!sameSession()) { discard(); return; }
        if (!holding) return;
        if (freezeMode()) {
            if (!CombatTargets.canAct(mc()) || target == null || target.isRemoved() || !target.isAlive()
                    || mc().player.position().distanceTo(serverPosition) > releaseDistance.get()) { stop(true); target = null; }
            return;
        }
        if (!eligible()) { stop(true); return; }
        for (Packet<?> packet : pending.release(System.nanoTime(), (long) (delay.get() * 1_000_000))) apply(packet, connection);
    }

    @Subscribe
    private void onFrame(FrameUpdateEvent event) { pump(); }

    @Subscribe
    private void onTick(ClientTickEvent event) {
        if (cooldownTicks > 0) cooldownTicks--;
        pump();
        if (holding && freezeMode()) {
            freezeTicks++;
            if (releaseTicks.get() > 0 && freezeTicks >= releaseTicks.get()) {
                stop(true);
                target = null;
            } else if (batchRelease.get() && freezeTicks % releaseInterval.get().intValue() == 0) {
                for (Packet<?> packet : pending.releaseSpan(releaseAmount.get().longValue())) apply(packet, connection);
            }
        }
        if (holding && currentIndicator != null) {
            previousIndicator = currentIndicator;
            currentIndicator = currentIndicator.lerp(serverPosition, smoothing > 0 ? 1.0 / smoothing-- : 1);
            if (!freezeMode() && mc().hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() != target) stop(true);
        }
    }

    @Subscribe
    private void onRender(Render2DEvent event) {
        var frame = WorldProjection.current();
        if (!holding || (!freezeMode() && !indicator.get()) || currentIndicator == null || frame == null || frame.level() != level) return;
        Vec3 position = freezeMode() ? serverPosition : previousIndicator.lerp(currentIndicator, frame.partialTick());
        WorldBox.draw(event.context(), frame, position, target.getBbWidth(), target.getBbHeight(),
            !freezeMode() && rotate.get() ? serverYaw : 0, freezeMode() ? "Blue" : color.get(),
            !freezeMode() && filled.get(), freezeMode() ? 1 : lineWidth.get().floatValue());
    }

    @Override
    protected void onDisable() { stop(true); discard(); }
}

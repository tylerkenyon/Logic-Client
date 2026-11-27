package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;

import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.combat.BacktrackHistory;
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
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.level.ClipContext;


public final class Backtrack extends Module {
    private final ModeSetting mode = registerSetting(new ModeSetting("Mode", "How incoming updates are delayed", "Standard", "Standard", "Freeze", "Hitbox"));
    private final NumberSetting maxBlocks = registerSetting(new NumberSetting(
        "Max blocks", "Maximum recorded breadcrumb path length before releasing everything", 6, 1, 30, 0.5));
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
    private final BacktrackHistory history = new BacktrackHistory();
    private long sequence, releasedSequence;
    private EntityHitResult substitutedHit;
    private HitResult originalHit;
    private BacktrackHistory.Selection attackSelection;
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
            if (setting != mode) setting.visibleWhen(() -> !freezeLikeMode());
        }
        maxBlocks.visibleWhen(this::hitboxMode);
        releaseDistance.visibleWhen(this::freezeLikeMode);
        releaseTicks.visibleWhen(this::freezeLikeMode);
        batchRelease.visibleWhen(this::freezeLikeMode);
        releaseAmount.visibleWhen(() -> freezeLikeMode() && batchRelease.get());
        releaseInterval.visibleWhen(() -> freezeLikeMode() && batchRelease.get());
        rotate.visibleWhen(() -> !freezeLikeMode() && indicator.get());
        disableOnHit.visibleWhen(() -> !freezeLikeMode());
        filled.visibleWhen(() -> hitboxMode() || !freezeMode() && indicator.get());
        lineWidth.visibleWhen(() -> hitboxMode() || !freezeMode() && indicator.get());
        color.visibleWhen(() -> hitboxMode() || !freezeMode() && indicator.get());
    }

    private boolean freezeMode() { return mode.get().equals("Freeze"); }
    private boolean freezeLikeMode() { return freezeMode() || hitboxMode(); }
    private boolean hitboxMode() { return mode.get().equals("Hitbox"); }

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
        if (entity == null || !acceptsTargetType(entity.getClass(), freezeLikeMode() || playersOnly.get())
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
        if (freezeLikeMode() && sameSession() && !holding
                && mc().player.position().distanceTo(serverPosition) <= releaseDistance.get()) {
            serverPosition = living.getPositionCodec().getBase();
            codec.setBase(serverPosition);
            holding = true;
            freezeTicks = 0;
            releasedSequence = sequence;
            previousIndicator = currentIndicator = living.position();
            smoothing = 3;
            if (hitboxMode()) addSample();
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
        if (freezeLikeMode()) return "Ready";
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
            || (packet instanceof ClientboundSetEntityMotionPacket motion && !freezeLikeMode() && disableOnHit.get()
                && mc().player != null && motion.id() == mc().player.getId())
            //? if <26.3 {
            || (packet instanceof ClientboundRemoveEntitiesPacket remove && remove.getEntityIds().contains(target.getId()));
            //?} else {
            /*|| (packet instanceof ClientboundRemoveEntitiesPacket remove && remove.entityIds().contains(target.getId()));
            *///?}
        if (barrier) { stop(true); return false; }

        if (packet instanceof ClientboundSetHealthPacket || packet instanceof ClientboundSoundPacket
                || packet instanceof ClientboundSoundEntityPacket || packet instanceof ClientboundDamageEventPacket
                || packet instanceof ClientboundHurtAnimationPacket
                || (packet instanceof ClientboundEntityEventPacket status && status.getEntity(level) == target)) return false;
        if (freezeLikeMode()) {
            if (!holding) return false;
            boolean moved = trackPosition(packet);
            if (!CombatTargets.canAct(mc()) || !target.isAlive() || target.isRemoved()
                    || mc().player.position().distanceTo(serverPosition) > releaseDistance.get()) {
                stop(true);
                target = null;
                return false;
            }
            if (!pending.offer(packet, hitboxMode() ? ++sequence : freezeTicks, freezeTicks)) { stop(true); target = null; return false; }
            if (hitboxMode() && moved) {
                addSample();
                if (history.length() > maxBlocks.get()) { stop(true); target = null; }
            }
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
                //? if <26.3 {
                serverPosition = codec.decode(move.getXa(), move.getYa(), move.getZa());
                //?} else {
                /*serverPosition = move.getPositionDelta().decode(codec).endPosition();
                *///?}
                codec.setBase(serverPosition);
                moved = true;
            }
            if (move.hasRotation()) serverYaw = move.getYRot();
        } else if (packet instanceof ClientboundEntityPositionSyncPacket sync && sync.id() == target.getId()) {
            //? if <26.3 {
            serverPosition = sync.values().position();
            //?} else {
            /*serverPosition = sync.position().endPosition();
            *///?}
            //? if <26.3 {
            serverYaw = sync.values().yRot();
            //?} else {
            /*serverYaw = sync.yRot();
            *///?}
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

    private void addSample() {
        history.add(sequence, serverPosition, latestTargetBox());
    }

    private AABB latestTargetBox() {
        return target.getBoundingBox().move(serverPosition.subtract(target.position()));
    }

    private BacktrackHistory.Selection aimedSample() {
        if (!enabled() || !hitboxMode() || !holding || !sameSession() || !CombatTargets.canAct(mc())
                || target == null || target.isRemoved() || !CombatTargets.valid(mc(), target, true, false, false)) return null;
        Vec3 eye = mc().player.getEyePosition(1);
        double reach = mc().player.entityInteractionRange();
        Vec3 end = eye.add(mc().player.getViewVector(1).scale(reach));
        var block = level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, mc().player));
        if (block.getType() != HitResult.Type.MISS) end = block.getLocation();
        HitResult vanillaHit = substitutedHit != null && mc().hitResult == substitutedHit
            ? originalHit : mc().hitResult;
        if (vanillaHit instanceof EntityHitResult hit && hit.getEntity() != target
                && eye.distanceToSqr(hit.getLocation()) < eye.distanceToSqr(end)) end = hit.getLocation();
        return history.select(eye, end, latestTargetBox());
    }

    public void beforeKeybinds() {
        afterKeybinds();
        if (!enabled()) return;
        syncMode();
        var selected = aimedSample();
        if (selected == null) return;
        originalHit = mc().hitResult;
        substitutedHit = new EntityHitResult(target, selected.hit());
        mc().hitResult = substitutedHit;
    }

    public void afterKeybinds() {
        if (substitutedHit != null && mc().hitResult == substitutedHit) mc().hitResult = originalHit;
        substitutedHit = null;
        originalHit = null;
        attackSelection = null;
    }

    public boolean blocksAttack() {
        if (!enabled() || !hitboxMode() || !holding || !sameSession()
                || !(mc().hitResult instanceof EntityHitResult hit) || hit.getEntity() != target) return false;
        var selected = aimedSample();
        if (hit == substitutedHit) attackSelection = selected;
        return selected == null;
    }

    public net.minecraft.world.phys.AABB attackBox(LivingEntity entity) {
        if (entity == target && substitutedHit != null) {
            var selected = aimedSample();
            if (selected != null) return selected.sample().box();
        }
        return entity.getBoundingBox();
    }

    public void attackDispatched() {
        if (attackSelection == null || !sameSession() || !holding || !hitboxMode()) return;
        long through = attackSelection.sample().sequence();
        for (Packet<?> packet : pending.releaseThrough(through)) apply(packet, connection);
        releasedSequence = Math.max(releasedSequence, through);
        history.releaseThrough(through);
        attackSelection = null;
    }

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
        if (wasHolding && !freezeLikeMode()) cooldownTicks = cooldown.get().intValue();
        previousIndicator = currentIndicator = null;
        history.clear();
        afterKeybinds();
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
        history.clear();
        sequence = releasedSequence = 0;
        afterKeybinds();
    }

    private void pump() {
        syncMode();
        if (!sameSession()) { discard(); return; }
        if (!holding) return;
        if (freezeLikeMode()) {
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
        if (holding && freezeLikeMode()) {
            freezeTicks++;
            if (releaseTicks.get() > 0 && freezeTicks >= releaseTicks.get()) {
                stop(true);
                target = null;
            } else if (freezeLikeMode() && batchRelease.get() && freezeTicks % releaseInterval.get().intValue() == 0) {
                var released = pending.releaseSpan(releaseAmount.get().longValue());
                for (Packet<?> packet : released) apply(packet, connection);
                if (hitboxMode()) {
                    releasedSequence += released.size();
                    history.releaseThrough(releasedSequence);
                }
            }
        }
        if (holding && currentIndicator != null) {
            previousIndicator = currentIndicator;
            currentIndicator = currentIndicator.lerp(serverPosition, smoothing > 0 ? 1.0 / smoothing-- : 1);
            if (!freezeLikeMode() && mc().hitResult instanceof net.minecraft.world.phys.EntityHitResult hit && hit.getEntity() != target) stop(true);
        }
    }

    @Subscribe
    private void onRender(Render2DEvent event) {
        var frame = WorldProjection.current();
        if (hitboxMode()) {
            if (!holding || frame == null || frame.level() != level || target == null) return;
            WorldBox.draw(event.context(), frame, serverPosition, target.getBbWidth(), target.getBbHeight(),
                0, "Blue", false, 1);
            var samples = history.samples();
            for (int i = 1; i < samples.size(); i++) {
                WorldBox.line(event.context(), frame, samples.get(i - 1).box().getCenter(),
                    samples.get(i).box().getCenter(), color.get(), lineWidth.get().floatValue());
            }
            var selected = aimedSample();
            if (selected != null) {
                var box = selected.sample().box();
                WorldBox.draw(event.context(), frame, new Vec3((box.minX + box.maxX) * 0.5, box.minY, (box.minZ + box.maxZ) * 0.5),
                    (float) (box.maxX - box.minX), (float) (box.maxY - box.minY), 0,
                    color.get(), filled.get(), lineWidth.get().floatValue());
            }
            return;
        }
        if (!holding || (!freezeMode() && !indicator.get()) || currentIndicator == null || frame == null || frame.level() != level) return;
        Vec3 position = freezeMode() ? serverPosition : previousIndicator.lerp(currentIndicator, frame.partialTick());
        WorldBox.draw(event.context(), frame, position, target.getBbWidth(), target.getBbHeight(),
            !freezeMode() && rotate.get() ? serverYaw : 0, freezeMode() ? "Blue" : color.get(),
            !freezeMode() && filled.get(), freezeMode() ? 1 : lineWidth.get().floatValue());
    }

    @Override
    protected void onDisable() { stop(true); discard(); }
}

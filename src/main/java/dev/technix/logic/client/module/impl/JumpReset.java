package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.combat.JumpResetTiming;
import dev.technix.logic.client.combat.PacketDelayQueue;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket;
import net.minecraft.network.protocol.game.ClientboundLoginPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket;
import net.minecraft.network.protocol.game.ClientboundRespawnPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityMotionPacket;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.phys.EntityHitResult;
import java.util.concurrent.ThreadLocalRandom;

public final class JumpReset extends Module {
     
    private static final double MAX_KNOCKBACK_Y = 4500.0 / 8000;
    private final NumberSetting accuracy = registerSetting(new NumberSetting(
        "Accuracy", "Timing accuracy (%): 100 jumps immediately; lower values add up to three ticks", 100, 0, 100, 1));
    private final NumberSetting chance = registerSetting(new NumberSetting(
        "Chance", "Chance to jump on each incoming hit (%)", 100, 0, 100, 1));
    private final BooleanSetting onlyTargeting = registerSetting(new BooleanSetting(
        "Only while targeting", "Require a visible enemy under the crosshair", true));
    private final BooleanSetting hardReset = registerSetting(new BooleanSetting(
        "Hard reset", "Hold incoming knockback while airborne, then replay it before jumping.", false));
    private final NumberSetting hardResetDelay = registerSetting(new NumberSetting(
        "Hard reset max delay", "Maximum packet hold in milliseconds.", 200, 50, 1000, 10));
    private final NumberSetting hardResetDistance = registerSetting(new NumberSetting(
        "Hard reset distance", "Distance to a nearby opponent in blocks.", 5.3, 3, 12, 0.1));
    private final JumpResetTiming timing = new JumpResetTiming();
    private final PacketDelayQueue<Packet<?>> held = new PacketDelayQueue<>(4096);
    private ClientPacketListener heldConnection;
    private ClientLevel heldLevel;
    private net.minecraft.client.player.LocalPlayer heldPlayer;
    private long heldSince;
    private net.minecraft.client.player.LocalPlayer owner;
    private boolean controlling;

    public JumpReset() {
        super("Jump Reset", "Time a grounded jump when taking a hit.", Category.COMBAT);
        hardResetDelay.visibleWhen(hardReset::get);
        hardResetDistance.visibleWhen(hardReset::get);
    }

    public boolean handlesPackets() {
        return heldConnection != null || (enabled() && hardReset.get() && !mc().isLocalServer());
    }

    public boolean intercept(Packet<?> packet, ClientPacketListener connection, Backtrack backtrack) {
        boolean barrier = packet.isTerminal() || packet instanceof ClientboundDisconnectPacket
            || packet instanceof ClientboundLoginPacket || packet instanceof ClientboundRespawnPacket
            || packet instanceof ClientboundPlayerPositionPacket;
        if (heldConnection != null) {
            if (!sameSession() || barrier || !hardReset.get()) flush();
            else if (held.offer(packet, System.nanoTime())) return true;
            else { flush(); return false; }
        }
        if (barrier || !handlesPackets() || !CombatTargets.canAct(mc()) || mc().player.onGround()
                || !(packet instanceof ClientboundSetEntityMotionPacket motion) || motion.id() != mc().player.getId()
                || motion.movement().y <= 0 || motion.movement().y >= MAX_KNOCKBACK_Y
                || mc().player.isOnFire() || mc().player.hasEffect(MobEffects.JUMP_BOOST)
                || mc().player.hasEffect(MobEffects.POISON) || mc().player.hasEffect(MobEffects.WITHER)
                || !nearOpponent()) return false;

        if (backtrack.enabled()) backtrack.flushPending();
        heldConnection = connection;
        heldLevel = mc().level;
        heldPlayer = mc().player;
        heldSince = System.nanoTime();
        return held.offer(packet, heldSince);
    }

    public void startTick() {
        if (heldConnection == null) return;
        boolean active = sameSession() && hardReset.get() && CombatTargets.canAct(mc());
        boolean landed = active && mc().player.onGround();
        if (active && !landed && System.nanoTime() - heldSince < hardResetDelay.get() * 1_000_000
                && nearOpponent()) return;

        flush();
        if (landed && CombatTargets.canAct(mc()) && canJump()) {
            owner = mc().player;
            timing.consumeHit(owner.hurtTime);
            if (ThreadLocalRandom.current().nextDouble(100) < chance.get()) pressJump();
        }
    }

    private boolean sameSession() {
        var mc = mc();
        return heldConnection == mc.getConnection() && heldLevel == mc.level && heldPlayer == mc.player;
    }

    private boolean nearOpponent() {
        double range = hardResetDistance.get();
        var mc = mc();
        return mc.level.players().stream().anyMatch(player -> player.distanceToSqr(mc.player) <= range * range
            && CombatTargets.valid(mc, player, true, true, false));
    }

    private void flush() {
        if (heldConnection == null) return;
        ClientPacketListener connection = sameSession() ? heldConnection : null;
        heldConnection = null;
        heldLevel = null;
        heldPlayer = null;
        for (Packet<?> packet : held.drain()) {
            if (connection != null) LogicClient.applyPlayPacket(packet, connection);
        }
    }

    @Subscribe(priority = -100) private void onTick(ClientTickEvent event) {
        var mc = mc();
        boolean active = CombatTargets.canAct(mc);
        release(active);
        if (!active) { timing.reset(); owner = null; return; }
        if (owner != mc.player) { timing.reset(); owner = mc.player; }
        var random = ThreadLocalRandom.current();
        if (timing.update(mc.player.hurtTime, heldConnection == null && canJump(), chance.get(), accuracy.get(),
                random.nextDouble(), random.nextDouble())) pressJump();
    }

    private boolean canJump() {
        var mc = mc();
        boolean targeting = mc.hitResult instanceof EntityHitResult hit && hit.getEntity() instanceof LivingEntity enemy
            && CombatTargets.valid(mc, enemy, false, true, true);
        return mc.player.onGround() && !mc.player.isInWater() && !mc.player.isInLava()
            && !mc.player.isPassenger() && !mc.player.onClimbable()
            && !mc.options.keyJump.isDown() && (!onlyTargeting.get() || targeting);
    }

    private void pressJump() {
        mc().options.keyJump.setDown(true);
        controlling = true;
    }

    private void release(boolean physical) {
        if (controlling && mc().options != null)
            mc().options.keyJump.setDown(physical && MovementKeys.physicalDown(mc().options.keyJump));
        controlling = false;
    }

    @Override protected void onDisable() {
        flush();
        release(CombatTargets.canAct(mc()));
        timing.reset(); owner = null;
    }
}

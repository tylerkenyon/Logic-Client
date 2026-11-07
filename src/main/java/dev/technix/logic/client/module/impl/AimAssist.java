package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.combat.CombatMath;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import dev.technix.logic.event.impl.FrameUpdateEvent;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AimAssist extends Module {
    private final ModeSetting targetBone = registerSetting(new ModeSetting(
        "Target bone", "Choose the point to aim toward", "Chest", "Head", "Chest", "Pelvis", "Closest"));
    private final NumberSetting horizontalMidpoint = registerSetting(new NumberSetting(
        "Horizontal midpoint", "Aim offset: -100 left, 0 unchanged, 100 right; stays inside the hitbox", 0, -100, 100, 1));
    private final NumberSetting verticalMidpoint = registerSetting(new NumberSetting(
        "Vertical midpoint", "Aim offset: -100 down, 0 unchanged, 100 up; stays inside the hitbox", 0, -100, 100, 1));
    private final NumberSetting range = registerSetting(new NumberSetting(
        "Range", "Distance to lock on", 4, 1, 6, 0.1));
    private final NumberSetting fov = registerSetting(new NumberSetting(
        "Field of view", "target FOV", 60, 5, 180, 1));
    private final NumberSetting speed = registerSetting(new NumberSetting(
        "Turn speed", "Degrees per tick, smoothed between frames", 4, 0.5, 20, 0.5));
    private final NumberSetting horizontalSmoothing = registerSetting(new NumberSetting(
        "Horizontal smoothing", "Higher values ease horizontal turns more slowly", 1, 1, 20, 0.1));
    private final NumberSetting verticalSmoothing = registerSetting(new NumberSetting(
        "Vertical smoothing", "Higher values ease vertical turns more slowly", 1, 1, 20, 0.1));
    private final BooleanSetting holdAttack = registerSetting(new BooleanSetting(
        "Hold attack", "Only assist while held", true));
    private final BooleanSetting playersOnly = registerSetting(new BooleanSetting(
        "Players only", "exclude all other entities", true));
    private final BooleanSetting visibleOnly = registerSetting(new BooleanSetting(
        "Visibility check", "check if the player is visible", true));
    private final BooleanSetting ignoreTeams = registerSetting(new BooleanSetting(
        "Ignore teammates", "skip players on your team", true));

    private LivingEntity target;

    public AimAssist() {
        super("Aim Assist", "locks on to enemies.", Category.COMBAT);
    }

    @Subscribe
    private void onTick(ClientTickEvent event) {
        var mc = mc();
        if (!CombatTargets.canAct(mc) || (holdAttack.get() && !mc.options.keyAttack.isDown())) {
            target = null;
            return;
        }

        if (validTarget(target) && angularDistance(target, 1) <= fov.get() * 0.5 + 5) return;

        target = null;
        double bestAngle = fov.get() * 0.5;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity candidate) || !validTarget(candidate)) continue;
            double angle = angularDistance(candidate, 1);
            if (angle <= bestAngle) {
                bestAngle = angle;
                target = candidate;
            }
        }
    }

    @Subscribe
    private void onFrame(FrameUpdateEvent event) {
        if (dev.technix.logic.client.LogicClient.anchorBusy()) return;
        var mc = mc();
        if (!CombatTargets.canAct(mc) || (holdAttack.get() && !mc.options.keyAttack.isDown())
                || !validTarget(target)) {
            target = null;
            return;
        }
        Vec3 delta = targetDelta(target, event.partialTick());
        if (delta.lengthSqr() < 1.0e-12) return;
        float yaw = targetYaw(delta);
        float pitch = targetPitch(delta);
        if (Math.hypot(CombatMath.wrapDegrees(yaw - mc.player.getYRot()), pitch - mc.player.getXRot())
                > fov.get() * 0.5 + 5) {
            target = null;
            return;
        }

        float oldYaw = mc.player.getYRot(), oldPitch = mc.player.getXRot();
        double degreesPerSecond = speed.get() * 20;
        float nextYaw = CombatMath.smoothAngle(oldYaw, yaw, degreesPerSecond, event.deltaSeconds(), horizontalSmoothing.get());
        float nextPitch = Math.clamp(CombatMath.smoothAngle(
            oldPitch, pitch, degreesPerSecond, event.deltaSeconds(), verticalSmoothing.get()), -90, 90);
        mc.player.setYRot(nextYaw);
        mc.player.setXRot(nextPitch);
         
        mc.player.yRotO += nextYaw - oldYaw;
        mc.player.xRotO += nextPitch - oldPitch;
    }

    private boolean validTarget(LivingEntity candidate) {
        var mc = mc();
        return candidate != null && !candidate.isRemoved() && candidate.level() == mc.level
            && CombatTargets.valid(mc, candidate, playersOnly.get(), ignoreTeams.get(), visibleOnly.get())
            && mc.player.distanceToSqr(candidate) <= range.get() * range.get();
    }

    private Vec3 targetDelta(LivingEntity candidate, float partialTick) {
        var box = candidate.getBoundingBox().move(candidate.getPosition(partialTick).subtract(candidate.position()));
        Vec3 eye = mc().player.getEyePosition(partialTick);
        double targetY = switch (targetBone.get()) {
            case "Head" -> box.minY + candidate.getEyeHeight();
            case "Pelvis" -> box.minY + (box.maxY - box.minY) * 0.45;
            default -> box.minY + (box.maxY - box.minY) * 0.70;
        };
        return aimPoint(box, eye, targetY, targetBone.get().equals("Closest"),
            horizontalMidpoint.get(), verticalMidpoint.get(), mc().player.getYRot()).subtract(eye);
    }

    public static Vec3 aimPoint(AABB box, Vec3 eye, double targetY, boolean closest,
                               double horizontalOffset, double verticalOffset, float yaw) {
        double x = closest ? Math.clamp(eye.x, box.minX, box.maxX) : (box.minX + box.maxX) * 0.5;
        double y = closest ? Math.clamp(eye.y, box.minY, box.maxY) : targetY;
        double z = closest ? Math.clamp(eye.z, box.minZ, box.maxZ) : (box.minZ + box.maxZ) * 0.5;
        double radians = Math.toRadians(yaw);
         
        return new Vec3(
            Math.clamp(x - Math.cos(radians) * horizontalOffset / 100 * (box.maxX - box.minX) * 0.5, box.minX, box.maxX),
            Math.clamp(y + verticalOffset / 100 * (box.maxY - box.minY) * 0.5, box.minY, box.maxY),
            Math.clamp(z - Math.sin(radians) * horizontalOffset / 100 * (box.maxZ - box.minZ) * 0.5, box.minZ, box.maxZ));
    }

    private double angularDistance(LivingEntity candidate, float partialTick) {
        Vec3 delta = targetDelta(candidate, partialTick);
        if (delta.lengthSqr() < 1.0e-12) return 0;
        return Math.hypot(CombatMath.wrapDegrees(targetYaw(delta) - mc().player.getYRot()),
            targetPitch(delta) - mc().player.getXRot());
    }

    private static float targetYaw(Vec3 delta) {
        return (float) Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90;
    }

    private static float targetPitch(Vec3 delta) {
        return (float) -Math.toDegrees(Math.atan2(delta.y, Math.hypot(delta.x, delta.z)));
    }

    @Override
    protected void onDisable() {
        target = null;
    }
}

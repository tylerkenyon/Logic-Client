package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.combat.CombatMath;
import dev.technix.logic.client.combat.CombatTargets;
import dev.technix.logic.client.gui.GuiMotion;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import dev.technix.logic.event.impl.FrameUpdateEvent;
import dev.technix.logic.event.impl.Render2DEvent;
import imgui.ImColor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

public final class AimAssist extends Module {
    private final ModeSetting targetBone = registerSetting(new ModeSetting(
        "Target bone", "Closest follows your view ray; Optimal uses the nearest inset hitbox point",
        "Chest", "Head", "Chest", "Pelvis", "Closest", "Optimal"));
    private final NumberSetting horizontalMidpoint = registerSetting(new NumberSetting(
        "Horizontal midpoint", "Aim offset: -100 left, 0 unchanged, 100 right; stays inside the hitbox", 0, -100, 100, 1));
    private final NumberSetting verticalMidpoint = registerSetting(new NumberSetting(
        "Vertical midpoint", "Aim offset: -100 down, 0 unchanged, 100 up; stays inside the hitbox", 0, -100, 100, 1));
    private final NumberSetting range = registerSetting(new NumberSetting(
        "Range", "Distance to lock on", 4, 1, 6, 0.1));
    private final NumberSetting fov = registerSetting(new NumberSetting(
        "Field of view", "target FOV", 60, 5, 180, 1));
    private final BooleanSetting drawFov = registerSetting(new BooleanSetting(
        "Draw FOV", "Show the aim field of view around the first-person crosshair", false));
    private final BooleanSetting showOptimalPoint = registerSetting(new BooleanSetting(
        "Show optimal point", "Show the active Optimal aim point inside the target hitbox", false));
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
    private record AimMarker(LivingEntity target, Vec3 point, double radius) {}
    private AimMarker marker;
    private float markerScale;

    public AimAssist() {
        super("Aim Assist", "locks on to enemies.", Category.COMBAT);
        showOptimalPoint.visibleWhen(() -> targetBone.get().equals("Optimal"));
    }

    @Subscribe
    private void onTick(ClientTickEvent event) {
        var mc = mc();
        if (!CombatTargets.canAct(mc) || (holdAttack.get() && !mc.options.keyAttack.isDown())) {
            target = null;
            return;
        }

        boolean optimal = targetBone.get().equals("Optimal");
        boolean followView = targetBone.get().equals("Closest");
        if (!optimal && !followView && validTarget(target) && angularDistance(target, 1) <= fov.get() * 0.5 + 5) return;

        target = null;
        double bestScore = Double.POSITIVE_INFINITY;
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof LivingEntity candidate) || !validTarget(candidate)) continue;
            double angle = angularDistance(candidate, 1);
            if (angle > fov.get() * 0.5) continue;
            double score = optimal ? optimalYawDistance(candidate) : angle;
            if (score < bestScore) {
                bestScore = score;
                target = candidate;
            }
        }
    }

    @Subscribe
    private void onFrame(FrameUpdateEvent event) {
        if (LogicClient.anchorBusy() || LogicClient.keyPearlBusy()) return;
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
            && targetDistanceSqr(candidate) <= range.get() * range.get();
    }

    private double targetDistanceSqr(LivingEntity candidate) {
        if (!targetBone.get().equals("Optimal")) return mc().player.distanceToSqr(candidate);
        Vec3 eye = mc().player.getEyePosition(1);
        return eye.distanceToSqr(nearestPoint(targetBox(candidate, 1), eye));
    }

    private static AABB interpolatedBox(Entity entity, float partialTick) {
        return entity.getBoundingBox().move(entity.getPosition(partialTick).subtract(entity.position()));
    }

    private AABB targetBox(LivingEntity candidate, float partialTick) {
        AABB box = interpolatedBox(candidate, partialTick);
        if (targetBone.get().equals("Optimal") && candidate instanceof EnderDragon dragon) {
            Vec3 eye = mc().player.getEyePosition(partialTick);
            double closest = Double.POSITIVE_INFINITY;
            for (var part : dragon.getSubEntities()) {
                AABB partBox = interpolatedBox(part, partialTick);
                double distance = eye.distanceToSqr(nearestPoint(partBox, eye));
                if (distance < closest) {
                    box = partBox;
                    closest = distance;
                }
            }
        }
        return box;
    }

    private double optimalYawDistance(LivingEntity candidate) {
        Vec3 eye = mc().player.getEyePosition(1);
        Vec3 delta = nearestPoint(targetBox(candidate, 1), eye).subtract(eye);
        if (delta.horizontalDistanceSqr() < 1.0e-12) return 0;
        return Math.abs(CombatMath.wrapDegrees(targetYaw(delta) - mc().player.getYRot()));
    }

    private Vec3 targetDelta(LivingEntity candidate, float partialTick) {
        AABB box = targetBox(candidate, partialTick);
        Vec3 eye = mc().player.getEyePosition(partialTick);
        return targetPoint(candidate, box, eye).subtract(eye);
    }

    private Vec3 targetPoint(LivingEntity candidate, AABB box, Vec3 eye) {
        double targetY = switch (targetBone.get()) {
            case "Head" -> box.minY + candidate.getEyeHeight();
            case "Pelvis" -> box.minY + (box.maxY - box.minY) * 0.45;
            default -> box.minY + (box.maxY - box.minY) * 0.70;
        };
        Vec3 point = switch (targetBone.get()) {
            case "Closest" -> closestAimPoint(box, eye, mc().player.getViewVector(1));
            case "Optimal" -> optimalAimPoint(box, eye);
            default -> new Vec3((box.minX + box.maxX) * 0.5, targetY, (box.minZ + box.maxZ) * 0.5);
        };
        return offsetPoint(box, point, horizontalMidpoint.get(), verticalMidpoint.get(), mc().player.getYRot());
    }

    @Subscribe
    private void onRender(Render2DEvent event) {
        var mc = mc();
        var frame = WorldProjection.current();
        if (mc.player == null || mc.level == null || frame == null || frame.level() != mc.level
                || mc.getCameraEntity() != mc.player || mc.gui.screen() != null
                || LogicClient.MODULES.get(ClickGui.class).open()) {
            clearMarker();
            return;
        }

        var context = event.context();
        var draw = context.drawList();
        float focalLength = frame.focalLength(context.width(), context.height());
        boolean light = LogicClient.MODULES.get(InterfaceModule.class).light();
        if (drawFov.get() && mc.options.getCameraType().isFirstPerson()) {
            float x = context.width() * 0.5f, y = context.height() * 0.5f;
            double radius = focalLength * Math.tan(Math.toRadians(fov.get() * 0.5));
            if (radius >= 1 && radius <= Math.hypot(x, y) + 2) {
                draw.addCircle(x, y, (float) radius, ImColor.rgba(0, 0, 0, 105), 0, 2.5f);
                draw.addCircle(x, y, (float) radius, LogicClient.THEME.color(light, "accent", 0.65f), 0, 1f);
            }
        }

        if (!showOptimalPoint.get() || !targetBone.get().equals("Optimal")
                || (marker != null && marker.target().level() != frame.level())) {
            clearMarker();
            return;
        }
        boolean active = CombatTargets.canAct(mc) && !LogicClient.anchorBusy() && !LogicClient.keyPearlBusy()
            && (!holdAttack.get() || mc.options.keyAttack.isDown()) && validTarget(target);
        if (active) {
            AABB box = targetBox(target, frame.partialTick());
            Vec3 point = targetPoint(target, box, mc.player.getEyePosition(frame.partialTick()));
            double radius = Math.min(Math.min(point.x - box.minX, box.maxX - point.x),
                Math.min(Math.min(point.y - box.minY, box.maxY - point.y),
                    Math.min(point.z - box.minZ, box.maxZ - point.z)));
            if (marker == null || marker.target() != target) markerScale = 0;
            marker = new AimMarker(target, point, radius);
        }
        markerScale = GuiMotion.smooth(markerScale, active ? 1 : 0, 26, context.deltaTime());
        if (markerScale == 0 || marker == null) {
            clearMarker();
            return;
        }

        var clip = frame.clip(marker.point());
        if (clip.w <= 0.05f || !clip.isFinite() || Math.abs(clip.x) > clip.w || Math.abs(clip.y) > clip.w) return;
        float x = (1 + clip.x / clip.w) * context.width() * 0.5f;
        float y = (1 - clip.y / clip.w) * context.height() * 0.5f;
        float radius = (float) Math.min(12, marker.radius() * focalLength / clip.w) * markerScale;
        if (radius < 1) return;
        float stroke = Math.min(1.2f, radius * 0.35f);
        draw.addCircleFilled(x, y, radius, ImColor.rgba(0, 0, 0, Math.round(88 * markerScale)));
        draw.addCircle(x, y, radius - stroke, LogicClient.THEME.color(light, "accent", markerScale), 0, stroke);
    }

    private void clearMarker() {
        marker = null;
        markerScale = 0;
    }

    public static Vec3 optimalAimPoint(AABB box, Vec3 eye) {
        double insetX = Math.min(0.12, (box.maxX - box.minX) * 0.5);
        double insetY = Math.min(0.12, (box.maxY - box.minY) * 0.5);
        double insetZ = Math.min(0.12, (box.maxZ - box.minZ) * 0.5);
        return new Vec3(Math.clamp(eye.x, box.minX + insetX, box.maxX - insetX),
            Math.clamp(eye.y, box.minY + insetY, box.maxY - insetY),
            Math.clamp(eye.z, box.minZ + insetZ, box.maxZ - insetZ));
    }

    private static Vec3 nearestPoint(AABB box, Vec3 point) {
        return new Vec3(Math.clamp(point.x, box.minX, box.maxX),
            Math.clamp(point.y, box.minY, box.maxY), Math.clamp(point.z, box.minZ, box.maxZ));
    }

    public static Vec3 closestAimPoint(AABB box, Vec3 eye, Vec3 look) {
        if (box.contains(eye)) return eye;
        if (look.lengthSqr() < 1.0e-12) return nearestPoint(box, eye);
        Vec3 direction = look.normalize();
        double length = eye.distanceTo(box.getCenter())
            + Math.sqrt(Math.pow(box.maxX - box.minX, 2) + Math.pow(box.maxY - box.minY, 2)
                + Math.pow(box.maxZ - box.minZ, 2));
        var intersection = box.clip(eye, eye.add(direction.scale(length)));
        if (intersection.isPresent()) return intersection.get();

        double low = 0, high = length;
        for (int i = 0; i < 40; i++) {
            double first = low + (high - low) / 3;
            double second = high - (high - low) / 3;
            Vec3 a = eye.add(direction.scale(first));
            Vec3 b = eye.add(direction.scale(second));
            if (a.distanceToSqr(nearestPoint(box, a)) < b.distanceToSqr(nearestPoint(box, b))) high = second;
            else low = first;
        }
        return nearestPoint(box, eye.add(direction.scale((low + high) * 0.5)));
    }

    public static Vec3 aimPoint(AABB box, Vec3 eye, double targetY, boolean closest,
                               double horizontalOffset, double verticalOffset, float yaw) {
        double x = closest ? Math.clamp(eye.x, box.minX, box.maxX) : (box.minX + box.maxX) * 0.5;
        double y = closest ? Math.clamp(eye.y, box.minY, box.maxY) : targetY;
        double z = closest ? Math.clamp(eye.z, box.minZ, box.maxZ) : (box.minZ + box.maxZ) * 0.5;
        return offsetPoint(box, new Vec3(x, y, z), horizontalOffset, verticalOffset, yaw);
    }

    private static Vec3 offsetPoint(AABB box, Vec3 point, double horizontalOffset, double verticalOffset, float yaw) {
        double radians = Math.toRadians(yaw);
         
        return new Vec3(
            Math.clamp(point.x - Math.cos(radians) * horizontalOffset / 100 * (box.maxX - box.minX) * 0.5, box.minX, box.maxX),
            Math.clamp(point.y + verticalOffset / 100 * (box.maxY - box.minY) * 0.5, box.minY, box.maxY),
            Math.clamp(point.z - Math.sin(radians) * horizontalOffset / 100 * (box.maxZ - box.minZ) * 0.5, box.minZ, box.maxZ));
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
        clearMarker();
    }
}

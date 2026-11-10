package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.gui.GuiMotion;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.Render2DEvent;
import dev.technix.mica.api.FontFace;
import dev.technix.mica.api.RenderContext;
import imgui.ImGui;
import imgui.ImColor;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.projectile.ThrowableProjectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import org.joml.Vector4f;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import javax.imageio.ImageIO;

public final class Trajectories extends Module {
    private final BooleanSetting landingHud = registerSetting(new BooleanSetting("Landing HUD", "Show predicted impact coordinates and flight time.", true));
    private record Prediction(Vec3 impact, double ticks) {}
    private static final BufferedImage PEARL_ICON = loadPearlIcon();
    private Prediction pearlLanding;
    private UUID pearlId;
    private float hudAnimation;
    private double remaining;

    public Trajectories() { super("Trajectories", "Predict projectile paths and impacts.", Category.RENDER); }
    @Override protected void onDisable() { pearlLanding = null; pearlId = null; hudAnimation = 0; remaining = 0; }

    private static BufferedImage loadPearlIcon() {
        try (var input = Minecraft.class.getResourceAsStream("/assets/minecraft/textures/item/ender_pearl.png")) {
            return input == null ? null : ImageIO.read(input);
        } catch (IOException ignored) { return null; }
    }

    @Subscribe private void render(Render2DEvent event) {
        var mc = mc();
        var frame = WorldProjection.current();
        if (mc.player == null || mc.level == null || frame == null || frame.level() != mc.level) {
            onDisable();
            return;
        }
        if (mc.gui.screen() != null || LogicClient.MODULES.get(ClickGui.class).open()) return;
        var ctx = event.context();
        int color = LogicClient.THEME.color(LogicClient.MODULES.get(InterfaceModule.class).light(), "accent", 1);
        Prediction selected = null;
        UUID selectedId = null;
        int selectedAge = Integer.MAX_VALUE;
        var stack = mc.player.isUsingItem() ? mc.player.getUseItem() : mc.player.getMainHandItem();
        var item = stack.getItem();
        double speed = 0, gravity = 0.03, pitchOffset = 0;
        if (item == Items.ENDER_PEARL || item == Items.SNOWBALL || item == Items.EGG
                || item == Items.BLUE_EGG || item == Items.BROWN_EGG) speed = 1.5;
        else if (item == Items.SPLASH_POTION || item == Items.LINGERING_POTION) { speed = 0.5; gravity = 0.05; pitchOffset = -20; }
        else if (item == Items.EXPERIENCE_BOTTLE) { speed = 0.7; gravity = 0.07; pitchOffset = -20; }
        else if (item == Items.BOW && mc.player.isUsingItem()) { speed = BowItem.getPowerForTime(mc.player.getTicksUsingItem()) * 3; gravity = 0.05; }
        else if (item == Items.TRIDENT && mc.player.isUsingItem() && mc.player.getTicksUsingItem() >= 10) { speed = 2.5; gravity = 0.05; }
        if (speed > 0.1) {
            double yaw = Math.toRadians(mc.player.getYRot(frame.partialTick())), pitch = Math.toRadians(mc.player.getXRot(frame.partialTick()) + pitchOffset);
            Vec3 direction = new Vec3(-Math.sin(yaw) * Math.cos(pitch), -Math.sin(pitch), Math.cos(yaw) * Math.cos(pitch));
            Vec3 motion = direction.scale(speed).add(mc.player.getDeltaMovement().x, mc.player.onGround() ? 0 : mc.player.getDeltaMovement().y, mc.player.getDeltaMovement().z);
            predict(ctx, frame, mc.player, mc.player.getEyePosition(frame.partialTick()).add(0, -0.1, 0), motion, gravity, item != Items.BOW && item != Items.TRIDENT, color);
        }
        for (Entity entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile projectile) || !(projectile instanceof ThrowableProjectile || projectile instanceof AbstractArrow)
                    || projectile.isRemoved() || projectile.onGround() || projectile.getDeltaMovement().lengthSqr() < 0.0001
                    || mc.player.distanceToSqr(projectile) > 128 * 128) continue;
            segment(ctx, frame, WorldProjection.entityPosition(projectile, frame.partialTick()), projectile.position(), color);
            Prediction result = predict(ctx, frame, projectile, projectile.position(), projectile.getDeltaMovement(), projectile.isNoGravity() ? 0 : projectile.getGravity(), projectile instanceof ThrowableProjectile, color);
            if (projectile instanceof ThrownEnderpearl && projectile.getOwner() == mc.player && result != null && projectile.tickCount < selectedAge) {
                selected = result; selectedId = projectile.getUUID(); selectedAge = projectile.tickCount;
            }
        }
        float dt = ctx.deltaTime();
        if (selected != null && landingHud.get()) {
            double estimate = (selected.ticks() + 1 - frame.partialTick()) / 20;
            remaining = selectedId.equals(pearlId) ? Math.min(Math.max(0, remaining - dt), estimate) : estimate;
            pearlId = selectedId;
            pearlLanding = selected;
        } else {
            pearlId = null;
            remaining = Math.max(0, remaining - dt);
        }
        hudAnimation = GuiMotion.smooth(hudAnimation, selected != null && landingHud.get() ? 1 : 0, 14, dt);
        if (hudAnimation > 0 && pearlLanding != null) hud(ctx, pearlLanding, remaining, hudAnimation);
        else if (hudAnimation == 0) pearlLanding = null;
    }

    private Prediction predict(RenderContext ctx, WorldProjection.Frame frame, Entity source, Vec3 start, Vec3 velocity, double gravity, boolean throwable, int color) {
        var level = mc().level;
        Vec3 position = start;
        for (int tick = 0; tick < 200; tick++) {
            if (throwable) velocity = velocity.add(0, -gravity, 0).scale(0.99);
            Vec3 next = position.add(velocity);
            if (!level.hasChunkAt(BlockPos.containing(next))) return null;
            var block = level.clip(new ClipContext(position, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, source));
            Vec3 end = block.getType() == HitResult.Type.MISS ? next : block.getLocation();
            var hit = ProjectileUtil.getEntityHitResult(level, source, position, end, new AABB(position, end).inflate(1),
                entity -> entity.isAlive() && entity.isPickable() && entity != source && (!(source instanceof Projectile p) || entity != p.getOwner()), 0.3f);
            if (hit != null) end = hit.getLocation();
            segment(ctx, frame, position, end, color);
            if (hit != null || block.getType() != HitResult.Type.MISS) {
                double fraction = velocity.length() > 0 ? position.distanceTo(end) / velocity.length() : 0;
                return new Prediction(end, tick + fraction);
            }
            position = next;
            if (!throwable) velocity = velocity.scale(0.99).add(0, -gravity, 0);
            if (position.y < level.getMinY() - 4) break;
        }
        return null;
    }

    private static void segment(RenderContext ctx, WorldProjection.Frame frame, Vec3 from, Vec3 to, int color) {
        Vec3 a0 = from.subtract(frame.camera()), b0 = to.subtract(frame.camera());
        Vector4f a = frame.matrix().transform(new Vector4f((float)a0.x, (float)a0.y, (float)a0.z, 1));
        Vector4f b = frame.matrix().transform(new Vector4f((float)b0.x, (float)b0.y, (float)b0.z, 1));
        float near = 0.05f;
        if (a.w < near && b.w < near) return;
        if (a.w < near) a.lerp(b, (near - a.w) / (b.w - a.w));
        else if (b.w < near) b.lerp(a, (near - b.w) / (a.w - b.w));
        float x1 = (a.x / a.w + 1) * ctx.width() / 2, y1 = (1 - a.y / a.w) * ctx.height() / 2;
        float x2 = (b.x / b.w + 1) * ctx.width() / 2, y2 = (1 - b.y / b.w) * ctx.height() / 2;
        if (Float.isFinite(x1 + y1 + x2 + y2)) ctx.drawList().addLine(x1, y1, x2, y2, color, 2);
    }

    private static void hud(RenderContext ctx, Prediction prediction, double remaining, float animation) {
        var ui = LogicClient.MODULES.get(InterfaceModule.class);
        boolean light = ui.light();
        float scale = ui.scale.get().floatValue(), padding = Math.round(ui.padding.get().floatValue() * scale);
        int size = Math.max(12, Math.round(14 * scale));
        var face = ctx.renderer().font(FontFace.MEDIUM);
        var font = face != null && face.imFont() != null ? face.imFont() : ImGui.getFont();
        ImGui.pushFont(font, size);
        try {
            Vec3 p = prediction.impact();
            String title = String.format(Locale.ROOT, "Ender Pearl  %.1fs", remaining);
            String coordinates = String.format(Locale.ROOT, "%.1f, %.1f, %.1f", p.x, p.y, p.z);
            float iconSize = Math.round(20 * scale), gap = Math.round(8 * scale), lineGap = Math.round(3 * scale);
            float width = padding * 2 + iconSize + gap + Math.max(ImGui.calcTextSizeX(title), ImGui.calcTextSizeX(coordinates));
            float height = padding * 2 + size * 2 + lineGap;
            float x = Math.round((ctx.width() - width) / 2), y = Math.round(ctx.height() / 2f + 38 * scale + (1 - animation) * 12 * scale);
            var draw = ctx.drawList();
            draw.addRectFilled(x, y, x + width, y + height,
                LogicClient.THEME.color(light, "bg-primary", ui.opacity.get().floatValue() / 100 * animation), Math.min(LogicClient.THEME.radiusMd * scale, height / 2));
            pearlIcon(ctx, x + padding, y + (height - iconSize) / 2, iconSize, animation);
            float textX = Math.round(x + padding + iconSize + gap), textY = Math.round(y + padding - size * 0.1f);
            draw.addText(font, size, textX, textY, LogicClient.THEME.color(light, "text-primary", animation), title);
            draw.addText(font, size, textX, textY + size + lineGap, LogicClient.THEME.color(light, "text-secondary", animation), coordinates);
        } finally { ImGui.popFont(); }
    }

    private static void pearlIcon(RenderContext ctx, float x, float y, float size, float alpha) {
        if (PEARL_ICON == null) {
            ctx.drawList().addCircleFilled(x + size / 2, y + size / 2, size * 0.4f, ImColor.rgba(46, 154, 132, Math.round(255 * alpha)));
            return;
        }
        int width = PEARL_ICON.getWidth(), height = PEARL_ICON.getHeight();
        for (int py = 0; py < height; py++) for (int px = 0; px < width; px++) {
            int argb = PEARL_ICON.getRGB(px, py), a = Math.round((argb >>> 24) * alpha);
            if (a == 0) continue;
            int color = ImColor.rgba((argb >>> 16) & 255, (argb >>> 8) & 255, argb & 255, a);
            ctx.drawList().addRectFilled(x + px * size / width, y + py * size / height,
                x + (px + 1) * size / width, y + (py + 1) * size / height, color);
        }
    }
}


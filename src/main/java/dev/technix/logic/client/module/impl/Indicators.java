package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.MultiSelectSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.Render2DEvent;
import dev.technix.mica.api.FontFace;
import dev.technix.mica.api.OverlayRenderer;
import imgui.ImGui;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.entity.projectile.arrow.AbstractArrow;
import net.minecraft.world.entity.projectile.hurtingprojectile.AbstractHurtingProjectile;
import net.minecraft.world.entity.projectile.throwableitemprojectile.ThrownEnderpearl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class Indicators extends Module {
    private final MultiSelectSetting projectiles = registerSetting(new MultiSelectSetting("Projectiles", "",
        List.of("Arrows", "Fireballs", "Ender pearls"), "Arrows", "Fireballs", "Ender pearls"));
    private final BooleanSetting approaching = registerSetting(new BooleanSetting("Only approaching", "", true));
    private final BooleanSetting labels = registerSetting(new BooleanSetting("Distance labels", "", true));
    private final NumberSetting range = registerSetting(new NumberSetting("Range", "Blocks", 48, 8, 96, 1));
    private final NumberSetting radius = registerSetting(new NumberSetting("Radius", "Distance from the crosshair", 72, 32, 160, 1));
    private record Marker(double angle, double distance, String accent) {}

    public Indicators() { super("Indicators", "Directional cues for nearby projectiles.", Category.RENDER); }

    @Subscribe
    private void render(Render2DEvent event) {
        var mc = mc();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null
                || LogicClient.MODULES.get(ClickGui.class).open()) return;
        var ctx = event.context();
        var eye = mc.player.getEyePosition();
        float partial = mc.getDeltaTracker().getGameTimeDeltaPartialTick(false);
        double yaw = Math.toRadians(mc.gameRenderer.mainCamera().yRot());
        double sin = Math.sin(yaw), cos = Math.cos(yaw);
        var markers = new ArrayList<Marker>();
        for (var entity : mc.level.entitiesForRendering()) {
            if (!(entity instanceof Projectile projectile) || projectile.isRemoved() || projectile.getOwner() == mc.player) continue;
            String kind;
            String accent;
            if (projectile instanceof ThrownEnderpearl) { kind = "Ender pearls"; accent = "success"; }
            else if (projectile instanceof AbstractHurtingProjectile) { kind = "Fireballs"; accent = "warning"; }
            else if (projectile instanceof AbstractArrow) { kind = "Arrows"; accent = "accent-hover"; }
            else continue;
            if (!projectiles.selected(kind)) continue;
            var motion = projectile.getDeltaMovement();
            var offset = projectile.getPosition(partial).subtract(eye);
            if (motion.lengthSqr() < 1e-5 || offset.lengthSqr() > range.get() * range.get()
                    || approaching.get() && offset.dot(motion) >= 0) continue;
            double right = -offset.x * cos - offset.z * sin;
            double forward = -offset.x * sin + offset.z * cos;
            double distance = offset.length();
            markers.add(new Marker(Math.atan2(right, forward), distance, distance < 10 ? "danger" : accent));
        }
        if (markers.isEmpty()) return;
        markers.sort(Comparator.comparingDouble(Marker::distance));
        boolean light = LogicClient.MODULES.get(InterfaceModule.class).light();
        float ring = Math.min(radius.get().floatValue(), Math.min(ctx.width(), ctx.height()) * .35f);
        var placed = new ArrayList<Marker>();
        boolean pushed = OverlayRenderer.pushFont(ctx.renderer().font(FontFace.MEDIUM));
        try {
            for (var marker : markers) {
                if (placed.stream().anyMatch(other -> Math.cos(marker.angle - other.angle) > .94)) continue;
                float dx = (float)Math.sin(marker.angle), dy = -(float)Math.cos(marker.angle);
                float x = ctx.width() / 2 + dx * ring, y = ctx.height() / 2 + dy * ring;
                int color = LogicClient.THEME.color(light, marker.accent, 1);
                ctx.drawList().addLine(x - dy * 5 - dx * 4, y + dx * 5 - dy * 4, x + dx * 4, y + dy * 4, color, 2);
                ctx.drawList().addLine(x + dx * 4, y + dy * 4, x + dy * 5 - dx * 4, y - dx * 5 - dy * 4, color, 2);
                if (labels.get()) {
                    String label = Math.round(marker.distance) + "m";
                    ctx.drawList().addText(Math.round(x + dx * 19 - ImGui.calcTextSizeX(label) / 2),
                        Math.round(y + dy * 19 - ImGui.getFontSize() / 2), color, label);
                }
                placed.add(marker);
                if (placed.size() == 8) break;
            }
        } finally { OverlayRenderer.popFont(pushed); }
    }
}

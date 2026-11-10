package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.ScreenProjection;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.render.SkeletonRenderer;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.Render2DEvent;
import dev.technix.mica.api.FontFace;
import dev.technix.mica.api.OverlayRenderer;
import dev.technix.mica.api.RenderContext;
import imgui.ImColor;
import imgui.ImGui;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import org.lwjgl.glfw.GLFW;

import java.util.Locale;

public final class ESP extends Module {
    private final ModeSetting mode = registerSetting(new ModeSetting(
        "Display mode", "Choose the overlay geometry", "Boxes", "Boxes", "Skeleton", "Both"));
    private final BooleanSetting boxes = registerSetting(new BooleanSetting(
        "Player boxes", "Draw a screen-space bounding rectangle", true));
    private final BooleanSetting names = registerSetting(new BooleanSetting(
        "Names", "Display the entity name above the box", true));
    private final BooleanSetting healthBars = registerSetting(new BooleanSetting(
        "Health bars", "Display remaining health beside the box", true));
    private final BooleanSetting distanceLabels = registerSetting(new BooleanSetting(
        "Distance labels", "Display distance below the box", true));
    private final BooleanSetting playersOnly = registerSetting(new BooleanSetting(
        "Players only", "Disable to include living mobs", true));
    private final BooleanSetting invisible = registerSetting(new BooleanSetting(
        "Invisible entities", "Include entities invisible to you", false));
    private final NumberSetting range = registerSetting(new NumberSetting(
        "Render distance", "Maximum distance in blocks", 128, 8, 256, 8));

    public ESP() {
        super("2D ESP", "ImGui boxes, names, health and distance overlays.", Category.RENDER,
            GLFW.GLFW_KEY_UNKNOWN, false);
    }

    @Subscribe
    private void onRender(Render2DEvent event) {
        var mc = mc();
        var frame = WorldProjection.current();
        if (mc.player == null || mc.level == null || mc.gui.screen() != null || frame == null
                || frame.level() != mc.level) return;
        RenderContext context = event.context();
        var draw = context.drawList();
        draw.pushClipRect(0, 0, context.width(), context.height(), true);
        try {
            for (var entity : mc.level.entitiesForRendering()) {
                if (!(entity instanceof LivingEntity target) || entity == mc.player || !entity.isAlive()
                        || entity.isSpectator() || (playersOnly.get() && !(entity instanceof Player))
                        || (!invisible.get() && entity.isInvisibleTo(mc.player))
                        || mc.player.distanceToSqr(entity) > range.get() * range.get()) continue;

                var box = entity.getBoundingBox().move(WorldProjection.entityPosition(entity, frame.partialTick())
                    .subtract(entity.position())).inflate(0.03).move(frame.camera().scale(-1));
                var screen = ScreenProjection.bounds(frame.matrix(), box.minX, box.minY, box.minZ,
                    box.maxX, box.maxY, box.maxZ, Camera.PROJECTION_Z_NEAR, context.width(), context.height());
                if (screen == null) continue;
                float x = screen.left(), y = screen.top(), right = screen.right(), bottom = screen.bottom();
                if (right - x < 1 || bottom - y < 1) continue;

                if (!mode.get().equals("Boxes")) SkeletonRenderer.draw(context, frame, target);
                if (boxes.get() && !mode.get().equals("Skeleton")) {
                    draw.addRect(x, y, right, bottom, ImColor.rgba(0, 0, 0, 230), 0, 0, 3);
                    draw.addRect(x, y, right, bottom, ImColor.rgba(240, 240, 240, 255), 0, 0, 1);
                }
                if (names.get() && (!(entity instanceof Player player) || !LogicClient.MODULES.get(Nametags.class).shows(player))) {
                    label(context, LogicClient.displayName(entity.getName().getString()), (x + right) * 0.5f, y - 17);
                }
                if (distanceLabels.get()) label(context,
                    String.format(Locale.ROOT, "%.0fm", mc.player.distanceTo(entity)), (x + right) * 0.5f, bottom + 3);
                if (healthBars.get()) {
                    float health = Math.clamp(target.getHealth() / Math.max(1, target.getMaxHealth()), 0, 1);
                    draw.addRectFilled(x - 7, y - 1, x - 3, bottom + 1, ImColor.rgba(0, 0, 0, 220));
                    draw.addRectFilled(x - 6, bottom - (bottom - y) * health, x - 4, bottom,
                        ImColor.rgba((int) (230 * (1 - health)), (int) (220 * health), 65, 255));
                }
            }
        } finally {
            draw.popClipRect();
        }
    }

    private static void label(RenderContext context, String text, float centerX, float y) {
        boolean pushed = OverlayRenderer.pushFont(context.renderer().font(FontFace.REGULAR));
        try {
            float x = centerX - ImGui.calcTextSizeX(text) * 0.5f;
            context.drawList().addText(x + 1, y + 1, ImColor.rgba(0, 0, 0, 240), text);
            context.drawList().addText(x, y, ImColor.rgba(245, 245, 245, 255), text);
        } finally {
            OverlayRenderer.popFont(pushed);
        }
    }
}

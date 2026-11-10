package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.Render2DEvent;
import dev.technix.mica.api.FontFace;
import dev.technix.mica.api.OverlayRenderer;
import imgui.ImGui;
import net.minecraft.client.Camera;
import net.minecraft.world.entity.player.Player;
import org.joml.Vector4f;

import java.util.Comparator;

public final class Nametags extends Module {
    private final BooleanSetting hideVanilla = registerSetting(new BooleanSetting("Hide vanilla nametags", "", true));
    private final BooleanSetting health = registerSetting(new BooleanSetting("Health", "", false));
    private final BooleanSetting distance = registerSetting(new BooleanSetting("Distance", "", false));
    private final NumberSetting range = registerSetting(new NumberSetting("Range", "Blocks", 64, 8, 128, 1));

    public Nametags() { super("Nametags", "Minimal labels above other players.", Category.RENDER); }

    public boolean shows(Player player) {
        var mc = mc();
        return enabled() && mc.player != null && mc.gui.screen() == null && player != mc.player
            && player != mc.getCameraEntity() && player.isAlive() && !player.isSpectator() && !player.isInvisibleTo(mc.player)
            && player.distanceToSqr(mc.player) <= range.get() * range.get();
    }

    public boolean hidesVanilla(Object entity) {
        return hideVanilla.get() && entity instanceof Player player && shows(player);
    }

    @Subscribe(priority = -5)
    private void render(Render2DEvent event) {
        var mc = mc();
        var frame = WorldProjection.current();
        if (mc.level == null || mc.player == null || frame == null || frame.level() != mc.level) return;
        var ctx = event.context();
        var theme = LogicClient.THEME;
        boolean light = LogicClient.MODULES.get(InterfaceModule.class).light();
        var players = mc.level.players().stream().filter(this::shows)
            .sorted(Comparator.<Player>comparingDouble(mc.player::distanceToSqr).reversed()).toList();
        boolean pushed = OverlayRenderer.pushFont(ctx.renderer().font(FontFace.MEDIUM));
        try {
            for (var player : players) {
                var head = WorldProjection.entityPosition(player, frame.partialTick()).add(0, player.getBbHeight() + .28, 0).subtract(frame.camera());
                var clip = frame.matrix().transform(new Vector4f((float)head.x, (float)head.y, (float)head.z, 1));
                if (clip.w < Camera.PROJECTION_Z_NEAR || Math.abs(clip.x) > clip.w || Math.abs(clip.y) > clip.w) continue;
                String name = LogicClient.displayName(player.getName().getString());
                String details = health.get() ? Math.round(player.getHealth() + player.getAbsorptionAmount()) + " HP" : "";
                if (distance.get()) details += (details.isEmpty() ? "" : "  ") + Math.round(mc.player.distanceTo(player)) + "m";
                float nameWidth = ImGui.calcTextSizeX(name), fontHeight = ImGui.getFontSize();
                float gap = details.isEmpty() ? 0 : 8;
                float width = nameWidth + gap + (details.isEmpty() ? 0 : ImGui.calcTextSizeX(details)) + 14;
                float x = Math.round((clip.x / clip.w + 1) * ctx.width() / 2 - width / 2);
                float y = Math.round((1 - clip.y / clip.w) * ctx.height() / 2 - fontHeight - 6);
                var draw = ctx.drawList();
                draw.addRectFilled(x, y, x + width, y + fontHeight + 6, theme.color(light, "bg-primary", .93f), theme.radiusSm);
                draw.addText(x + 7, y + 3, theme.color(light, "text-primary", 1), name);
                if (!details.isEmpty()) draw.addText(x + 7 + nameWidth + gap, y + 3, theme.color(light, "text-secondary", 1), details);
            }
        } finally { OverlayRenderer.popFont(pushed); }
    }
}

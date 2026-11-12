package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.input.EdgePrediction;
import dev.technix.logic.client.input.EagleAlignment;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.*;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.entity.player.Input;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.phys.AABB;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

public final class Eagle extends Module {
    public final ModeSetting mode = registerSetting(new ModeSetting("Mode", "Autoalign stabilizes horizontal edge detection and sneak release.", "Autoalign", "Standard", "Autoalign"));
    public final BooleanSetting shiftInAir = registerSetting(new BooleanSetting("Shift in air", "Also sneak while airborne when pitch, backward-key and block checks pass.", false));
    public final MultiSelectSetting checks = registerSetting(new MultiSelectSetting("Checks", "Selected checks must all pass.",
        List.of("Pitch", "Only holding S", "Only blocks", "On ground", "No jumping"),
        "Pitch", "Only holding S", "Only blocks", "On ground", "No jumping"));
    public final RangeSetting pitch = registerSetting(new RangeSetting("Pitch range", "Downward view angle in degrees.", 60, 90, 0, 90, 1));
    public final MultiSelectSetting hands = registerSetting(new MultiSelectSetting("Block hands", "Hands checked by Only blocks.",
        List.of("Main hand"), "Main hand", "Off hand"));
    public final RangeSetting advance = registerSetting(new RangeSetting("Lead time (ms)", "Sneak this long before predicted support loss. Equal endpoints use a fixed time.", 100, 100, 0, 500, 10));
    public final NumberSetting margin = registerSetting(new NumberSetting("Edge margin", "Inset from the player's support footprint, in blocks.", 0.05, 0, 0.2, 0.01));
    private boolean controlling;
    private double selectedLead = Double.NaN;
    private LocalPlayer owner;
    private final EagleAlignment alignment = new EagleAlignment();

    public Eagle() { super("Eagle", "Sneak before reaching an unsupported block edge.", Category.MOVEMENT); }

    private boolean eligible(LocalPlayer player, Input input) {
        var mc = mc();
        if (!mc.isWindowActive() || mc.isPaused() || mc.gui.screen() != null || mc.gui.overlay() != null
                || LogicClient.MODULES.get(ClickGui.class).open()
                || player.isSpectator() || player.isPassenger() || player.getAbilities().flying
                || player.isFallFlying() || player.isInWater() || player.isInLava() || player.onClimbable()) return false;
        boolean airShift = shiftInAir.get() && !player.onGround();
        if (!airShift && checks.selected("On ground") && !player.onGround()) return false;
        if (!airShift && checks.selected("No jumping") && (input.jump() || player.getDeltaMovement().y > 0.05)) return false;
        if (checks.selected("Only holding S") && !input.backward()) return false;
        if (checks.selected("Pitch") && (player.getXRot() < pitch.low() || player.getXRot() > pitch.high())) return false;
        return !checks.selected("Only blocks")
            || hands.selected("Main hand") && player.getMainHandItem().getItem() instanceof BlockItem
            || hands.selected("Off hand") && player.getOffhandItem().getItem() instanceof BlockItem;
    }


    public void applyInput(LocalPlayer player) {
        if (!enabled()) return;
        if (owner != player) {
            resetPrediction();
            owner = player;
        }
        Input input = player.input.keyPresses;
        if (!eligible(player, input)) {
            resetPrediction();
            return;
        }
        if (shouldSneak(player, input) && !input.shift()) {
            player.input.keyPresses = new Input(input.forward(), input.backward(), input.left(), input.right(),
                input.jump(), true, input.sprint());
        }
    }

    private boolean shouldSneak(LocalPlayer player, Input input) {
        if (shiftInAir.get() && !player.onGround()) return true;
        if (!Double.isFinite(selectedLead)) selectedLead = advance.low() == advance.high() ? advance.low()
            : ThreadLocalRandom.current().nextDouble(advance.low(), advance.high());
        double forward = (input.forward() ? 1 : 0) - (input.backward() ? 1 : 0);
        double strafe = (input.left() ? 1 : 0) - (input.right() ? 1 : 0);
        double magnitude = Math.hypot(forward, strafe);
        AABB box = player.getBoundingBox();
        double inset = Math.min(margin.get(), Math.min(box.maxX - box.minX, box.maxZ - box.minZ) * 0.45);
        AABB feet = new AABB(box.minX + inset, box.minY - 0.08, box.minZ + inset,
            box.maxX - inset, box.minY - 0.001, box.maxZ - inset);

        AABB fullFeet = new AABB(box.minX, box.minY - 0.08, box.minZ, box.maxX, box.minY - 0.001, box.maxZ);
        if (!supported(fullFeet)) {
            resetPrediction();
            return false;
        }
        double clearance = Math.min(0.03, Math.min(feet.maxX - feet.minX, feet.maxZ - feet.minZ) * 0.25);
        AABB releaseFeet = feet.deflate(clearance, 0, clearance);
        boolean edge, safelyClear;
        boolean align = mode.get().equals("Autoalign");
        var velocity = player.getDeltaMovement();
        double horizontalSpeed = Math.hypot(velocity.x, velocity.z);
        if (magnitude < 0.001) {
            edge = controlling && !supported(feet);
            safelyClear = supported(releaseFeet);
        }
        else {
            double yaw = Math.toRadians(player.getYRot());
            double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) / magnitude;
            double dz = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) / magnitude;
            double speed = Math.clamp(Math.max(player.getSpeed(), horizontalSpeed), 0.01, 2);
            edge = EdgePrediction.reachesEdge(speed, selectedLead, d -> supported(feet.move(dx * d, 0, dz * d)));
            double[] aligned = EagleAlignment.alignedHeading(dx, dz);
            if (align) edge |= EdgePrediction.reachesEdge(speed, selectedLead,
                d -> supported(feet.move(aligned[0] * d, 0, aligned[1] * d)));
            safelyClear = !EdgePrediction.reachesEdge(speed, selectedLead + 50,
                d -> supported(releaseFeet.move(dx * d, 0, dz * d)));
            if (align) safelyClear &= !EdgePrediction.reachesEdge(speed, selectedLead + 50,
                d -> supported(releaseFeet.move(aligned[0] * d, 0, aligned[1] * d)));
        }
        // Existing momentum can carry the player over an edge while turning or releasing movement.
        if (horizontalSpeed >= 0.001) {
            edge |= EdgePrediction.reachesEdge(feet, velocity.x, velocity.z, selectedLead, this::supported);
            safelyClear &= !EdgePrediction.reachesEdge(releaseFeet, velocity.x, velocity.z, selectedLead + 50, this::supported);
        }
        boolean sneak = alignment.update(edge, safelyClear, player.tickCount, align);
        if (controlling && !sneak) selectedLead = Double.NaN;
        controlling = sneak;
        return sneak;
    }

    private boolean supported(AABB box) { return mc().level.getBlockCollisions(mc().player, box).iterator().hasNext(); }
    private void resetPrediction() {
        controlling = false;
        selectedLead = Double.NaN;
        alignment.reset();
    }
    @Override protected void onDisable() { resetPrediction(); owner = null; }
}

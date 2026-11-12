package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.LogicClient;
import dev.technix.logic.client.input.EdgePrediction;
import dev.technix.logic.client.input.EagleAlignment;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.*;
import net.minecraft.client.player.LocalPlayer;
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
    private boolean manualToggle;
    private double selectedLead = Double.NaN;
    private LocalPlayer owner;
    private final EagleAlignment alignment = new EagleAlignment();
    private long clientTick;

    public Eagle() { super("Eagle", "Sneak before reaching an unsupported block edge.", Category.MOVEMENT); }

    private boolean eligible() {
        var mc = mc(); var player = mc.player;
        if (!enabled() || player == null || mc.level == null || mc.options == null || !mc.isWindowActive()
                || mc.gui.screen() != null || LogicClient.MODULES.get(ClickGui.class).open()
                || player.isSpectator() || player.isPassenger() || player.getAbilities().flying
                || player.isFallFlying() || player.isInWater() || player.isInLava() || player.onClimbable()) return false;
        boolean airShift = shiftInAir.get() && !player.onGround();
        if (!airShift && checks.selected("On ground") && !player.onGround()) return false;
        if (!airShift && checks.selected("No jumping") && (mc.options.keyJump.isDown() || player.getDeltaMovement().y > 0.05)) return false;
        if (checks.selected("Only holding S") && !MovementKeys.physicalDown(mc.options.keyDown)) return false;
        if (checks.selected("Pitch") && (player.getXRot() < pitch.low() || player.getXRot() > pitch.high())) return false;
        return !checks.selected("Only blocks")
            || hands.selected("Main hand") && player.getMainHandItem().getItem() instanceof BlockItem
            || hands.selected("Off hand") && player.getOffhandItem().getItem() instanceof BlockItem;
    }


    public void startTick() { clientTick++; update(); }

    public void update() {
        var mc = mc();
        if (owner != mc.player) { release(manualAllowed()); owner = mc.player; selectedLead = Double.NaN; }
        if (!eligible()) { release(manualAllowed()); selectedLead = Double.NaN; return; }
        if (controlling && mc.options.toggleCrouch().get() && !mc.options.keyShift.isDown()) manualToggle = !manualToggle;
        if (shiftInAir.get() && !mc.player.onGround()) { pressSneak(); return; }
        if (!Double.isFinite(selectedLead)) selectedLead = advance.low() == advance.high() ? advance.low()
            : ThreadLocalRandom.current().nextDouble(advance.low(), advance.high());
        double forward = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
        double strafe = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
        double magnitude = Math.hypot(forward, strafe);
        AABB box = mc.player.getBoundingBox();
        double inset = Math.min(margin.get(), Math.min(box.maxX - box.minX, box.maxZ - box.minZ) * 0.45);
        AABB feet = new AABB(box.minX + inset, box.minY - 0.08, box.minZ + inset,
            box.maxX - inset, box.minY - 0.001, box.maxZ - inset);

        AABB fullFeet = new AABB(box.minX, box.minY - 0.08, box.minZ, box.maxX, box.minY - 0.001, box.maxZ);
        if (!supported(fullFeet)) { release(manualAllowed()); return; }
        double clearance = Math.min(0.03, Math.min(feet.maxX - feet.minX, feet.maxZ - feet.minZ) * 0.25);
        AABB releaseFeet = feet.deflate(clearance, 0, clearance);
        boolean edge, safelyClear;
        boolean align = mode.get().equals("Autoalign");
        var velocity = mc.player.getDeltaMovement();
        double horizontalSpeed = Math.hypot(velocity.x, velocity.z);
        if (magnitude < 0.001) {
            edge = controlling && !supported(feet);
            safelyClear = supported(releaseFeet);
        }
        else {
            double yaw = Math.toRadians(mc.player.getYRot());
            double dx = (strafe * Math.cos(yaw) - forward * Math.sin(yaw)) / magnitude;
            double dz = (forward * Math.cos(yaw) + strafe * Math.sin(yaw)) / magnitude;
            double speed = Math.clamp(Math.max(mc.player.getSpeed(), horizontalSpeed), 0.01, 2);
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
        if (alignment.update(edge, safelyClear, clientTick, align)) pressSneak();
        else {
            boolean wasControlling = controlling;
            release(manualAllowed());
            if (wasControlling) selectedLead = Double.NaN;
        }
    }

    private void pressSneak() {
        var options = mc().options;
        if (!controlling) manualToggle = options.toggleCrouch().get() && options.keyShift.isDown();
        MovementKeys.setState(options.keyShift, true, options.toggleCrouch().get());
        controlling = true;
    }

    private boolean supported(AABB box) { return mc().level.getBlockCollisions(mc().player, box).iterator().hasNext(); }
    private boolean manualAllowed() {
        var mc = mc();
        return mc.player != null && mc.options != null && mc.isWindowActive() && mc.gui.screen() == null
            && (!LogicClient.MODULES.get(ClickGui.class).open() || LogicClient.MODULES.get(ClickGuiConfig.class).moveInGui.get());
    }
    private void release(boolean physical) {
        if (controlling && mc().options != null) {
            boolean toggle = mc().options.toggleCrouch().get();
            MovementKeys.setState(mc().options.keyShift, physical && (toggle ? manualToggle : MovementKeys.physicalDown(mc().options.keyShift)), toggle);
        }
        controlling = false;
        manualToggle = false;
        alignment.reset();
    }
    @Override protected void onDisable() { release(manualAllowed()); selectedLead = Double.NaN; owner = null; }
}

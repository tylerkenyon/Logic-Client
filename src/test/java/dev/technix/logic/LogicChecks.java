package dev.technix.logic;

import dev.technix.logic.client.combat.CombatMath;
import dev.technix.logic.client.combat.PacketDelayQueue;
import dev.technix.logic.client.module.impl.UseRateGate;
import dev.technix.logic.client.module.impl.AimAssist;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import dev.technix.logic.client.gui.GuiMotion;
import dev.technix.logic.client.input.OpposingKeys;
import dev.technix.logic.client.input.EdgePrediction;
import dev.technix.logic.client.input.EagleAlignment;
import dev.technix.logic.client.module.impl.Backtrack;
import dev.technix.logic.client.module.impl.AnchorMacro;
import dev.technix.logic.client.module.impl.AnchorMacro.Stage;
import dev.technix.logic.client.input.MovementKeys;
import dev.technix.logic.client.setting.MultiSelectSetting;
import dev.technix.logic.client.setting.RangeSetting;
import dev.technix.logic.client.render.ScreenProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.Setting;
import org.joml.Matrix4f;

public final class LogicChecks {
    public static void main(String[] args) {
        require(dev.technix.logic.client.module.impl.ClickGui.shouldHideCursor(true, true, true, dev.technix.mica.api.CaptureStatus.ACTIVE), "Protect cursor opened from gameplay");
        require(!dev.technix.logic.client.module.impl.ClickGui.shouldHideCursor(true, false, true, dev.technix.mica.api.CaptureStatus.ACTIVE)
            && !dev.technix.logic.client.module.impl.ClickGui.shouldHideCursor(true, true, false, dev.technix.mica.api.CaptureStatus.ACTIVE)
            && !dev.technix.logic.client.module.impl.ClickGui.shouldHideCursor(true, true, true, dev.technix.mica.api.CaptureStatus.FAILED)
            && !dev.technix.logic.client.module.impl.ClickGui.shouldHideCursor(false, true, true, dev.technix.mica.api.CaptureStatus.ACTIVE), "Restore native cursor outside a protected gameplay menu");
        checkClientSpoofer();
        checkNameHider();
        checkAntiBot();
        checkLagRange();
        checkAnchorMacro();
        checkTimer();
        checkAntiAction();
        checkInventoryModules();
        checkShieldBreaker();
        require(dev.technix.logic.client.module.impl.InterfaceModule.pingLabel(false, 73).equals("73 ms"), "Display player-list latency without custom pong hooks");
        require(dev.technix.logic.client.module.impl.InterfaceModule.pingLabel(false, 0).equals("0 ms"), "Accept zero latency");
        require(dev.technix.logic.client.module.impl.InterfaceModule.pingLabel(true, -1).equals("0 ms"), "Singleplayer has zero network latency");
        require(dev.technix.logic.client.module.impl.InterfaceModule.pingLabel(false, -1).equals("-- ms"), "Only show unknown when player information is unavailable");
        require(Backtrack.acceptsTargetType(net.minecraft.world.entity.monster.zombie.Zombie.class, false), "Backtrack must accept singleplayer living mobs when Players only is off");
        require(!Backtrack.acceptsTargetType(net.minecraft.world.entity.monster.zombie.Zombie.class, true), "Players only must exclude mobs");
        require(Backtrack.acceptsTargetType(net.minecraft.client.player.RemotePlayer.class, true), "Keep remote-player backtracking supported");
        require(!Backtrack.acceptsTargetType(net.minecraft.world.entity.Entity.class, false), "Never acquire nonliving targets");
        var backtrackModeCheck = new Backtrack();
        var backtrackMode = (ModeSetting) backtrackModeCheck.settings().stream().filter(setting -> setting.name().equals("Mode")).findFirst().orElseThrow();
        backtrackMode.set("Freeze");
        require(backtrackModeCheck.settings().stream().anyMatch(setting -> setting.name().equals("Release distance") && setting.visible())
            && backtrackModeCheck.settings().stream().anyMatch(setting -> setting.name().equals("Delay") && !setting.visible()), "Freeze shows its own settings and hides standard settings");
        var partial = (BooleanSetting) backtrackModeCheck.settings().stream().filter(setting -> setting.name().equals("Partial release")).findFirst().orElseThrow();
        var amount = backtrackModeCheck.settings().stream().filter(setting -> setting.name().equals("Release amount")).findFirst().orElseThrow();
        require(!amount.visible(), "Partial release options are hidden while disabled");
        partial.set(true);
        require(amount.visible(), "Partial release options appear when enabled");
        backtrackMode.set("Standard");
        require(backtrackModeCheck.settings().stream().anyMatch(setting -> setting.name().equals("Delay") && setting.visible()), "Standard settings return when switching modes");
        EagleAlignment aligned = new EagleAlignment();
        require(aligned.update(true, false, 0, true), "Sneak when the edge prediction detects danger");
        require(aligned.update(false, true, 0, true), "A second update in the same tick must not immediately release sneak");
        require(aligned.update(true, false, 1, true), "Keep sneak through alternating edge probes on a horizontal seam");
        require(aligned.update(false, false, 2, true), "Require actual clearance before starting a release");
        require(aligned.update(false, true, 3, true) && aligned.update(false, true, 3, true), "Only count unique client ticks toward safe release");
        require(aligned.update(false, true, 4, true), "Retain sneak during the first safe tick");
        require(!aligned.update(false, true, 5, true), "Release after two full safely clear client ticks");
        aligned.update(true, false, 6, true);
        require(!aligned.update(false, false, 6, false), "Standard mode retains immediate release behavior");
        double[] heading = EagleAlignment.alignedHeading(0.99, 0.05);
        require(heading[0] == 1 && heading[1] == 0, "Align near-horizontal probes to the block axis");
        heading = EagleAlignment.alignedHeading(0.707, -0.707);
        require(heading[0] == 0.707 && heading[1] == -0.707, "Preserve true diagonal movement in autoalignment");
        var toggleSneak = new net.minecraft.client.ToggleKeyMapping("logic.test.sneak", -1,
            net.minecraft.client.KeyMapping.Category.MOVEMENT, () -> true, false);
        MovementKeys.setState(toggleSneak, true, true);
        MovementKeys.setState(toggleSneak, true, true);
        require(toggleSneak.isDown(), "Repeated Eagle updates must not flip toggle-to-sneak off");
        MovementKeys.setState(toggleSneak, false, true);
        require(!toggleSneak.isDown(), "Release toggle-to-sneak without leaving a stuck input");
        var migratedTiming = new RangeSetting("Timing", "", 2, 2, 0, 20, 1);
        dev.technix.logic.client.config.ConfigStore.readSetting(migratedTiming, new com.google.gson.JsonPrimitive(7));
        require(migratedTiming.low() == 7 && migratedTiming.high() == 7 && migratedTiming.randomInt() == 7,
            "Migrate a fixed timing to equal range endpoints");
        var trigger = new dev.technix.logic.client.module.impl.Triggerbot();
        require(trigger.settings().stream().anyMatch(setting -> setting.name().equals("Reaction time (ms)") && setting instanceof RangeSetting),
            "Triggerbot exposes a reaction time range");
        MultiSelectSetting checks = new MultiSelectSetting("Checks", "", java.util.List.of("Pitch"), "Pitch", "Blocks", "Ground");
        checks.toggleOption("Blocks");
        require(checks.selected("Pitch") && checks.selected("Blocks"), "Multi-select retains existing selections when enabling another");
        checks.toggleOption("Pitch");
        require(!checks.selected("Pitch") && checks.selected("Blocks"), "Multi-select toggles choices independently");
        boolean invalidChoice = false;
        try { checks.toggleOption("Unknown"); } catch (IllegalArgumentException expected) { invalidChoice = true; }
        require(invalidChoice && checks.get().equals(java.util.List.of("Blocks")), "Reject unknown multi-select choices without changing the selection");
        RangeSetting endpoints = new RangeSetting("Pitch", "", 60, 90, 0, 90, 1);
        endpoints.setLow(100);
        require(endpoints.low() == 90 && endpoints.high() == 90, "A range handle cannot cross the other handle");
        endpoints.set(new RangeSetting.Value(80.4, 20.6));
        require(endpoints.low() == 21 && endpoints.high() == 80, "Normalize reversed endpoints and snap them to the step");
        endpoints.setHigh(-100);
        require(endpoints.high() == 21 && endpoints.low() == 21, "Clamp the upper handle at the lower handle");
        boolean invalidRange = false;
        try { endpoints.set(new RangeSetting.Value(Double.NaN, 20)); } catch (IllegalArgumentException expected) { invalidRange = true; }
        require(invalidRange && endpoints.low() == 21, "Reject non-finite ranges without modifying stored values");
        require(!EdgePrediction.reachesEdge(0.1, 0, d -> d < 0.25), "Zero advance does not sneak several ticks before an edge");
        require(EdgePrediction.reachesEdge(0.1, 100, d -> d < 0.25), "Advance time predicts an edge before the player reaches it");
        require(!EdgePrediction.reachesEdge(0.1, 100, d -> true), "Do not sneak on a continuously supported path");
        require(EdgePrediction.reachesEdge(0.4, 100, d -> d < 0.45 || d > 0.8), "Detect an intermediate gap even when the destination has support");
        require(!EdgePrediction.reachesEdge(0, 100, d -> true), "Do not sneak when stationary on supported ground");
        require(EdgePrediction.reachesEdge(0, 0, d -> false), "Keep sneak active on an already unsupported inner footprint");
        require(!EdgePrediction.reachesEdge(Double.NaN, 100, d -> false), "Reject invalid motion predictions");
        AABB slab = new AABB(0, 0, 0, 1, 0.5, 1);
        AABB slabFeet = new AABB(0.65, 0.42, 0.2, 1.15, 0.499, 0.8);
        require(EdgePrediction.reachesEdge(0.15, 100, d -> slabFeet.move(d, 0, 0).intersects(slab)), "Predict support loss on partial-height slab geometry");
        var jumpReset = new dev.technix.logic.client.combat.JumpResetTiming();
        require(!jumpReset.update(0, true, 100, 100, 0, 0), "Do not jump without an incoming hit");
        require(jumpReset.update(10, true, 100, 100, 0.99, 0.99), "Full chance and accuracy jump immediately on a new hit");
        require(!jumpReset.update(9, true, 100, 100, 0, 0), "Never repeat a jump during the same hurt cycle");
        require(!jumpReset.update(10, true, 0, 100, 0, 0), "Zero chance never triggers, including a zero roll");
        jumpReset.update(0, true, 100, 0, 0, 0);
        require(!jumpReset.update(10, true, 100, 0, 0, 0.99), "Low accuracy can delay a jump by three ticks");
        require(!jumpReset.update(9, true, 100, 0, 0, 0), "Wait for the first timing tick");
        require(!jumpReset.update(8, true, 100, 0, 0, 0), "Wait for the second timing tick");
        require(jumpReset.update(7, true, 100, 0, 0, 0), "Perform the delayed jump once");
        jumpReset.update(0, true, 100, 0, 0, 0);
        jumpReset.update(10, true, 100, 0, 0, 0.99);
        require(!jumpReset.update(9, false, 100, 0, 0, 0), "Cancel pending jumps when grounding or targeting is lost");
        require(!jumpReset.update(8, true, 100, 0, 0, 0), "Do not re-roll the same hit after eligibility returns");
        jumpReset.reset();
        require(!jumpReset.update(10, true, 100, 100, 0, 0), "Enabling or reconnecting mid-hit must not jump");
        jumpReset.consumeHit(10);
        require(!jumpReset.update(10, true, 100, 100, 0, 0), "Do not queue a second jump for a hit replayed by Hard reset");
        var sprintReset = new dev.technix.logic.client.module.impl.SprintReset();
        var resetMode = (ModeSetting) sprintReset.settings().getFirst();
        require(resetMode.options().equals(java.util.List.of("S-Tap", "W-Tap", "NoStop")),
            "Expose all three sprint reset modes");
        var sprintTiming = new dev.technix.logic.client.combat.SprintResetTiming();
        require(!sprintTiming.request(0, 1, 0, 0, 0), "Zero sprint reset chance must never trigger");
        require(sprintTiming.request(0, 1, 0, 100, 0.99), "Full chance accepts an eligible reset");
        require(sprintTiming.tick() && !sprintTiming.tick(), "Default reset holds for exactly one tick");
        require(sprintTiming.request(2, 3, 2, 100, 0), "Accept custom reset timing");
        require(!sprintTiming.request(0, 1, 0, 100, 0), "Do not replace a queued reset on another attack");
        require(!sprintTiming.tick() && !sprintTiming.tick(), "Wait the configured delay");
        require(sprintTiming.tick() && sprintTiming.tick() && sprintTiming.tick(), "Hold the configured duration");
        require(!sprintTiming.request(0, 1, 0, 100, 0), "Do not extend an active reset");
        require(!sprintTiming.tick(), "Release inputs after the configured duration");
        require(!sprintTiming.request(0, 1, 0, 100, 0), "Block attacks during cooldown");
        sprintTiming.tick();
        require(!sprintTiming.request(0, 1, 0, 100, 0), "Respect the entire cooldown");
        sprintTiming.tick();
        require(sprintTiming.request(0, 1, 0, 100, 0), "Allow a reset when cooldown expires");
        sprintTiming.reset();
        require(!sprintTiming.tick(), "Cancel a queued reset on disable or context loss");
        OpposingKeys keys = new OpposingKeys();
        keys.update(true, false);
        require(keys.firstActive() && !keys.secondActive(), "Allow a single movement key");
        keys.update(true, true);
        require(!keys.firstActive() && keys.secondActive(), "The newest opposing key takes priority");
        keys.update(true, true);
        require(!keys.firstActive() && keys.secondActive(), "Held keys and repeat events must not change priority");
        keys.update(true, false);
        require(keys.firstActive() && !keys.secondActive(), "Releasing the winner restores the held opposing key");
        keys.update(false, true);
        keys.update(true, true);
        require(keys.firstActive() && !keys.secondActive(), "Handle the opposite press order symmetrically");
        keys.reset();
        require(!keys.firstActive() && !keys.secondActive(), "Clear movement when focus or control is lost");
        keys.update(true, true);
        require(!keys.firstActive() && !keys.secondActive(), "Stay neutral when initial simultaneous input has no known press order");
        keys.update(false, true);
        require(!keys.firstActive() && keys.secondActive(), "Recover from an initial simultaneous press");
        keys.update(false, false);
        require(!keys.firstActive() && !keys.secondActive(), "Release both directions without leaving a stuck key");
        var batches = new PacketDelayQueue<String>(4);
        batches.offer("a", 2); batches.offer("b", 2); batches.offer("c", 3); batches.offer("d", 5);
        require(batches.releaseSpan(2).equals(java.util.List.of("a", "b", "c"))
            && batches.drain().equals(java.util.List.of("d")), "Partial release preserves complete tick groups and packet order");
        PacketDelayQueue<String> packets = new PacketDelayQueue<>(2);
        require(packets.offer("move1", 0) && packets.offer("move2", 50), "Queue movement in arrival order");
        require(!packets.offer("overflow", 60), "Bound the retained packet count");
        require(packets.release(99, 100).isEmpty(), "Do not release packets before their delay");
        require(packets.release(100, 100).equals(java.util.List.of("move1")), "Release the first expired packet exactly once");
        require(packets.drain().equals(java.util.List.of("move2")), "Flush remaining packets in order");
        require(packets.drain().isEmpty(), "Never replay flushed packets twice");
        packets.offer("old session", 200);
        packets.clear();
        require(packets.release(1000, 0).isEmpty(), "Discard stale packets across a session change");
        packets.offer("first", 0);
        packets.offer("second", 5);
        require(packets.release(20, 10).equals(java.util.List.of("first", "second")), "Replay multiple expired packets in FIFO order");

        UseRateGate placement = new UseRateGate();
        require(placement.tryUse(0, 20, 150_000_000), "Allow the initial physical placement without duplicating it");
        require(!placement.tryUse(50_000_000, 20, 150_000_000), "Respect activation delay after the first placement");
        require(placement.tryUse(150_000_000, 20, 150_000_000), "Allow placement when activation delay expires");
        require(!placement.tryUse(150_000_000, 20, 0), "Do not duplicate placement within one time slice");
        require(placement.tryUse(200_000_000, 20, 0), "Respect a 20 CPS placement interval");
        require(placement.tryUse(1_000_000_000, 20, 0) && !placement.tryUse(1_000_000_000, 20, 0), "Never burst placements to catch up after a stall");
        placement.reset();
        require(placement.tryUse(1_000_000_001, 20, 0), "A new press must reset placement timing");
        for (int rate = 5; rate <= 20; rate++) {
            UseRateGate cadence = new UseRateGate();
            int attempts = 0;
            for (int tick = 0; tick < 1000; tick++) {
                if (cadence.tryUseTick(tick, rate, 0)) attempts++;
                require(!cadence.tryUseTick(tick, rate, 0), "Do not duplicate use within one client tick");
            }
            require(attempts == rate * 50, "Maintain the requested placement cadence across tick rounding at " + rate + " CPS: " + attempts);
        }
        UseRateGate tickPlacement = new UseRateGate();
        for (int tick = 0; tick < 20; tick++)
            require(tickPlacement.tryUseTick(tick, 20, 0), "20 CPS must allow every client tick regardless of wall-clock jitter");
        require(tickPlacement.tryUseTick(1, 20, 0), "Recover immediately when the player tick clock resets");
        tickPlacement.reset();
        require(tickPlacement.tryUseTick(0, 20, 3) && !tickPlacement.tryUseTick(1, 20, 3)
            && !tickPlacement.tryUseTick(2, 20, 3) && tickPlacement.tryUseTick(3, 20, 3), "Preserve the configured activation delay in game ticks");
        float at30 = 0, at144 = 0;
        for (int i = 0; i < 30; i++) at30 = GuiMotion.smooth(at30, 1, 3, 1f / 30);
        for (int i = 0; i < 144; i++) at144 = GuiMotion.smooth(at144, 1, 3, 1f / 144);
        close(at30, at144, "Dropdown animations must not depend on frame rate");
        float closing = GuiMotion.smooth(0.5f, 0, 16, 1f / 60);
        require(closing > 0 && closing < 0.5f, "Panel closing must ease without overshooting");
        close(GuiMotion.smooth(0.5f, 1, 16, 0), 0.5, "A zero-duration animation must not advance");
        require(GuiMotion.smooth(0, 1, 16, 10) < 1, "A stalled frame must not snap the opening animation");
        BooleanSetting flag = new BooleanSetting("Flag", "", false);
        flag.toggle();
        require(flag.get(), "Boolean settings must toggle their stored value");
        ModeSetting mode = new ModeSetting("Target", "", "Chest", "Head", "Chest", "Pelvis");
        mode.set("Head");
        require(mode.get().equals("Head"), "Dropdown selections update the registered setting");
        boolean invalidMode = false;
        try { mode.set("Unknown"); } catch (IllegalArgumentException expected) { invalidMode = true; }
        require(invalidMode && mode.get().equals("Head"), "Invalid modes must not change the current selection");
        boolean immutableOptions = false;
        try { mode.options().add("Unknown"); } catch (UnsupportedOperationException expected) { immutableOptions = true; }
        require(immutableOptions, "Dropdown choices must remain immutable");

        NumberSetting range = new NumberSetting("Range", "", 3, 1, 6, 0.1);
        range.set(100.0);
        close(range.get(), 6, "Clamp above maximum");
        range.set(-100.0);
        close(range.get(), 1, "Clamp below minimum");
        range.set(3.27);
        close(range.get(), 3.3, "Snap slider input to its step");
        boolean rejected = false;
        try { range.set(Double.NaN); } catch (IllegalArgumentException expected) { rejected = true; }
        require(rejected, "Reject non-finite slider input");

        close(CombatMath.wrapDegrees(358), -2, "Wrap through the yaw seam");
        close(CombatMath.approachAngle(179, -179, 1), 180, "Aim follows the shortest turn");
        close(CombatMath.approachAngle(0, 90, 4), 4, "Aim respects its per-tick turn limit");
        float smooth30 = smoothForOneSecond(0, 30, 80, 30);
        close(smoothForOneSecond(0, 30, 80, 60), smooth30, "Smoothing is consistent at 30 and 60 FPS");
        close(smoothForOneSecond(0, 30, 80, 144), smooth30, "Smoothing is consistent at 30 and 144 FPS");
        require(smooth30 > 29 && smooth30 <= 30, "Ease into a target without overshooting");
        float firstFrame = CombatMath.smoothAngle(0, 90, 80, 1.0 / 144);
        require(firstFrame > 0 && firstFrame < 1, "Apply small frame updates instead of four-degree tick jumps");
        require(CombatMath.smoothAngle(0, 90, 80, 5) <= 4, "Cap movement after a long render stall");
        close(CombatMath.smoothAngle(10, 90, 80, 0), 10, "A zero-duration frame must not move the camera");
        close(CombatMath.smoothAngle(10, 90, 80, Double.NaN), 10, "Ignore invalid frame durations");
        float acrossSeam = CombatMath.smoothAngle(179, -179, 80, 1.0 / 60);
        require(acrossSeam > 179 && acrossSeam < 181, "Smooth through the shortest yaw seam");
        close(smoothForOneSecond(0, -30, 80, 60), -smooth30, "Negative turns use the same smoothing");
        float softTurn = CombatMath.smoothAngle(0, 5, 80, 1.0 / 60, 10);
        require(softTurn > 0 && softTurn < CombatMath.smoothAngle(0, 5, 80, 1.0 / 60),
            "Higher axis smoothing slows the response");
        float soft30 = 0, soft144 = 0;
        for (int i = 0; i < 30; i++) soft30 = CombatMath.smoothAngle(soft30, 30, 80, 1.0 / 30, 10);
        for (int i = 0; i < 144; i++) soft144 = CombatMath.smoothAngle(soft144, 30, 80, 1.0 / 144, 10);
        close(soft30, soft144, "Custom smoothing remains frame-rate independent");
        close(CombatMath.smoothAngle(10, 90, 80, 1.0 / 60, Double.NaN), 10, "Reject invalid smoothing");

        AABB hitbox = new AABB(-1, 0, 4, 1, 2, 6);
        Vec3 eye = new Vec3(3, 1.5, 0);
        Vec3 closest = AimAssist.aimPoint(hitbox, eye, 1.4, true, 0, 0, 0);
        require(closest.equals(new Vec3(1, 1.5, 4)), "Closest clamps each eye coordinate to the hitbox");
        require(AimAssist.aimPoint(hitbox, eye, 1.4, false, 0, 0, 0).equals(new Vec3(0, 1.4, 5)),
            "Zero midpoint offsets preserve the selected bone");
        Vec3 shifted = AimAssist.aimPoint(hitbox, eye, 1.4, false, 100, 100, 0);
        require(shifted.equals(new Vec3(-1, 2, 5)), "Midpoint offsets move right and up and clamp to the hitbox");
        Vec3 rotated = AimAssist.aimPoint(hitbox, eye, 1.4, false, 100, -100, 90);
        close(rotated.x, 0, "Horizontal offsets follow the camera orientation");
        close(rotated.z, 4, "Camera-right rotates with yaw");
        close(rotated.y, 0.4, "Vertical midpoint moves independently");
        Vec3 inside = new Vec3(0, 1, 5);
        require(AimAssist.aimPoint(hitbox, inside, 1.4, true, 0, 0, 0).equals(inside),
            "An eye inside the hitbox needs no closest-point turn");
        var aim = new AimAssist();
        require(aim.settings().stream().filter(s -> s instanceof NumberSetting &&
            (s.name().endsWith("smoothing") || s.name().endsWith("midpoint"))).count() == 4,
            "Register both axes of smoothing and midpoints for sliders and persistence");
        require(!CombatMath.cooldownReady(0.99f, 1), "Do not attack before full charge");
        require(CombatMath.cooldownReady(1, 1), "Attack when fully charged");
        require(!CombatMath.cooldownReady(0, 0.9), "Do not attack immediately after cooldown reset");

        Matrix4f matrix = new Matrix4f().perspective((float) Math.toRadians(90), 1, 0.05f, 100);
        var centered = ScreenProjection.bounds(matrix, -1, -1, -6, 1, 1, -4, 0.05f, 800, 800);
        require(centered != null, "Project an entity in front of the camera");
        close(centered.left(), 300, "Project left edge");
        close(centered.right(), 500, "Project right edge");
        close(centered.top(), 300, "Project top edge");
        close(centered.bottom(), 500, "Project bottom edge");
        require(ScreenProjection.bounds(matrix, -1, -1, 4, 1, 1, 6, 0.05f, 800, 800) == null,
            "Never draw mirrored boxes for entities behind the camera");
        require(ScreenProjection.bounds(matrix, 40, -1, -6, 42, 1, -4, 0.05f, 800, 800) == null,
            "Reject fully offscreen boxes");
        var clipped = ScreenProjection.bounds(matrix, -1, -1, -1, 1, 1, 1, 0.05f, 800, 800);
        require(clipped != null && clipped.left() >= 0 && clipped.top() >= 0
            && clipped.right() <= 800 && clipped.bottom() <= 800,
            "Clip boxes crossing the camera near plane to finite screen bounds");

         
        Matrix4f reversed = new Matrix4f().perspective((float) Math.toRadians(90), 1, 100, 0.05f, true);
        var reversedBox = ScreenProjection.bounds(reversed, -1, -1, -6, 1, 1, -4, 0.05f, 800, 800);
        require(reversedBox != null, "Support Vulkan's reversed-depth projection");
        close(reversedBox.left(), centered.left(), "Depth convention must not move the screen rectangle");
        System.out.println("Logic checks passed: packet FIFO and limits, placement timing, dropdown animations, settings, aim smoothing, weapon cooldown, and ESP projection.");
    }

    private static void checkTimer() {
        var module = new dev.technix.logic.client.module.impl.TimerModule();
        var speed = (NumberSetting) module.settings().getFirst();
        require(speed.min() == 0 && speed.max() == 2 && speed.get() == 1, "Timer supports 0–2 with normal speed by default");
        module.setEnabled(true);
        try {
            var clock = new net.minecraft.client.DeltaTracker.Timer(20, 0, module::tickDuration);
            require(clock.advanceGameTime(1000) == 20, "Normal Timer speed produces 20 ticks per second");
            speed.set(2.0);
            require(clock.advanceGameTime(2000) == 40, "Double Timer speed produces 40 ticks per second");
            speed.set(0.5);
            require(clock.advanceGameTime(3000) == 10, "Half Timer speed produces 10 ticks per second");
            speed.set(0.0);
            require(clock.advanceGameTime(4000) == 0 && clock.advanceGameTime(5000) == 0, "Zero speed pauses game ticks without NaN");
            clock.advanceRealTime(50);
            require(clock.getRealtimeDeltaTicks() == 1, "Pausing game ticks preserves real-time UI timing");
            module.setEnabled(false);
            require(clock.advanceGameTime(5050) == 1, "Disabling Timer resumes immediately without a catch-up burst");
            require(dev.technix.logic.host.RuntimeHost.tickDuration(50) == 50, "Detached injector restores vanilla tick timing");
        } finally { module.setEnabled(false); }
    }

    private static void checkInventoryModules() {
        net.minecraft.core.registries.BuiltInRegistries.DATA_COMPONENT_INITIALIZERS
            //? if <26.3 {
            .build(net.minecraft.data.registries.VanillaRegistries.createLookup())
            //?} else {
            /*.build(net.minecraft.data.registries.VanillaRegistries.createWorldLookup())
            *///?}
            .forEach(net.minecraft.core.component.DataComponentInitializers.PendingComponents::apply);
        var head = net.minecraft.world.entity.EquipmentSlot.HEAD;
        var diamond = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND_HELMET);
        var leather = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.LEATHER_HELMET);
        require(dev.technix.logic.client.module.impl.AutoArmor.armorScore(diamond, head)
            > dev.technix.logic.client.module.impl.AutoArmor.armorScore(leather, head), "Rank diamond armor above leather");
        require(dev.technix.logic.client.module.impl.AutoArmor.eligible(diamond, head, 5), "Accept healthy matching armor");
        require(!dev.technix.logic.client.module.impl.AutoArmor.eligible(diamond, net.minecraft.world.entity.EquipmentSlot.FEET, 5), "Never put helmets in boot slots");
        diamond.setDamageValue(diamond.getMaxDamage() - 1);
        require(!dev.technix.logic.client.module.impl.AutoArmor.eligible(diamond, head, 5), "Skip armor below the durability threshold");
        require(!dev.technix.logic.client.module.impl.AutoArmor.eligible(new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.CARVED_PUMPKIN), head, 0), "Do not treat decorative equipment as armor");
        require(!dev.technix.logic.client.module.impl.AutoArmor.eligible(net.minecraft.world.item.ItemStack.EMPTY, head, 0), "Ignore empty armor candidates");
        require(dev.technix.logic.client.module.impl.ChestStealer.matchesFilter("minecraft:diamond", "Whitelist", " iron_ingot, DIAMOND "), "Normalize short item IDs and whitespace");
        require(!dev.technix.logic.client.module.impl.ChestStealer.matchesFilter("minecraft:diamond_block", "Whitelist", "diamond"), "Match exact IDs rather than substrings");
        require(!dev.technix.logic.client.module.impl.ChestStealer.matchesFilter("minecraft:diamond", "Blacklist", "minecraft:diamond"), "Exclude blacklisted items");
        require(!dev.technix.logic.client.module.impl.ChestStealer.matchesFilter("minecraft:diamond", "Whitelist", " , ")
            && dev.technix.logic.client.module.impl.ChestStealer.matchesFilter("minecraft:diamond", "All", ""), "Empty whitelist takes nothing while All ignores the list");
        var incoming = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 32);
        var existing = new net.minecraft.world.item.ItemStack(net.minecraft.world.item.Items.DIAMOND, 63);
        require(dev.technix.logic.client.module.impl.ChestStealer.canFit(incoming, existing, 64), "Allow partial stack merges in a full inventory");
        require(!dev.technix.logic.client.module.impl.ChestStealer.canFit(incoming, existing, 63), "Respect destination slot limits");
        existing.setCount(64);
        require(!dev.technix.logic.client.module.impl.ChestStealer.canFit(incoming, existing, 64), "Skip full stacks");
        existing.setCount(1);
        existing.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME, net.minecraft.network.chat.Component.literal("Named"));
        require(!dev.technix.logic.client.module.impl.ChestStealer.canFit(incoming, existing, 64), "Different item components prevent stacking");
        require(dev.technix.logic.client.module.impl.ChestStealer.canFit(incoming, net.minecraft.world.item.ItemStack.EMPTY, 64), "Allow empty inventory destinations");
        var armor = new dev.technix.logic.client.module.impl.AutoArmor();
        var chest = new dev.technix.logic.client.module.impl.ChestStealer();
        require(!armor.enabled() && !chest.enabled() && armor.category() == dev.technix.logic.client.module.Category.PLAYER
            && chest.category() == dev.technix.logic.client.module.Category.PLAYER, "Inventory modules start disabled in Player");
        var settings = chest.settings().stream().collect(java.util.stream.Collectors.toMap(dev.technix.logic.client.setting.Setting::name, s -> s));
        require(!settings.get("Item IDs").visible(), "Hide the item list in All mode");
        ((ModeSetting) settings.get("Item filter")).set("Whitelist");
        require(settings.get("Item IDs").visible(), "Expose item IDs when filtering");
    }

    private static void checkShieldBreaker() {
        var timing = new dev.technix.logic.client.combat.ShieldBreakTiming();
        var none = dev.technix.logic.client.combat.ShieldBreakTiming.Action.NONE;
        require(timing.start(0, 50) && timing.busy() && !timing.start(1, 0), "Start one shield-break sequence without restarting its timer");
        require(timing.poll(49, 100, 80, 500) == none, "Wait for the switch delay");
        require(timing.poll(50, 100, 80, 500) == dev.technix.logic.client.combat.ShieldBreakTiming.Action.SWITCH, "Select the axe when the switch delay expires");
        require(timing.poll(149, 100, 80, 500) == none, "Wait after the actual axe selection before attacking");
        require(timing.poll(150, 100, 80, 500) == dev.technix.logic.client.combat.ShieldBreakTiming.Action.ATTACK
            && timing.restoring(), "Request exactly one attack before entering the restore wait");
        require(timing.poll(150, 100, 80, 500) == none && timing.poll(229, 100, 80, 500) == none, "Never repeat an attack while waiting to restore");
        require(timing.poll(230, 100, 80, 500) == dev.technix.logic.client.combat.ShieldBreakTiming.Action.RESTORE
            && !timing.busy(), "Restore the slot and release attack control");
        require(!timing.start(729, 0) && timing.start(730, 0), "Respect the retry cooldown");
        timing.cancel(731, 500);
        require(!timing.busy() && timing.poll(900, 0, 0, 0) == none && !timing.start(1230, 0), "Cancellation discards pending attacks and retains the cooldown");
        timing.reset();
        require(timing.start(0, 50) && timing.poll(500, 100, 80, 500) == dev.technix.logic.client.combat.ShieldBreakTiming.Action.SWITCH
            && timing.poll(599, 100, 80, 500) == none, "A stalled frame cannot collapse the delay after switching");
        timing.reset();
        require(!timing.busy() && timing.start(0, 0)
            && timing.poll(0, 0, 0, 0) == dev.technix.logic.client.combat.ShieldBreakTiming.Action.SWITCH
            && timing.poll(0, 0, 0, 0) == dev.technix.logic.client.combat.ShieldBreakTiming.Action.ATTACK
            && timing.poll(0, 0, 0, 0) == dev.technix.logic.client.combat.ShieldBreakTiming.Action.RESTORE, "Zero delays preserve switch-attack-restore order");
        require(!dev.technix.logic.client.LogicClient.shieldBreakerBusy(), "Detached shield breaker does not block input");
    }

    private static void checkAntiAction() {
        net.minecraft.SharedConstants.tryDetectVersion();
        net.minecraft.server.Bootstrap.bootStrap();
        var module = new dev.technix.logic.client.module.impl.AntiAction();
        var anchor = net.minecraft.world.level.block.Blocks.RESPAWN_ANCHOR.defaultBlockState();
        var charged = anchor.setValue(net.minecraft.world.level.block.RespawnAnchorBlock.CHARGE, 1);
        require(!module.blocksBlockUse(anchor, false, true, true), "Allow the first anchor charge");
        require(module.blocksBlockUse(charged, false, true, true), "Prevent a second anchor charge");
        require(module.blocksBlockUse(anchor, true, true, true), "Prevent glowstone placement while sneaking on an anchor");
        var chest = net.minecraft.world.level.block.Blocks.ENDER_CHEST.defaultBlockState();
        require(module.blocksBlockUse(chest, false, false, true), "Prevent opening an ender chest with combat items");
        require(!module.blocksBlockUse(chest, true, false, true), "Allow sneaking to open an ender chest");
        require(!module.blocksBlockUse(chest, false, false, false), "Allow opening an ender chest with other items");
        require(module.blocksBlockUse(net.minecraft.world.level.block.Blocks.STONE.defaultBlockState(), false, true, true), "Prevent ordinary glowstone placement");
        require(!module.blocksBlockUse(net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), false, true, true), "Ignore missed block targets");
        require(module.blocksFirework(true, false, false), "Prevent rockets without gliding");
        require(!module.blocksFirework(true, true, false) && !module.blocksFirework(true, false, true)
            && !module.blocksFirework(false, false, false), "Allow gliding, offhand crossbows and other held items");
        require(!dev.technix.logic.client.module.impl.AntiAction.isCombatItem(net.minecraft.world.item.ItemStack.EMPTY), "Empty hands are not combat items");
        for (var setting : module.settings()) if (setting instanceof BooleanSetting toggle) toggle.set(false);
        require(!module.blocksBlockUse(charged, false, true, true) && !module.blocksBlockUse(chest, false, true, true)
            && !module.blocksFirework(true, false, false), "Disabled protections allow interactions");
    }

    private static void checkAnchorMacro() {
        require(AnchorMacro.nextStage(false, 0, true, true, true) == Stage.PLACE,
            "Placement input places an anchor on a non-anchor target");
        require(AnchorMacro.nextStage(true, 0, true, true, true) == Stage.CHARGE,
            "Holding placement on an empty anchor uses charging settings");
        require(AnchorMacro.nextStage(true, 0, true, false, true) == null,
            "Placement input must not bypass disabled charging");
        require(AnchorMacro.nextStage(true, 1, true, true, true) == Stage.DETONATE,
            "Holding placement on a charged anchor must not place an adjacent anchor");
        require(AnchorMacro.nextStage(true, 1, true, true, false) == null,
            "Respect disabled detonation even while placement is held");
        require(AnchorMacro.canDetonate(1, false, false)
            && !AnchorMacro.canDetonate(1, true, false) && !AnchorMacro.canDetonate(1, false, true)
            && AnchorMacro.canDetonate(4, true, true) && !AnchorMacro.canDetonate(0, false, false),
            "Reject glowstone interactions that would charge or defer to the off hand");
        require(!AnchorMacro.confirmed(Stage.CHARGE, 0, true, 0)
            && AnchorMacro.confirmed(Stage.CHARGE, 0, true, 1)
            && !AnchorMacro.confirmed(Stage.DETONATE, 1, true, 1)
            && AnchorMacro.confirmed(Stage.DETONATE, 1, false, 0)
            && AnchorMacro.confirmed(Stage.PLACE, 0, true, 0),
            "Wait for the expected server block transition before advancing the macro");
        var macro = new AnchorMacro();
        require(macro.settings().stream().filter(setting -> setting.name().endsWith("inventory"))
            .allMatch(setting -> setting.get().equals("Temporary")), "Default inventory modes can complete the full sequence");
        var position = new net.minecraft.core.BlockPos(4, 5, 6);
        var hit = new net.minecraft.world.phys.BlockHitResult(new Vec3(4.5, 6, 6.5), net.minecraft.core.Direction.UP, position, false);
        require(dev.technix.logic.client.module.impl.AnchorMacro.placementPosition(hit, false).equals(position.above()),
            "Track anchor placements beside the clicked solid block");
        require(dev.technix.logic.client.module.impl.AnchorMacro.placementPosition(hit, true).equals(position),
            "Track anchors replacing grass or fire at the clicked position");
        var bind = new dev.technix.logic.client.setting.KeybindSetting("Place", "");
        var gui = new dev.technix.logic.client.module.impl.ClickGui();
        gui.startBinding(bind);
        gui.handleKey(org.lwjgl.glfw.GLFW.GLFW_KEY_G, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        require(bind.get() == org.lwjgl.glfw.GLFW.GLFW_KEY_G, "Capture keyboard action bindings");
        gui.startBinding(bind);
        gui.handleKey(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        require(bind.get() == org.lwjgl.glfw.GLFW.GLFW_KEY_UNKNOWN && !bind.isDown(), "Clear action bindings without polling an invalid key");
        gui.startBinding(bind);
        require(gui.bindMouse(org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_5, org.lwjgl.glfw.GLFW.GLFW_PRESS), "Capture side-button action bindings");
        boolean invalid = false;
        try { dev.technix.logic.client.config.ConfigStore.readSetting(bind, new com.google.gson.JsonPrimitive(2.5)); }
        catch (ArithmeticException expected) { invalid = true; }
        require(invalid && bind.get() == org.lwjgl.glfw.GLFW.GLFW_MOUSE_BUTTON_5, "Reject fractional key codes without changing a binding");
        require(!dev.technix.logic.client.LogicClient.anchorBusy() && !dev.technix.logic.client.LogicClient.anchorControlsUse(),
            "Detached macro hooks leave normal input available");
    }

    private static void checkLagRange() {
        var movement = new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Pos(1, 2, 3, true, false);
        var slot = new net.minecraft.network.protocol.game.ServerboundSetCarriedItemPacket(2);
        var use = new net.minecraft.network.protocol.game.ServerboundUseItemPacket(net.minecraft.world.InteractionHand.MAIN_HAND, 0, 0, 0);
        require(dev.technix.logic.client.module.impl.Blink.buffersGameplayPacket(movement)
            && dev.technix.logic.client.module.impl.Blink.buffersGameplayPacket(slot)
            && dev.technix.logic.client.module.impl.Blink.buffersGameplayPacket(use),
            "Blink and Lag Range share ordering for movement, hotbar changes and combat actions");
        var debugPing = new net.minecraft.network.protocol.ping.ServerboundPingRequestPacket(1);
        var tickEnd = net.minecraft.network.protocol.game.ServerboundClientTickEndPacket.INSTANCE;
        require(!dev.technix.logic.client.module.impl.Blink.buffersGameplayPacket(new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(1))
            && dev.technix.logic.client.module.impl.Blink.buffersGameplayPacket(debugPing)
            && dev.technix.logic.client.module.impl.Blink.buffersGameplayPacket(tickEnd),
            "Debug pings and tick-end packets must retain gameplay order while keepalives remain immediate");
        var tickQueue = new PacketDelayQueue<net.minecraft.network.protocol.Packet<?>>(3);
        tickQueue.offer(movement, 0);
        tickQueue.offer(debugPing, 10);
        tickQueue.offer(tickEnd, 20);
        require(tickQueue.release(150, 150).equals(java.util.List.of(movement))
            && tickQueue.drain().equals(java.util.List.of(debugPing, tickEnd)),
            "Debug pings must not overtake held movement or move past their tick-end boundary");
        record Outgoing(net.minecraft.network.protocol.Packet<?> packet, io.netty.channel.ChannelFutureListener listener, boolean flush) { }
        var queue = new PacketDelayQueue<Outgoing>(3);
        io.netty.channel.ChannelFutureListener listener = future -> { };
        var first = new Outgoing(movement, listener, false);
        var second = new Outgoing(slot, null, true);
        var third = new Outgoing(use, listener, false);
        require(queue.offer(first, 0) && queue.offer(second, 10) && queue.offer(third, 20), "Queue a complete ordered action sequence");
        require(!queue.offer(first, 30), "Bound outgoing queue growth");
        require(queue.release(150, 150).equals(java.util.List.of(first)), "Only replay packets whose own hold budget expired");
        require(queue.drain().equals(java.util.List.of(second, third)) && queue.size() == 0,
            "Action and Blink handoff drains retain packet order, listeners and flush flags");
        queue.offer(first, 200);
        queue.clear();
        require(queue.drain().isEmpty(), "A session correction discards queued movement");
        try {
            var lagRange = new dev.technix.logic.client.module.impl.LagRange();
            var position = lagRange.getClass().getDeclaredField("serverPosition");
            position.setAccessible(true);
            Vec3 origin = new Vec3(0, 2, 3);
            position.set(lagRange, origin);
            var sent = lagRange.getClass().getDeclaredMethod("observeSent", net.minecraft.network.protocol.Packet.class);
            sent.setAccessible(true);
            queue.offer(first, 0);
            for (var entry : queue.release(149, 150)) sent.invoke(lagRange, entry.packet());
            require(position.get(lagRange).equals(origin), "Queued movement must leave the server marker behind the player");
            for (var entry : queue.release(150, 150)) sent.invoke(lagRange, entry.packet());
            require(position.get(lagRange).equals(new Vec3(1, 2, 3)), "Advance the server marker only when movement is transmitted");
            sent.invoke(lagRange, new net.minecraft.network.protocol.game.ServerboundMovePlayerPacket.Rot(90, 0, true, false));
            require(position.get(lagRange).equals(new Vec3(1, 2, 3)), "Rotation-only packets must preserve the transmitted position");
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not verify Lag Range's transmitted position", failure);
        }
    }

    private static void checkAntiBot() {
        var stationary = new dev.technix.logic.client.combat.BotChecks.Options(true, "Off", false, false, 0, false);
        var history = new dev.technix.logic.client.combat.BotChecks();
        var still = new dev.technix.logic.client.combat.BotChecks.Sample(0, 4, false, false, 30, "Player");
        require(history.update(still, stationary), "Stationary check rejects unmoving players");
        var moving = new dev.technix.logic.client.combat.BotChecks.Sample(0.5, 4, false, false, 30, "Player");
        require(history.update(moving, stationary) && !history.update(moving, stationary), "Accumulate squared horizontal movement until one");
        require(!history.update(still, stationary), "A previously moving player stays valid when standing still");
        var uuid = new dev.technix.logic.client.combat.BotChecks.Options(false, "While stationary", false, false, 0, false);
        var versionTwo = new dev.technix.logic.client.combat.BotChecks.Sample(0, 2, false, false, 30, "Player");
        require(new dev.technix.logic.client.combat.BotChecks().update(versionTwo, uuid), "Reject stationary version-2 UUIDs");
        require(!history.update(versionTwo, uuid), "Allow moving version-2 UUIDs in stationary mode");
        require(history.update(versionTwo, new dev.technix.logic.client.combat.BotChecks.Options(false, "Always", false, false, 0, false)), "Always mode ignores movement history");
        var invisibility = new dev.technix.logic.client.combat.BotChecks.Options(false, "Off", false, true, 0, false);
        var hidden = new dev.technix.logic.client.combat.BotChecks.Sample(0, 4, false, true, 30, "Player");
        history = new dev.technix.logic.client.combat.BotChecks();
        require(history.update(hidden, invisibility), "Reject consistently invisible unarmoured players");
        require(!history.update(still, invisibility) && !history.update(hidden, invisibility), "Remember players seen visible after tick 21");
        var age = new dev.technix.logic.client.combat.BotChecks.Options(false, "Off", false, false, 20, false);
        var young = new dev.technix.logic.client.combat.BotChecks.Sample(0, 4, false, false, 5, "Player");
        history = new dev.technix.logic.client.combat.BotChecks();
        require(history.update(young, age) && !history.update(still, age), "Release players after minimum age");
        require(new dev.technix.logic.client.combat.BotChecks().update(
            new dev.technix.logic.client.combat.BotChecks.Sample(0, 4, true, false, 30, "Player"),
            new dev.technix.logic.client.combat.BotChecks.Options(false, "Off", true, false, 0, false)), "Detect sleeping players when enabled");
        require(!dev.technix.logic.client.combat.BotChecks.hasValidNameCharacter("\u00a7a!!!")
            && dev.technix.logic.client.combat.BotChecks.hasValidNameCharacter("\u00a7aPlayer_1"), "Skip colour codes when validating usernames");
        require(!dev.technix.logic.client.LogicClient.isBot(null), "An inactive client does not exclude targets");
    }

    private static void checkNameHider() {
        var mask = new dev.technix.logic.client.render.NameMask(java.util.Map.of("alice", "Hidden$1", "bob", "Player"));
        require(mask.replace("Alice met BOB. Alice_2 Malice").equals("Hidden$1 met Player. Alice_2 Malice"),
            "Replace complete names case-insensitively and treat alias text literally");
        var red = net.minecraft.network.chat.Style.EMPTY.withColor(0xFF0000);
        var blue = net.minecraft.network.chat.Style.EMPTY.withColor(0x0000FF);
        var sequence = net.minecraft.util.FormattedCharSequence.composite(
            net.minecraft.util.FormattedCharSequence.forward("Ali", red),
            net.minecraft.util.FormattedCharSequence.forward("ce!", blue));
        var rewritten = mask.replace(sequence);
        StringBuilder output = new StringBuilder();
        rewritten.accept((index, style, codepoint) -> {
            output.appendCodePoint(codepoint);
            require(style.equals(codepoint == '!' ? blue : red), "Keep replacement and surrounding character styles");
            return true;
        });
        require(output.toString().equals("Hidden$1!"), "Replace names split across styled runs");
        var component = net.minecraft.network.chat.FormattedText.composite(
            net.minecraft.network.chat.FormattedText.of("Ali", red), net.minecraft.network.chat.FormattedText.of("ce!", blue));
        require(mask.replace(component).getString().equals("Hidden$1!") && component.getString().equals("Alice!"),
            "Measure protected text without changing source components");
        require(new dev.technix.logic.client.render.NameMask(java.util.Map.of()).replace(sequence) == sequence,
            "An empty mask preserves the original sequence");
        var emptyNameMask = new dev.technix.logic.client.render.NameMask(java.util.Map.of("", "You", "alice", "Hidden"));
        var punctuation = net.minecraft.util.FormattedCharSequence.forward("!", red);
        require(emptyNameMask.replace(punctuation) == punctuation && emptyNameMask.replace("!").equals("!"),
            "Ignore empty names without matching past the end of styled text");
        require(emptyNameMask.replace("Alice!").equals("Hidden!"), "Keep valid replacements alongside an empty name");
        var hider = new dev.technix.logic.client.module.impl.NameHider();
        var modes = (MultiSelectSetting) hider.settings().getFirst();
        var selfName = (dev.technix.logic.client.setting.StringSetting) hider.settings().get(1);
        require(selfName.visible(), "Show self name for Self mode");
        modes.set(java.util.List.of("Others"));
        require(!selfName.visible(), "Hide self name for Others mode");
        modes.set(java.util.List.of("Self", "Others"));
        require(selfName.visible(), "Support hiding self and others together");

        var gui = new dev.technix.logic.client.module.impl.ClickGui();
        var field = new dev.technix.logic.client.setting.StringSetting("Name", "", "AB");
        gui.editText(field);
        gui.handleKey(org.lwjgl.glfw.GLFW.GLFW_KEY_LEFT, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        gui.handleCharacter(0x1F600);
        gui.handleKey(org.lwjgl.glfw.GLFW.GLFW_KEY_BACKSPACE, com.mojang.blaze3d.platform.InputConstants.REPEAT);
        require(field.get().equals("AB") && gui.textCursor() == 1, "Insert and delete whole Unicode codepoints at the cursor");
        gui.handleCharacter('C');
        gui.handleKey(org.lwjgl.glfw.GLFW.GLFW_KEY_ENTER, org.lwjgl.glfw.GLFW.GLFW_PRESS);
        require(field.get().equals("ACB") && !gui.isEditing(field), "Enter finishes editing the self name");
        field.visibleWhen(() -> false);
        gui.editText(field);
        gui.handleCharacter('X');
        require(field.get().equals("ACB"), "Hidden text fields cannot receive characters");
    }

    private static void checkClientSpoofer() {
        var spoofer = new dev.technix.logic.client.module.impl.ClientSpoofer();
        var brand = (ModeSetting) spoofer.settings().getFirst();
        var options = spoofer.settings().stream().collect(java.util.stream.Collectors.toMap(Setting::name, setting -> setting));
        var channels = (dev.technix.logic.client.setting.BooleanSetting) options.get("Hide mod channels");
        var translations = (dev.technix.logic.client.setting.BooleanSetting) options.get("Block translation probes");
        var packs = (dev.technix.logic.client.setting.BooleanSetting) options.get("Anti force resource pack");
        var requiredPack = new net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket(java.util.UUID.randomUUID(), "https://example.com/pack.zip", "", true, java.util.Optional.empty());
        var optionalPack = new net.minecraft.network.protocol.common.ClientboundResourcePackPushPacket(java.util.UUID.randomUUID(), "https://example.com/pack.zip", "", false, java.util.Optional.empty());
        var original = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
            new net.minecraft.network.protocol.common.custom.BrandPayload("original"));
        require(spoofer.rewriteBrand(original) == original, "Disabled spoofer preserves the brand packet");
        var register = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
            new net.minecraft.network.protocol.common.custom.DiscardedPayload(net.minecraft.resources.Identifier.parse("minecraft:register")));
        require(!spoofer.suppress(register), "Disabled spoofer preserves channel registrations");
        spoofer.setEnabled(true);
        try {
            String[] expected = {"fabric", "vanilla", "lunarclient:v2.12.0-2349,fabric"};
            for (int i = 0; i < brand.options().size(); i++) {
                brand.set(brand.options().get(i));
                var rewritten = (net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket) spoofer.rewriteBrand(original);
                require(((net.minecraft.network.protocol.common.custom.BrandPayload) rewritten.payload()).brand().equals(expected[i]),
                    "Replace the outgoing brand for " + brand.get());
            }
            var keepAlive = new net.minecraft.network.protocol.common.ServerboundKeepAlivePacket(42);
            require(spoofer.rewriteBrand(keepAlive) == keepAlive, "Preserve unrelated outgoing packets");
            var otherPayload = new net.minecraft.network.protocol.common.ServerboundCustomPayloadPacket(
                new net.minecraft.network.protocol.common.custom.DiscardedPayload(net.minecraft.resources.Identifier.withDefaultNamespace("other")));
            require(spoofer.rewriteBrand(otherPayload) == otherPayload, "Preserve other custom payloads");
            require(spoofer.suppress(register) && !spoofer.suppress(otherPayload), "Suppress channel advertisements only");
            channels.set(false);
            require(!spoofer.suppress(register), "Channel protection can be disabled");
            require(spoofer.protectsTranslations(), "Translation protection starts enabled");
            translations.set(false);
            require(!spoofer.protectsTranslations(), "Translation protection can be disabled");
            require(!spoofer.spoofPack(requiredPack), "Forced pack protection starts disabled");
            packs.set(true);
            require(spoofer.spoofPack(requiredPack) && !spoofer.spoofPack(optionalPack), "Only spoof required packs when protection is enabled");
            var responses = dev.technix.logic.client.module.impl.ClientSpoofer.packResponses(requiredPack);
            require(responses.stream().allMatch(response -> response.id().equals(requiredPack.id()))
                && responses.stream().map(net.minecraft.network.protocol.common.ServerboundResourcePackPacket::action).toList().equals(java.util.List.of(
                    net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.ACCEPTED,
                    net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.DOWNLOADED,
                    net.minecraft.network.protocol.common.ServerboundResourcePackPacket.Action.SUCCESSFULLY_LOADED)),
                "Report the requested pack as accepted, downloaded and loaded in order");
            var probe = net.minecraft.network.chat.Component.literal("prefix ")
                .append(net.minecraft.network.chat.Component.translatableWithFallback("mod.secret", "fallback"))
                .append(net.minecraft.network.chat.Component.keybind("key.mod.secret"));
            require(dev.technix.logic.client.module.impl.ClientSpoofer.safeSignText(probe).equals("prefix fallbackkey.mod.secret"),
                "Sign editing must not resolve translation keys or keybinds");
        } finally {
            spoofer.setEnabled(false);
        }
        require(spoofer.rewriteBrand(original) == original, "Disabling restores the original brand");
        require(!spoofer.spoofPack(requiredPack), "Disabled spoofer permits required packs");
        require(dev.technix.logic.host.RuntimeHost.rewriteOutgoing(original) == original,
            "Detached injector preserves the outgoing packet");
    }

    private static float smoothForOneSecond(float current, float target, double speed, int fps) {
        for (int i = 0; i < fps; i++) current = CombatMath.smoothAngle(current, target, speed, 1.0 / fps);
        return current;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void close(double actual, double expected, String message) {
        require(Math.abs(actual - expected) < 0.001, message + ": " + actual + " != " + expected);
    }
}

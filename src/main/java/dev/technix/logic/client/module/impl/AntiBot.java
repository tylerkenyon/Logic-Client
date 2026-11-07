package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.combat.BotChecks;
import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.ModeSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Player;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AntiBot extends Module {
    private final ModeSetting invalidUuid = registerSetting(new ModeSetting("Invalid UUID",
        "Check version-2 UUIDs.", "While stationary", "Off", "While stationary", "Always"));
    private final BooleanSetting invalidUsername = registerSetting(new BooleanSetting("Invalid username",
        "Reject names without letters, digits or underscores.", true));
    private final BooleanSetting invisible = registerSetting(new BooleanSetting("Always invisible",
        "Reject players always invisible without armour since appearing.", true));
    private final BooleanSetting stationary = registerSetting(new BooleanSetting("Always stationary",
        "Reject players until accumulated squared horizontal movement reaches one.", false));
    private final BooleanSetting sleeping = registerSetting(new BooleanSetting("Sleeping", "Ignore sleeping players.", false));
    private final NumberSetting minimumAge = registerSetting(new NumberSetting("Minimum age",
        "Ignore newly spawned players for this many ticks.", 0, 0, 200, 1));
    private record Tracked(Player player, BotChecks checks) { }
    private final Map<UUID, Tracked> history = new HashMap<>();
    private final Set<Player> bots = new HashSet<>();
    private ClientLevel level;

    public AntiBot() { super("AntiBot", "Exclude suspected server bots from combat targets.", Category.COMBAT); }

    public boolean isBot(Entity entity) {
        return enabled() && level == mc().level && bots.contains(entity);
    }

    @Subscribe(priority = 100)
    private void tick(ClientTickEvent event) {
        var mc = mc();
        if (level != mc.level) {
            clear();
            level = mc.level;
        }
        bots.clear();
        if (level == null || mc.player == null) { history.clear(); return; }
        var options = new BotChecks.Options(stationary.get(), invalidUuid.get(), sleeping.get(), invisible.get(), minimumAge.get(), invalidUsername.get());
        Set<UUID> present = new HashSet<>();
        for (Player player : level.players()) {
            if (player == mc.player || player.isRemoved()) continue;
            UUID id = player.getUUID();
            present.add(id);
            Tracked tracked = history.get(id);
            if (tracked == null || tracked.player() != player) {
                tracked = new Tracked(player, new BotChecks());
                history.put(id, tracked);
            }
            double dx = player.getX() - player.xo, dz = player.getZ() - player.zo;
            boolean unarmoured = player.getItemBySlot(EquipmentSlot.HEAD).isEmpty()
                && player.getItemBySlot(EquipmentSlot.CHEST).isEmpty()
                && player.getItemBySlot(EquipmentSlot.LEGS).isEmpty()
                && player.getItemBySlot(EquipmentSlot.FEET).isEmpty();
            var sample = new BotChecks.Sample(dx * dx + dz * dz, id.version(), player.isSleeping(),
                player.isInvisibleTo(mc.player) && unarmoured, player.tickCount, player.getGameProfile().name());
            if (tracked.checks().update(sample, options)) bots.add(player);
        }
        history.keySet().retainAll(present);
    }

    private void clear() { history.clear(); bots.clear(); level = null; }
    @Override protected void onDisable() { clear(); }
}

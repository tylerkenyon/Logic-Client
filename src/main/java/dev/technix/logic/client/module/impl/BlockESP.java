package dev.technix.logic.client.module.impl;

import dev.technix.logic.client.module.Category;
import dev.technix.logic.client.module.Module;
import dev.technix.logic.client.render.WorldBox;
import dev.technix.logic.client.render.WorldProjection;
import dev.technix.logic.client.setting.BooleanSetting;
import dev.technix.logic.client.setting.MultiSelectSetting;
import dev.technix.logic.client.setting.NumberSetting;
import dev.technix.logic.event.Subscribe;
import dev.technix.logic.event.impl.ClientTickEvent;
import dev.technix.logic.event.impl.Render2DEvent;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public final class BlockESP extends Module {
    private enum Group {
        DIAMOND("Diamond", "Blue", Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE),
        ANCIENT("Ancient debris", "Rose", Blocks.ANCIENT_DEBRIS),
        GOLD("Gold", "Amber", Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE, Blocks.NETHER_GOLD_ORE),
        IRON("Iron", "Silver", Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE),
        EMERALD("Emerald", "Green", Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE),
        REDSTONE("Redstone", "Rose", Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE),
        LAPIS("Lapis", "Blue", Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE),
        COAL("Coal", "Silver", Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE),
        COPPER("Copper", "Amber", Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE),
        STORAGE("Chests", "Amber", Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST);

        final String label, color;
        final List<Block> blocks;
        Group(String label, String color, Block... blocks) {
            this.label = label;
            this.color = color;
            this.blocks = List.of(blocks);
        }
    }

    private record Chunk(int x, int z) {}
    private record Match(BlockPos pos, Block block, String color) {}

    private final MultiSelectSetting blocks = registerSetting(new MultiSelectSetting("Blocks", "",
        List.of("Diamond", "Ancient debris"), java.util.Arrays.stream(Group.values()).map(group -> group.label).toArray(String[]::new)));
    private final NumberSetting range = registerSetting(new NumberSetting("Range", "Blocks", 32, 8, 64, 1));
    private final BooleanSetting fill = registerSetting(new BooleanSetting("Fill", "", false));
    private final Map<Chunk, List<Match>> cache = new HashMap<>();
    private final Map<Block, String> selected = new HashMap<>();
    private List<String> selection = List.of();
    private List<Chunk> sweep = List.of();
    private List<Match> visible = List.of();
    private ClientLevel level;
    private int cursor, centerX, centerZ, centerSection, scanRange;

    public BlockESP() { super("Block ESP", "Outline selected blocks through walls.", Category.RENDER); }

    @Subscribe
    private void scan(ClientTickEvent event) {
        var mc = mc();
        if (mc.level != level) {
            clear();
            level = mc.level;
        }
        if (level == null || mc.player == null) return;
        if (!selection.equals(blocks.get())) {
            selection = blocks.get();
            selected.clear();
            for (Group group : Group.values()) {
                if (selection.contains(group.label)) for (Block block : group.blocks) selected.put(block, group.color);
            }
            cache.clear();
            sweep = List.of();
        }
        if (selected.isEmpty()) { visible = List.of(); return; }

        BlockPos origin = mc.player.blockPosition();
        int cx = origin.getX() >> 4, cz = origin.getZ() >> 4, sy = origin.getY() >> 4;
        int radius = range.get().intValue();
        if (sweep.isEmpty() || cx != centerX || cz != centerZ || sy != centerSection || radius != scanRange) {
            centerX = cx; centerZ = cz; centerSection = sy; scanRange = radius;
            var chunks = new ArrayList<Chunk>();
            int chunkRadius = (radius + 15) / 16;
            for (int x = cx - chunkRadius; x <= cx + chunkRadius; x++) {
                for (int z = cz - chunkRadius; z <= cz + chunkRadius; z++) chunks.add(new Chunk(x, z));
            }
            chunks.sort(Comparator.comparingInt(c -> Math.abs(c.x - cx) + Math.abs(c.z - cz)));
            cache.keySet().retainAll(chunks);
            sweep = chunks;
            cursor = 0;
        }

        for (int i = 0; i < 2; i++) {
            Chunk chunk = sweep.get(cursor++ % sweep.size());
            scanChunk(chunk, radius);
            if (cursor == sweep.size()) cursor = 0;
        }
        visible = cache.values().stream().flatMap(List::stream)
            .filter(match -> match.pos.distSqr(origin) <= radius * radius)
            .sorted(Comparator.comparingDouble(match -> match.pos.distSqr(origin))).limit(96).toList();
    }

    private void scanChunk(Chunk key, int radius) {
        var chunk = level.getChunkSource().getChunk(key.x, key.z, ChunkStatus.FULL, false);
        if (chunk == null) { cache.remove(key); return; }
        var matches = new ArrayList<Match>();
        var sections = chunk.getSections();
        for (int i = 0; i < sections.length; i++) {
            int sectionY = chunk.getSectionYFromSectionIndex(i);
            var section = sections[i];
            if (Math.abs(sectionY - centerSection) > (radius + 15) / 16 || section.hasOnlyAir()
                    || !section.maybeHas(state -> selected.containsKey(state.getBlock()))) continue;
            for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) for (int x = 0; x < 16; x++) {
                Block block = section.getBlockState(x, y, z).getBlock();
                String color = selected.get(block);
                if (color != null) matches.add(new Match(new BlockPos((key.x << 4) + x, (sectionY << 4) + y, (key.z << 4) + z), block, color));
            }
        }
        if (matches.isEmpty()) cache.remove(key);
        else cache.put(key, matches);
    }

    @Subscribe
    private void render(Render2DEvent event) {
        var mc = mc();
        var frame = WorldProjection.current();
        if (level == null || level != mc.level || mc.player == null || mc.gui.screen() != null
                || frame == null || frame.level() != level) return;
        for (Match match : visible) {
            BlockPos p = match.pos;
            var chunk = level.getChunkSource().getChunk(p.getX() >> 4, p.getZ() >> 4, ChunkStatus.FULL, false);
            if (chunk == null || chunk.getBlockState(p).getBlock() != match.block) continue;
            WorldBox.draw(event.context(), frame, new Vec3(p.getX() + .5, p.getY(), p.getZ() + .5), 1, 1, 0, match.color, fill.get(), 1.5f);
        }
    }

    private void clear() {
        cache.clear();
        visible = List.of();
        sweep = List.of();
        level = null;
    }

    @Override protected void onDisable() { clear(); }
}

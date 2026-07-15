package dev.world.level.levelgen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class OreDepositStonePalette {
    public static final int MAX_BASES = 256;
    public static final TagKey<Block> STONES = TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", "stones"));
    private static volatile List<ResourceLocation> availableIdsCache;
    private static volatile List<ResourceLocation> clientIds = List.of();
    private static final AtomicInteger CLIENT_REVISION = new AtomicInteger();

    private OreDepositStonePalette() {
    }

    static List<ResourceLocation> availableIds() {
        List<ResourceLocation> cached = availableIdsCache;
        if (cached != null) {
            return cached;
        }

        List<Block> tagged = new ArrayList<>();
        for (var holder : BuiltInRegistries.BLOCK.getTagOrEmpty(STONES)) {
            tagged.add(holder.value());
        }
        List<Block> stones = tagged.stream()
                .sorted(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()))
                .limit(MAX_BASES)
                .toList();
        if (stones.isEmpty()) {
            stones = List.of(Blocks.STONE, Blocks.DEEPSLATE, Blocks.NETHERRACK, Blocks.END_STONE);
        }

        cached = stones.stream().map(OreDepositStonePalette::idOf).toList();
        availableIdsCache = cached;
        return cached;
    }

    static ResourceLocation idOf(Block block) {
        return BuiltInRegistries.BLOCK.getKey(block);
    }

    public static void clearAvailableCache() {
        availableIdsCache = null;
    }

    public static int indexOf(ServerLevel level, Block block) {
        return OreDepositPaletteData.get(level).baseIndex(block);
    }

    public static Block stoneAt(int index) {
        List<ResourceLocation> ids = clientIds.isEmpty() ? availableIds() : clientIds;
        if (index < 0 || index >= ids.size()) {
            return Blocks.STONE;
        }

        ResourceLocation id = ids.get(index);
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : Blocks.STONE;
    }

    public static Block stoneAt(ServerLevel level, int index) {
        List<ResourceLocation> ids = OreDepositPaletteData.get(level).bases();
        if (index < 0 || index >= ids.size()) {
            return Blocks.STONE;
        }

        ResourceLocation id = ids.get(index);
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : Blocks.STONE;
    }

    public static int nearestByMapColor(ServerLevel level, int color) {
        List<ResourceLocation> ids = OreDepositPaletteData.get(level).bases();
        if (ids.isEmpty()) {
            return 0;
        }

        int bestIndex = 0;
        int bestDistance = Integer.MAX_VALUE;
        for (int index = 0; index < ids.size(); index++) {
            ResourceLocation id = ids.get(index);
            if (!BuiltInRegistries.BLOCK.containsKey(id)) {
                continue;
            }
            int stoneColor = mapColor(BuiltInRegistries.BLOCK.get(id));
            int distance = colorDistance(color, stoneColor);
            if (distance < bestDistance) {
                bestDistance = distance;
                bestIndex = index;
            }
        }
        return bestIndex;
    }

    public static void applyClientPalette(List<ResourceLocation> ids) {
        clientIds = List.copyOf(ids.subList(0, Math.min(ids.size(), MAX_BASES)));
        CLIENT_REVISION.incrementAndGet();
    }

    public static int clientRevision() {
        return CLIENT_REVISION.get();
    }

    public static int mapColor(Block block) {
        try {
            return block.defaultBlockState().getMapColor(null, null).col;
        } catch (RuntimeException exception) {
            return Blocks.STONE.defaultBlockState().getMapColor(null, null).col;
        }
    }

    private static int colorDistance(int first, int second) {
        int red = ((first >> 16) & 0xFF) - ((second >> 16) & 0xFF);
        int green = ((first >> 8) & 0xFF) - ((second >> 8) & 0xFF);
        int blue = (first & 0xFF) - (second & 0xFF);
        return red * red + green * green + blue * blue;
    }
}

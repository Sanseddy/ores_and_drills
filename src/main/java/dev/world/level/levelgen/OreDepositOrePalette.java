package dev.world.level.levelgen;

import dev.FactoryExpansionMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public final class OreDepositOrePalette {
    public static final int MAX_ORES = 256;
    private static volatile List<ResourceLocation> availableIdsCache;
    private static volatile List<ResourceLocation> clientIds = List.of();
    private static volatile List<ResourceLocation> clientDropIds = List.of();
    private static final AtomicInteger CLIENT_REVISION = new AtomicInteger();

    private OreDepositOrePalette() {
    }

    public static List<ResourceLocation> availableIds() {
        List<ResourceLocation> cached = availableIdsCache;
        if (cached != null) {
            return cached;
        }

        cached = OreTags.oreBlocks().stream()
                .filter(block -> {
                    var id = BuiltInRegistries.BLOCK.getKey(block);
                    return id != null && !id.getNamespace().equals(FactoryExpansionMod.MOD_ID);
                })
                .sorted(Comparator.comparing(block -> BuiltInRegistries.BLOCK.getKey(block).toString()))
                .limit(MAX_ORES)
                .map(OreDepositOrePalette::idOf)
                .toList();
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
        return OreDepositPaletteData.get(level).oreIndex(block);
    }

    public static Block oreAt(int index) {
        List<ResourceLocation> ids = clientIds.isEmpty() ? availableIds() : clientIds;
        if (index < 0 || index >= ids.size()) {
            return null;
        }

        ResourceLocation id = ids.get(index);
        return BuiltInRegistries.BLOCK.containsKey(id) ? BuiltInRegistries.BLOCK.get(id) : null;
    }

    public static Item dropAt(int index) {
        List<ResourceLocation> ids = clientDropIds;
        if (index < 0 || index >= ids.size()) {
            return Items.AIR;
        }

        ResourceLocation id = ids.get(index);
        return BuiltInRegistries.ITEM.containsKey(id) ? BuiltInRegistries.ITEM.get(id) : Items.AIR;
    }

    public static void applyClientPalette(List<ResourceLocation> ids, List<ResourceLocation> dropIds) {
        clientIds = List.copyOf(ids.subList(0, Math.min(ids.size(), MAX_ORES)));
        clientDropIds = List.copyOf(dropIds.subList(0, Math.min(dropIds.size(), clientIds.size())));
        CLIENT_REVISION.incrementAndGet();
    }

    public static int clientRevision() {
        return CLIENT_REVISION.get();
    }
}

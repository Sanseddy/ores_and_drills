package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

import javax.annotation.Nullable;
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
                    return id != null && !id.getNamespace().equals(OresAndDrillsMod.MOD_ID);
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

    /** Returns the real server-resolved loot-table drop for this ore, when the world palette is available. */
    @Nullable
    public static ResourceLocation dropIdForOre(ResourceLocation oreId) {
        int index = clientIds.indexOf(oreId);
        return index >= 0 && index < clientDropIds.size() ? clientDropIds.get(index) : null;
    }

    /** Number of authoritative ore/drop slots currently synchronized by the logical server. */
    public static int clientSize() {
        return clientIds.size();
    }

    /** Returns the authoritative loot-table drop id for a synchronized palette slot. */
    @Nullable
    public static ResourceLocation dropIdAt(int index) {
        return index >= 0 && index < clientDropIds.size() ? clientDropIds.get(index) : null;
    }

    /**
     * Applies the authoritative server mapping. The return value tells the physical client whether its
     * pre-stitched ore texture slots must be recolored in place.
     */
    public static synchronized boolean applyClientPalette(
            List<ResourceLocation> ids,
            List<ResourceLocation> dropIds
    ) {
        List<ResourceLocation> newIds = List.copyOf(ids.subList(0, Math.min(ids.size(), MAX_ORES)));
        ResourceLocation airId = BuiltInRegistries.ITEM.getKey(Items.AIR);
        List<ResourceLocation> newDropIds = java.util.stream.IntStream.range(0, newIds.size())
                .mapToObj(index -> index < dropIds.size() ? dropIds.get(index) : airId)
                .toList();
        if (newIds.equals(clientIds) && newDropIds.equals(clientDropIds)) {
            return false;
        }
        clientIds = newIds;
        clientDropIds = newDropIds;
        CLIENT_REVISION.incrementAndGet();
        return true;
    }

    public static int clientRevision() {
        return CLIENT_REVISION.get();
    }
}

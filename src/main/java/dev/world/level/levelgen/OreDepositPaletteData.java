package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Set;

/** Keeps block-state palette indices stable for the lifetime of a world. */
public final class OreDepositPaletteData extends SavedData {
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_ore_deposit_palette";
    private static final String TAG_BASES = "Bases";
    private static final String TAG_ORES = "Ores";
    private static final SavedData.Factory<OreDepositPaletteData> FACTORY =
            new SavedData.Factory<>(OreDepositPaletteData::new, OreDepositPaletteData::load);

    private final List<ResourceLocation> bases = new ArrayList<>();
    private final List<ResourceLocation> ores = new ArrayList<>();
    private final Map<ResourceLocation, Integer> baseIndices = new HashMap<>();
    private final Map<ResourceLocation, Integer> oreIndices = new HashMap<>();
    private boolean initialized;

    private OreDepositPaletteData() {
    }

    public static OreDepositPaletteData get(ServerLevel level) {
        ServerLevel storageLevel = level.getServer().overworld();
        OreDepositPaletteData data = storageLevel.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
        data.ensureCurrent();
        return data;
    }

    public synchronized int baseIndex(Block block) {
        ensureCurrent();
        return baseIndices.getOrDefault(OreDepositStonePalette.idOf(block), -1);
    }

    public synchronized int oreIndex(Block block) {
        ensureCurrent();
        return oreIndices.getOrDefault(OreDepositOrePalette.idOf(block), -1);
    }

    public synchronized List<ResourceLocation> bases() {
        ensureCurrent();
        return List.copyOf(bases);
    }

    public synchronized List<ResourceLocation> ores() {
        ensureCurrent();
        return List.copyOf(ores);
    }

    public synchronized void refreshFromRegistries() {
        boolean changed = appendMissing(bases, OreDepositStonePalette.availableIds(), OreDepositStonePalette.MAX_BASES, "base");
        changed |= appendMissing(ores, OreDepositOrePalette.availableIds(), OreDepositOrePalette.MAX_ORES, "ore");
        initialized = true;
        if (changed) {
            rebuildIndices();
            setDirty();
            OresAndDrillsMod.LOGGER.trace(
                    "Ore deposit palette: {} fixed bases and {} fixed ores",
                    bases.size(), ores.size()
            );
        } else if (baseIndices.isEmpty() && !bases.isEmpty() || oreIndices.isEmpty() && !ores.isEmpty()) {
            rebuildIndices();
        }
    }

    private synchronized void ensureCurrent() {
        if (!initialized) {
            refreshFromRegistries();
        }
    }

    private static boolean appendMissing(
            List<ResourceLocation> palette,
            List<ResourceLocation> available,
            int maximum,
            String kind
    ) {
        boolean changed = false;
        Set<ResourceLocation> present = new HashSet<>(palette);
        for (ResourceLocation id : available) {
            if (present.contains(id)) {
                continue;
            }
            if (palette.size() >= maximum) {
                OresAndDrillsMod.LOGGER.warn(
                        "Ore deposit palette: cannot append {} {} because the {}-entry limit is full",
                        kind, id, maximum
                );
                continue;
            }

            palette.add(id);
            present.add(id);
            changed = true;
        }
        return changed;
    }

    private static OreDepositPaletteData load(CompoundTag tag, HolderLookup.Provider registries) {
        OreDepositPaletteData data = new OreDepositPaletteData();
        readIds(tag.getList(TAG_BASES, Tag.TAG_STRING), data.bases, OreDepositStonePalette.MAX_BASES);
        readIds(tag.getList(TAG_ORES, Tag.TAG_STRING), data.ores, OreDepositOrePalette.MAX_ORES);
        data.rebuildIndices();
        return data;
    }

    private static void readIds(ListTag list, List<ResourceLocation> target, int maximum) {
        for (int index = 0; index < list.size() && target.size() < maximum; index++) {
            ResourceLocation id = ResourceLocation.tryParse(list.getString(index));
            if (id != null && !target.contains(id)) {
                target.add(id);
            }
        }
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.put(TAG_BASES, writeIds(bases));
        tag.put(TAG_ORES, writeIds(ores));
        return tag;
    }

    private static ListTag writeIds(List<ResourceLocation> ids) {
        ListTag list = new ListTag();
        for (ResourceLocation id : ids) {
            list.add(StringTag.valueOf(id.toString()));
        }
        return list;
    }

    private void rebuildIndices() {
        baseIndices.clear();
        oreIndices.clear();
        indexInto(bases, baseIndices);
        indexInto(ores, oreIndices);
    }

    private static void indexInto(List<ResourceLocation> ids, Map<ResourceLocation, Integer> target) {
        for (int index = 0; index < ids.size(); index++) {
            target.put(ids.get(index), index);
        }
    }
}

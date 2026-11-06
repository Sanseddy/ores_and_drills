package dev.world.geology;

import dev.OresAndDrillsMod;
import dev.world.level.storage.FastSavedData;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Per-dimension field equipment: every placed geophone and every finished borehole. Stations query
 * it instead of scanning the world, so a geophone in an unloaded chunk still takes part in a survey
 * without that chunk being loaded. Records are added and removed by the blocks themselves.
 */
public final class GeologySavedData extends FastSavedData {
    public static final int FORMAT = 1;
    public static final int MAXIMUM_GEOPHONES = 4096;
    public static final int MAXIMUM_BOREHOLES = 2048;
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_geology";
    private static final SavedData.Factory<GeologySavedData> FACTORY =
            new SavedData.Factory<>(GeologySavedData::new, GeologySavedData::load);

    // Sorted, so every survey sees the geophones in the same order regardless of placement order.
    private final TreeSet<Long> geophones = new TreeSet<>();
    private final LinkedHashMap<Long, Borehole> boreholes = new LinkedHashMap<>();

    public static GeologySavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public synchronized boolean addGeophone(BlockPos pos) {
        if (geophones.size() >= MAXIMUM_GEOPHONES && !geophones.contains(pos.asLong())) {
            return false;
        }
        if (geophones.add(pos.asLong())) {
            setDirty();
        }
        return true;
    }

    public synchronized void removeGeophone(BlockPos pos) {
        if (geophones.remove(pos.asLong())) {
            setDirty();
        }
    }

    /** Registered geophones within {@code radius} blocks (horizontally) of {@code center}. */
    public synchronized List<BlockPos> geophonesWithin(BlockPos center, double radius) {
        List<BlockPos> result = new ArrayList<>();
        double radiusSquared = radius * radius;
        for (long packed : geophones) {
            BlockPos pos = BlockPos.of(packed);
            double dx = pos.getX() - center.getX();
            double dz = pos.getZ() - center.getZ();
            if (dx * dx + dz * dz <= radiusSquared) {
                result.add(pos);
            }
        }
        return result;
    }

    public synchronized int geophoneCount() {
        return geophones.size();
    }

    public synchronized void addBorehole(Borehole borehole) {
        long key = borehole.top().asLong();
        boreholes.remove(key);
        boreholes.put(key, borehole);
        while (boreholes.size() > MAXIMUM_BOREHOLES) {
            Long eldest = boreholes.keySet().iterator().next();
            boreholes.remove(eldest);
        }
        setDirty();
    }

    public synchronized List<Borehole> boreholesWithin(BlockPos center, double radius) {
        List<Borehole> result = new ArrayList<>();
        double radiusSquared = radius * radius;
        for (Borehole borehole : boreholes.values()) {
            double dx = borehole.top().getX() - center.getX();
            double dz = borehole.top().getZ() - center.getZ();
            if (dx * dx + dz * dz <= radiusSquared) {
                result.add(borehole);
            }
        }
        return result;
    }

    static GeologySavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.isEmpty() && tag.getInt("Format") != FORMAT) {
            throw new IllegalArgumentException(
                    "Unsupported geology data format; a new world is required");
        }
        GeologySavedData data = new GeologySavedData();
        for (long packed : tag.getLongArray("Geophones")) {
            data.geophones.add(packed);
        }
        ListTag holes = tag.getList("Boreholes", Tag.TAG_COMPOUND);
        for (int i = 0; i < holes.size(); i++) {
            Borehole borehole = Borehole.load(holes.getCompound(i));
            data.boreholes.put(borehole.top().asLong(), borehole);
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", FORMAT);
        long[] packed = new long[geophones.size()];
        int i = 0;
        for (long value : geophones) {
            packed[i++] = value;
        }
        tag.putLongArray("Geophones", packed);
        ListTag holes = new ListTag();
        for (Map.Entry<Long, Borehole> entry : boreholes.entrySet()) {
            holes.add(entry.getValue().save());
        }
        tag.put("Boreholes", holes);
        return tag;
    }
}

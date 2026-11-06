package dev.world.geology.lidar;

import dev.OresAndDrillsMod;
import dev.config.SeismicSurveyConfig;
import dev.world.level.storage.FastSavedData;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * A station's accumulated maps, one per dimension, in its own file next to its survey archive. The
 * file lives in the world's save, so different worlds never share maps; the station id travels with
 * the station item, so a moved station keeps refining the same maps.
 */
public final class StationLidarData extends FastSavedData {
    public static final int FORMAT = 1;
    private static final String FILE_PREFIX = OresAndDrillsMod.MOD_ID + "_lidar_";

    private final Map<ResourceLocation, LidarMap> maps = new HashMap<>();

    public static StationLidarData get(MinecraftServer server, UUID station) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(
                        new SavedData.Factory<>(StationLidarData::new, StationLidarData::load),
                        FILE_PREFIX + station.toString().replace("-", "").toLowerCase(Locale.ROOT));
    }

    public synchronized LidarMap map(ResourceLocation dimension) {
        return maps.computeIfAbsent(dimension, ignored -> track(newMap()));
    }

    private LidarMap track(LidarMap map) {
        map.onChange(this::setDirty);
        return map;
    }

    static LidarMap newMap() {
        return new LidarMap(maxSamples(), maxObjects());
    }

    private static int maxSamples() {
        try {
            return SeismicSurveyConfig.LIDAR_MAX_SAMPLES.get();
        } catch (IllegalStateException notLoaded) {
            return 250_000;
        }
    }

    private static int maxObjects() {
        try {
            return SeismicSurveyConfig.LIDAR_MAX_OBJECTS.get();
        } catch (IllegalStateException notLoaded) {
            return 1_024;
        }
    }

    static StationLidarData load(CompoundTag tag, HolderLookup.Provider registries) {
        StationLidarData data = new StationLidarData();
        if (tag.isEmpty()) {
            return data;
        }
        if (tag.getInt("Format") != FORMAT) {
            throw new IllegalArgumentException("Unsupported station LiDAR data format");
        }
        ListTag list = tag.getList("Maps", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            ResourceLocation dimension = ResourceLocation.tryParse(entry.getString("Dimension"));
            if (dimension == null) {
                continue;
            }
            try {
                data.maps.put(
                        dimension,
                        data.track(
                                LidarMapStorage.load(
                                        entry.getCompound("Map"), maxSamples(), maxObjects())));
            } catch (RuntimeException invalid) {
                OresAndDrillsMod.LOGGER.warn(
                        "Dropping unreadable LiDAR map of {}: {}", dimension, invalid.getMessage());
            }
        }
        return data;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", FORMAT);
        ListTag list = new ListTag();
        for (Map.Entry<ResourceLocation, LidarMap> entry : maps.entrySet()) {
            CompoundTag map = new CompoundTag();
            map.putString("Dimension", entry.getKey().toString());
            map.put("Map", LidarMapStorage.save(entry.getValue()));
            list.add(map);
        }
        tag.put("Maps", list);
        return tag;
    }
}

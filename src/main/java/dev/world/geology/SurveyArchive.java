package dev.world.geology;

import dev.OresAndDrillsMod;
import dev.world.level.storage.FastSavedData;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Finished surveys of one station. Each station has its own file in the overworld's data folder: a
 * survey map is tens of kilobytes, and a single shared file would rewrite every station's history
 * whenever any station finished. The station's identity travels with its item, so a moved station
 * keeps its history and can stack surveys made from several positions.
 */
public final class SurveyArchive extends FastSavedData {
    public static final int FORMAT = 1;
    private static final String FILE_PREFIX = OresAndDrillsMod.MOD_ID + "_seismic_";

    private final List<SurveyRecord> records = new ArrayList<>();
    private int nextId = 1;

    public static SurveyArchive get(MinecraftServer server, UUID station) {
        return server.overworld()
                .getDataStorage()
                .computeIfAbsent(
                        new SavedData.Factory<>(SurveyArchive::new, SurveyArchive::load),
                        fileName(station));
    }

    static String fileName(UUID station) {
        return FILE_PREFIX + station.toString().replace("-", "").toLowerCase(Locale.ROOT);
    }

    /** Adds a survey and drops the oldest ones beyond {@code capacity}. */
    public synchronized SurveyRecord add(SurveyRecord record, int capacity) {
        SurveyRecord numbered =
                new SurveyRecord(
                        nextId++,
                        record.dimension(),
                        record.gameTime(),
                        record.configHash(),
                        record.mode(),
                        record.centerX(),
                        record.datumY(),
                        record.centerZ(),
                        record.radius(),
                        record.minY(),
                        record.setting(),
                        record.environment(),
                        record.wellTie(),
                        record.noiseSeed(),
                        record.geophoneX(),
                        record.geophoneZ(),
                        record.level(),
                        record.map(),
                        record.targets());
        records.add(numbered);
        while (records.size() > Math.max(1, capacity)) {
            records.removeFirst();
        }
        setDirty();
        return numbered;
    }

    /** Surveys oldest first. */
    public synchronized List<SurveyRecord> records() {
        return List.copyOf(records);
    }

    public synchronized SurveyRecord byId(int id) {
        for (SurveyRecord record : records) {
            if (record.id() == id) {
                return record;
            }
        }
        return null;
    }

    static SurveyArchive load(CompoundTag tag, HolderLookup.Provider registries) {
        if (!tag.isEmpty() && tag.getInt("Format") != FORMAT) {
            throw new IllegalArgumentException("Unsupported survey archive format");
        }
        SurveyArchive archive = new SurveyArchive();
        archive.nextId = Math.max(1, tag.getInt("NextId"));
        ListTag list = tag.getList("Surveys", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            try {
                SurveyRecord record = SurveyRecord.load(list.getCompound(i));
                archive.records.add(record);
                archive.nextId = Math.max(archive.nextId, record.id() + 1);
            } catch (RuntimeException invalid) {
                OresAndDrillsMod.LOGGER.warn(
                        "Dropping unreadable survey: {}", invalid.getMessage());
            }
        }
        return archive;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        tag.putInt("Format", FORMAT);
        tag.putInt("NextId", nextId);
        ListTag list = new ListTag();
        for (SurveyRecord record : records) {
            list.add(record.save());
        }
        tag.put("Surveys", list);
        return tag;
    }
}

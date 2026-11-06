package dev.world.geology.lidar;

import dev.world.geology.SurveyTarget;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

/**
 * Disk format of a {@link LidarMap}: every field at full precision, including the server-side
 * averaging state, so a reloaded map keeps refining exactly as before. Writes {@code Format=1} and
 * rejects other versions.
 */
public final class LidarMapStorage {
    private LidarMapStorage() {}

    /** A fresh tag of the whole map; the map is locked while it is built. */
    public static CompoundTag save(LidarMap map) {
        synchronized (map) {
            return saveLocked(map);
        }
    }

    private static CompoundTag saveLocked(LidarMap map) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", LidarMap.FORMAT);
        tag.putLong("Epoch", map.epoch);
        tag.putInt("Revision", map.revision);
        tag.putInt("ObjectRevision", map.objectRevision);
        tag.putInt("NextObject", map.nextObjectId);
        ListTag bucketList = new ListTag();
        for (Map.Entry<Long, LidarMap.Bucket> entry : map.buckets.entrySet()) {
            LidarMap.Bucket bucket = entry.getValue();
            CompoundTag bucketTag = new CompoundTag();
            bucketTag.putLong("Key", entry.getKey());
            bucketTag.putInt("Revision", bucket.revision);
            bucketTag.putInt("CaveLevel", bucket.caveLevel);
            bucketTag.putLong("CaveTime", bucket.caveTime);
            bucketTag.put("Entries", saveEntries(bucket.entries));
            bucketList.add(bucketTag);
        }
        tag.put("Buckets", bucketList);
        ListTag objectList = new ListTag();
        for (LidarMap.StoredObject stored : map.objects.values()) {
            CompoundTag objectTag = saveObject(stored.object());
            objectTag.putLong("Config", stored.config());
            objectList.add(objectTag);
        }
        tag.put("Objects", objectList);
        ListTag coverageList = new ListTag();
        for (LidarCoverage c : map.coverage) {
            coverageList.add(saveCoverage(c));
        }
        tag.put("Coverage", coverageList);
        return tag;
    }

    public static LidarMap load(CompoundTag tag, int maxSamples, int maxObjects) {
        LidarMap map = new LidarMap(maxSamples, maxObjects);
        if (tag.isEmpty()) {
            return map;
        }
        if (tag.getInt("Format") != LidarMap.FORMAT) {
            throw new IllegalArgumentException("Unsupported LiDAR map format");
        }
        map.epoch = tag.getLong("Epoch") == 0L ? LidarMap.freshEpoch() : tag.getLong("Epoch");
        map.revision = tag.getInt("Revision");
        map.objectRevision = tag.getInt("ObjectRevision");
        map.nextObjectId = Math.max(1, tag.getInt("NextObject"));
        ListTag bucketList = tag.getList("Buckets", Tag.TAG_COMPOUND);
        for (int i = 0; i < bucketList.size(); i++) {
            CompoundTag bucketTag = bucketList.getCompound(i);
            LidarMap.Bucket bucket = new LidarMap.Bucket();
            bucket.revision = bucketTag.getInt("Revision");
            bucket.caveLevel = bucketTag.getInt("CaveLevel");
            bucket.caveTime = bucketTag.getLong("CaveTime");
            for (StoredEntry stored : loadEntries(bucketTag.getCompound("Entries"))) {
                bucket.entries.add(
                        new LidarMap.Entry(
                                stored.key(),
                                stored.sample(),
                                stored.value(),
                                stored.weight(),
                                stored.cell(),
                                stored.config(),
                                stored.time()));
            }
            long key = bucketTag.getLong("Key");
            map.buckets.put(key, bucket);
            map.byRevision.put(bucket.revision, key);
            map.sampleCount += bucket.entries.size();
            map.revision = Math.max(map.revision, bucket.revision);
        }
        ListTag objectList = tag.getList("Objects", Tag.TAG_COMPOUND);
        for (int i = 0; i < objectList.size(); i++) {
            CompoundTag objectTag = objectList.getCompound(i);
            LidarObject object = loadObject(objectTag);
            map.objects.put(
                    object.id(), new LidarMap.StoredObject(object, objectTag.getLong("Config")));
            map.nextObjectId = Math.max(map.nextObjectId, object.id() + 1);
        }
        ListTag coverageList = tag.getList("Coverage", Tag.TAG_COMPOUND);
        for (int i = 0; i < coverageList.size(); i++) {
            map.coverage.add(loadCoverage(coverageList.getCompound(i)));
        }
        return map;
    }

    record StoredEntry(
            long key,
            LidarSample sample,
            float value,
            float weight,
            float cell,
            long config,
            long time) {}

    // ------------------------------------------------------------------------------------------
    // NBT

    static CompoundTag saveEntries(List<LidarMap.Entry> entries) {
        int n = entries.size();
        byte[] kinds = new byte[n];
        int[] floats = new int[n * 9];
        int[] ints = new int[n * 3];
        long[] longs = new long[n * 3];
        for (int i = 0; i < n; i++) {
            LidarMap.Entry entry = entries.get(i);
            LidarSample s = entry.sample();
            kinds[i] = (byte) s.kind().ordinal();
            int f = i * 9;
            floats[f] = Float.floatToIntBits(s.x());
            floats[f + 1] = Float.floatToIntBits(s.y());
            floats[f + 2] = Float.floatToIntBits(s.z());
            floats[f + 3] = Float.floatToIntBits(s.spreadH());
            floats[f + 4] = Float.floatToIntBits(s.spreadV());
            floats[f + 5] = Float.floatToIntBits(s.confidence());
            floats[f + 6] = Float.floatToIntBits(entry.value);
            floats[f + 7] = Float.floatToIntBits(entry.weight);
            floats[f + 8] = Float.floatToIntBits(entry.cell);
            ints[i * 3] = s.color();
            ints[i * 3 + 1] = s.object();
            ints[i * 3 + 2] = s.flags();
            longs[i * 3] = entry.key();
            longs[i * 3 + 1] = entry.config;
            longs[i * 3 + 2] = entry.time;
        }
        CompoundTag tag = new CompoundTag();
        tag.putByteArray("Kind", kinds);
        tag.putIntArray("Floats", floats);
        tag.putIntArray("Ints", ints);
        tag.putLongArray("Longs", longs);
        return tag;
    }

    static List<StoredEntry> loadEntries(CompoundTag tag) {
        byte[] kinds = tag.getByteArray("Kind");
        int n = kinds.length;
        int[] floats = tag.getIntArray("Floats");
        int[] ints = tag.getIntArray("Ints");
        long[] longs = tag.getLongArray("Longs");
        if (floats.length != n * 9 || ints.length != n * 3 || longs.length != n * 3) {
            throw new IllegalArgumentException("Invalid LiDAR bucket");
        }
        List<StoredEntry> result = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            int f = i * 9;
            LidarSample sample =
                    new LidarSample(
                            LidarSample.Kind.byId(kinds[i]),
                            Float.intBitsToFloat(floats[f]),
                            Float.intBitsToFloat(floats[f + 1]),
                            Float.intBitsToFloat(floats[f + 2]),
                            Float.intBitsToFloat(floats[f + 3]),
                            Float.intBitsToFloat(floats[f + 4]),
                            Float.intBitsToFloat(floats[f + 5]),
                            ints[i * 3],
                            ints[i * 3 + 1],
                            ints[i * 3 + 2]);
            result.add(
                    new StoredEntry(
                            longs[i * 3],
                            sample,
                            Float.intBitsToFloat(floats[f + 6]),
                            Float.intBitsToFloat(floats[f + 7]),
                            Float.intBitsToFloat(floats[f + 8]),
                            longs[i * 3 + 1],
                            longs[i * 3 + 2]));
        }
        return result;
    }

    static CompoundTag saveObject(LidarObject o) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Id", o.id());
        tag.putInt("Kind", o.kind().ordinal());
        tag.putIntArray(
                "Floats",
                new int[] {
                    Float.floatToIntBits(o.x()),
                    Float.floatToIntBits(o.y()),
                    Float.floatToIntBits(o.z()),
                    Float.floatToIntBits(o.sizeX()),
                    Float.floatToIntBits(o.sizeY()),
                    Float.floatToIntBits(o.sizeZ()),
                    Float.floatToIntBits(o.angle()),
                    Float.floatToIntBits(o.positionError()),
                    Float.floatToIntBits(o.depthError()),
                    Float.floatToIntBits(o.intensity()),
                    Float.floatToIntBits(o.confidence()),
                    Float.floatToIntBits(o.volume())
                });
        tag.putIntArray(
                "Ints",
                new int[] {
                    o.detail(),
                    o.level(),
                    o.shape(),
                    o.fill(),
                    o.links(),
                    o.suspectedLinks(),
                    o.surveys()
                });
        tag.putLong("Time", o.time());
        ListTag list = new ListTag();
        for (SurveyTarget.Candidate candidate : o.candidates()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Source", candidate.source().ordinal());
            entry.putInt(
                    "Category", candidate.category() == null ? -1 : candidate.category().ordinal());
            entry.putString("Ore", candidate.oreId());
            entry.putDouble("P", candidate.probability());
            list.add(entry);
        }
        tag.put("Candidates", list);
        return tag;
    }

    static LidarObject loadObject(CompoundTag tag) {
        int[] f = tag.getIntArray("Floats");
        int[] i = tag.getIntArray("Ints");
        if (f.length != 12 || i.length != 7) {
            throw new IllegalArgumentException("Invalid LiDAR object");
        }
        ListTag list = tag.getList("Candidates", Tag.TAG_COMPOUND);
        List<SurveyTarget.Candidate> candidates = new ArrayList<>();
        for (int c = 0; c < list.size(); c++) {
            CompoundTag entry = list.getCompound(c);
            candidates.add(
                    LidarCodec.candidate(
                            entry.getInt("Source"),
                            entry.getInt("Category"),
                            entry.getString("Ore"),
                            entry.getDouble("P")));
        }
        return new LidarObject(
                tag.getInt("Id"),
                LidarObject.Kind.byId(tag.getInt("Kind")),
                Float.intBitsToFloat(f[0]),
                Float.intBitsToFloat(f[1]),
                Float.intBitsToFloat(f[2]),
                Float.intBitsToFloat(f[3]),
                Float.intBitsToFloat(f[4]),
                Float.intBitsToFloat(f[5]),
                Float.intBitsToFloat(f[6]),
                Float.intBitsToFloat(f[7]),
                Float.intBitsToFloat(f[8]),
                Float.intBitsToFloat(f[9]),
                Float.intBitsToFloat(f[10]),
                i[0],
                i[1],
                i[2],
                Float.intBitsToFloat(f[11]),
                i[3],
                i[4],
                i[5],
                i[6],
                tag.getLong("Time"),
                candidates);
    }

    static CompoundTag saveCoverage(LidarCoverage c) {
        CompoundTag tag = new CompoundTag();
        tag.putFloat("X", c.x());
        tag.putFloat("Z", c.z());
        tag.putFloat("Radius", c.radius());
        tag.putFloat("Top", c.topY());
        tag.putFloat("Bottom", c.bottomY());
        tag.putBoolean("Cave", c.cave());
        tag.putInt("Level", c.level());
        tag.putLong("Time", c.time());
        return tag;
    }

    static LidarCoverage loadCoverage(CompoundTag tag) {
        return new LidarCoverage(
                tag.getFloat("X"),
                tag.getFloat("Z"),
                tag.getFloat("Radius"),
                tag.getFloat("Top"),
                tag.getFloat("Bottom"),
                tag.getBoolean("Cave"),
                tag.getInt("Level"),
                tag.getLong("Time"));
    }
}

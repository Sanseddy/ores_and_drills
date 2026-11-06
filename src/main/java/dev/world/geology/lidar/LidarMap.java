package dev.world.geology.lidar;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * The accumulated LiDAR-style map of one piece of equipment in one dimension: samples in cubic
 * buckets of {@link #BUCKET} blocks, interpreted objects and the volumes surveys covered.
 *
 * <p>Every change gives the touched bucket a new, higher revision, so a client that has seen
 * everything up to revision R needs only the buckets above R; removed buckets stay as empty
 * tombstones until the epoch is renewed. Repeated surveys refine samples in place instead of adding
 * points: a seismic reading is averaged with earlier ones of the same cell (inverse-variance with
 * age decay, never with an identical configuration, whose errors are the same), and a cell that a
 * new survey covers without seeing anything there is weakened as a measurement of zero. Cave walls
 * are re-measured, not averaged: a newer map of a bucket from an analyser at least as good, or one
 * replacing outdated data, replaces it, which is how mined-out and newly opened voids show up.
 */
public final class LidarMap {
    public static final int FORMAT = 1;
    public static final int BUCKET = 32;

    /** Intensity below which an averaged seismic cell is dropped. */
    public static final double KEEP_INTENSITY = 2.0D;

    long epoch;
    int revision;
    int objectRevision;
    int nextObjectId = 1;
    int sampleCount;
    private final int maxSamples;
    private final int maxObjects;
    final Map<Long, Bucket> buckets = new HashMap<>();
    final TreeMap<Integer, Long> byRevision = new TreeMap<>();
    final Map<Integer, StoredObject> objects = new LinkedHashMap<>();
    final List<LidarCoverage> coverage = new ArrayList<>();
    Runnable onChange = () -> {};

    public LidarMap(int maxSamples, int maxObjects) {
        this.maxSamples = Math.max(1, maxSamples);
        this.maxObjects = Math.max(1, maxObjects);
        this.epoch = freshEpoch();
    }

    /** A sample with the server-side state that averaging needs; never sent to clients. */
    public static final class Entry {
        final long key;
        LidarSample sample;
        float value;
        float weight;
        float cell;
        long config;
        long time;

        Entry(
                long key,
                LidarSample sample,
                float value,
                float weight,
                float cell,
                long config,
                long time) {
            this.key = key;
            this.sample = sample;
            this.value = value;
            this.weight = weight;
            this.cell = cell;
            this.config = config;
            this.time = time;
        }

        public LidarSample sample() {
            return sample;
        }

        public long key() {
            return key;
        }
    }

    static final class Bucket {
        final List<Entry> entries = new ArrayList<>();
        int revision;
        int caveLevel = -1;
        long caveTime;
    }

    record StoredObject(LidarObject object, long config) {}

    /** A seismic sample keyed by its canonical cell, with its intensity for averaging. */
    public record Keyed(long key, LidarSample sample, float intensity, float cell) {}

    /** Buckets above a revision, oldest change first, for sending. */
    public record BucketView(long key, int revision, List<LidarSample> samples) {}

    public void onChange(Runnable listener) {
        this.onChange = listener;
    }

    public synchronized long epoch() {
        return epoch;
    }

    public synchronized int revision() {
        return revision;
    }

    public synchronized int objectRevision() {
        return objectRevision;
    }

    public synchronized int sampleCount() {
        return sampleCount;
    }

    public static long bucketKey(double x, double y, double z) {
        return pack(
                Math.floorDiv((int) Math.floor(x), BUCKET),
                Math.floorDiv((int) Math.floor(y), BUCKET),
                Math.floorDiv((int) Math.floor(z), BUCKET));
    }

    public static long pack(int bx, int by, int bz) {
        return ((long) (bx & 0x1FFFFF) << 42) | ((long) (by & 0x1FFFFF) << 21) | (bz & 0x1FFFFF);
    }

    public static int bucketX(long key) {
        return (int) (key << 1 >> 43);
    }

    public static int bucketY(long key) {
        return (int) (key << 22 >> 43);
    }

    public static int bucketZ(long key) {
        return (int) (key << 43 >> 43);
    }

    /** Horizontal distance from (x, z) to the centre of a bucket. */
    public static double horizontalDistance(long key, double x, double z) {
        double cx = (bucketX(key) + 0.5D) * BUCKET;
        double cz = (bucketZ(key) + 0.5D) * BUCKET;
        return Math.hypot(cx - x, cz - z);
    }

    /** Keys of the stored buckets, including tombstones, that satisfy {@code filter}. */
    public synchronized List<Long> bucketKeys(Predicate<Long> filter) {
        List<Long> result = new ArrayList<>();
        for (Long key : buckets.keySet()) {
            if (filter.test(key)) {
                result.add(key);
            }
        }
        return result;
    }

    /**
     * Folds a survey's seismic samples of one bucket into the map.
     *
     * @param fresh the survey's samples in this bucket
     * @param covered whether a point lies inside the survey's volume
     * @param cell horizontal cell size of the survey
     * @param staleTicks age at which an earlier reading counts half
     */
    public synchronized void mergeAnomalies(
            long bucketKey,
            List<Keyed> fresh,
            Predicate<LidarSample> covered,
            float cell,
            long config,
            long time,
            long staleTicks) {
        Bucket bucket = buckets.get(bucketKey);
        if (bucket == null) {
            if (fresh.isEmpty()) {
                return;
            }
            bucket = new Bucket();
            buckets.put(bucketKey, bucket);
        }
        Map<Long, Entry> existing = new HashMap<>();
        for (Entry entry : bucket.entries) {
            if (entry.sample.kind() == LidarSample.Kind.ANOMALY) {
                existing.put(entry.key, entry);
            }
        }
        java.util.Set<Long> seen = new java.util.HashSet<>();
        for (Keyed keyed : fresh) {
            seen.add(keyed.key());
            Entry entry = existing.get(keyed.key());
            if (entry == null) {
                Entry added =
                        new Entry(
                                keyed.key(),
                                keyed.sample(),
                                keyed.intensity(),
                                1.0F,
                                keyed.cell(),
                                config,
                                time);
                bucket.entries.add(added);
                existing.put(keyed.key(), added);
                sampleCount++;
                continue;
            }
            if (entry.config == config) {
                entry.value = keyed.intensity();
                entry.weight = 1.0F;
            } else {
                double previous = entry.weight * decay(time - entry.time, staleTicks);
                entry.value =
                        (float) ((entry.value * previous + keyed.intensity()) / (previous + 1.0D));
                entry.weight = (float) (previous + 1.0D);
            }
            entry.config = config;
            entry.time = time;
            entry.cell = keyed.cell();
            entry.sample = keyed.sample().withConfidence((float) confidence(entry.value));
        }
        Iterator<Entry> iterator = bucket.entries.iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next();
            if (entry.sample.kind() != LidarSample.Kind.ANOMALY
                    || seen.contains(entry.key)
                    || entry.cell < cell * 0.5F
                    || !covered.test(entry.sample)) {
                continue;
            }
            // Covered and not seen: this survey measured nothing above its noise here.
            if (entry.config == config) {
                entry.value = 0.0F;
            } else {
                double previous = entry.weight * decay(time - entry.time, staleTicks);
                entry.value = (float) (entry.value * previous / (previous + 1.0D));
                entry.weight = (float) (previous + 1.0D);
                entry.config = config;
                entry.time = time;
            }
            if (entry.value < KEEP_INTENSITY) {
                iterator.remove();
                sampleCount--;
            } else {
                entry.sample = entry.sample.withConfidence((float) confidence(entry.value));
            }
        }
        touch(bucketKey, bucket);
    }

    /**
     * Replaces the cave walls of a bucket with a new analyser map, unless the stored one came from
     * a better analyser and is still current. Returns whether the new map was taken.
     */
    public synchronized boolean replaceCaves(
            long bucketKey, List<LidarSample> fresh, int level, long time, long staleTicks) {
        Bucket bucket = buckets.get(bucketKey);
        if (bucket != null && bucket.caveLevel > level && time - bucket.caveTime < staleTicks) {
            return false;
        }
        if (bucket == null) {
            if (fresh.isEmpty()) {
                return true;
            }
            bucket = new Bucket();
            buckets.put(bucketKey, bucket);
        }
        Iterator<Entry> iterator = bucket.entries.iterator();
        while (iterator.hasNext()) {
            if (iterator.next().sample.kind().layer() == LidarSample.Layer.CAVE) {
                iterator.remove();
                sampleCount--;
            }
        }
        for (LidarSample sample : fresh) {
            bucket.entries.add(new Entry(0L, sample, 0.0F, 1.0F, 0.0F, 0L, time));
            sampleCount++;
        }
        bucket.caveLevel = level;
        bucket.caveTime = time;
        touch(bucketKey, bucket);
        return true;
    }

    /** Appends samples as they are, for equipment that logs its own column. */
    public synchronized void add(List<LidarSample> samples, long time) {
        Map<Long, Bucket> touched = new HashMap<>();
        for (LidarSample sample : samples) {
            long key = bucketKey(sample.x(), sample.y(), sample.z());
            Bucket bucket = buckets.computeIfAbsent(key, ignored -> new Bucket());
            bucket.entries.add(new Entry(0L, sample, 0.0F, 1.0F, 0.0F, 0L, time));
            sampleCount++;
            touched.put(key, bucket);
        }
        touched.forEach(this::touch);
    }

    /** Forgets everything; clients resynchronise from an empty map. */
    public synchronized void clear() {
        buckets.clear();
        byRevision.clear();
        objects.clear();
        coverage.clear();
        sampleCount = 0;
        epoch = freshEpoch();
        revision = 0;
        objectRevision = 0;
        onChange.run();
    }

    private void touch(long key, Bucket bucket) {
        byRevision.remove(bucket.revision);
        bucket.revision = ++revision;
        byRevision.put(bucket.revision, key);
        enforceLimit(key);
        onChange.run();
    }

    /** Drops the buckets farthest from the latest change until the map fits its budget. */
    private void enforceLimit(long latest) {
        if (sampleCount <= maxSamples) {
            return;
        }
        double fx = (bucketX(latest) + 0.5D) * BUCKET;
        double fz = (bucketZ(latest) + 0.5D) * BUCKET;
        List<Map.Entry<Long, Bucket>> candidates = new ArrayList<>(buckets.entrySet());
        candidates.sort(
                (a, b) ->
                        Double.compare(
                                horizontalDistance(b.getKey(), fx, fz),
                                horizontalDistance(a.getKey(), fx, fz)));
        for (Map.Entry<Long, Bucket> candidate : candidates) {
            if (sampleCount <= maxSamples) {
                break;
            }
            Bucket bucket = candidate.getValue();
            if (bucket.entries.isEmpty() || candidate.getKey() == latest) {
                continue;
            }
            sampleCount -= bucket.entries.size();
            bucket.entries.clear();
            byRevision.remove(bucket.revision);
            bucket.revision = ++revision;
            byRevision.put(bucket.revision, candidate.getKey());
        }
    }

    /**
     * Buckets changed after {@code afterRevision}, oldest change first, up to about {@code
     * sampleBudget} samples (at least one bucket).
     */
    public synchronized List<BucketView> bucketsAfter(int afterRevision, int sampleBudget) {
        List<BucketView> result = new ArrayList<>();
        int samples = 0;
        NavigableMap<Integer, Long> pending = byRevision.tailMap(afterRevision, false);
        for (Map.Entry<Integer, Long> change : pending.entrySet()) {
            Bucket bucket = buckets.get(change.getValue());
            if (bucket == null) {
                continue;
            }
            if (!result.isEmpty() && samples + bucket.entries.size() > sampleBudget) {
                break;
            }
            List<LidarSample> copy = new ArrayList<>(bucket.entries.size());
            for (Entry entry : bucket.entries) {
                copy.add(entry.sample);
            }
            samples += copy.size();
            result.add(new BucketView(change.getValue(), change.getKey(), copy));
        }
        return result;
    }

    /** Stored samples of a bucket, for tests and merging. */
    public synchronized List<LidarSample> samples(long bucketKey) {
        Bucket bucket = buckets.get(bucketKey);
        if (bucket == null) {
            return List.of();
        }
        List<LidarSample> result = new ArrayList<>(bucket.entries.size());
        for (Entry entry : bucket.entries) {
            result.add(entry.sample);
        }
        return result;
    }

    // ------------------------------------------------------------------------------------------
    // Objects and coverage

    public synchronized List<LidarObject> objects() {
        List<LidarObject> result = new ArrayList<>(objects.size());
        for (StoredObject stored : objects.values()) {
            result.add(stored.object());
        }
        return result;
    }

    public synchronized LidarObject object(int id) {
        StoredObject stored = objects.get(id);
        return stored == null ? null : stored.object();
    }

    public synchronized long objectConfig(int id) {
        StoredObject stored = objects.get(id);
        return stored == null ? 0L : stored.config();
    }

    /** Adds an object under a new id, or replaces the one with its id; returns the id used. */
    public synchronized int putObject(LidarObject object, long config) {
        LidarObject stored = object;
        if (object.id() <= 0 || !objects.containsKey(object.id())) {
            stored = object.withId(nextObjectId++);
        }
        objects.put(stored.id(), new StoredObject(stored, config));
        while (objects.size() > maxObjects) {
            Iterator<Integer> oldest = objects.keySet().iterator();
            oldest.next();
            oldest.remove();
        }
        objectRevision++;
        onChange.run();
        return stored.id();
    }

    public synchronized void removeObject(int id) {
        if (objects.remove(id) != null) {
            objectRevision++;
            onChange.run();
        }
    }

    public synchronized List<LidarCoverage> coverage() {
        return List.copyOf(coverage);
    }

    /** Adds a covered volume, replacing an identical older one; keeps the latest 32. */
    public synchronized void addCoverage(LidarCoverage added) {
        coverage.removeIf(
                c ->
                        c.cave() == added.cave()
                                && c.x() == added.x()
                                && c.z() == added.z()
                                && c.radius() == added.radius());
        coverage.add(added);
        while (coverage.size() > 32) {
            coverage.removeFirst();
        }
        objectRevision++;
        onChange.run();
    }

    // ------------------------------------------------------------------------------------------
    // Rules

    /** Weight left to a reading of age {@code ticks}: half after {@code staleTicks}. */
    public static double decay(long ticks, long staleTicks) {
        if (ticks <= 0L || staleTicks <= 0L) {
            return 1.0D;
        }
        return Math.pow(0.5D, ticks / (double) staleTicks);
    }

    /**
     * Displayed certainty of a seismic cell: a logistic step around the detection threshold, so
     * cells barely above the noise are faint and clear anomalies bright.
     */
    public static double confidence(double intensity) {
        double value = 1.0D / (1.0D + Math.exp(-(intensity - 4.4D) / 0.9D));
        return Math.max(0.04D, Math.min(1.0D, value));
    }

    static long freshEpoch() {
        long value = ThreadLocalRandom.current().nextLong();
        return value == 0L ? 1L : value;
    }

    public synchronized Collection<Long> keys() {
        return List.copyOf(buckets.keySet());
    }
}

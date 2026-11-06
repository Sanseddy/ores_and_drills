package dev.world.geology.lidar;

import dev.world.geology.SurveyTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.ToDoubleFunction;

/**
 * Reveals one finished survey into a map as its fronts sweep outwards from the station. A bucket is
 * folded into the map when the front passes its centre, so the cloud grows ring by ring while the
 * survey runs and nothing beyond the front is ever sent. Objects appear when the front reaches them
 * or the first of their samples. Folding is idempotent for the same survey configuration, so a
 * survey resubmitted after a restart can sweep again over what it already revealed.
 */
public final class LidarSweep {
    private static final int BUCKETS_PER_STEP = 96;

    /** Everything about the survey the sweep needs. */
    public record Context(
            double centerX,
            double centerZ,
            double topY,
            double bottomY,
            double radius,
            double caveRadius,
            int level,
            int analyzer,
            float cell,
            long config,
            long time,
            long staleTicks,
            ToDoubleFunction<SurveyTarget.Candidate> prior) {}

    private final Context context;
    private final Map<Long, List<LidarMap.Keyed>> anomalies = new HashMap<>();
    private final List<LidarObject> targets;
    private final Map<Long, List<LidarSample>> caveSamples = new HashMap<>();
    private final List<LidarObject> caves;
    private final List<Task> oreTasks = new ArrayList<>();
    private final List<Task> caveTasks = new ArrayList<>();
    private int oreCursor;
    private int caveCursor;
    private final int[] targetIds;
    private final int[] caveIds;
    private final Set<Integer> matched = new HashSet<>();
    private final Set<Integer> caveObjects = new HashSet<>();
    private boolean finished;

    private record Task(long bucket, double distance, boolean cave) {}

    public LidarSweep(
            LidarMap map,
            Context context,
            List<LidarMap.Keyed> anomalies,
            List<LidarObject> targets,
            List<LidarSample> caveSamples,
            List<LidarObject> caves) {
        this.context = context;
        this.targets = List.copyOf(targets);
        this.caves = List.copyOf(caves);
        this.targetIds = new int[targets.size()];
        this.caveIds = new int[caves.size()];
        for (LidarMap.Keyed keyed : anomalies) {
            LidarSample s = keyed.sample();
            this.anomalies
                    .computeIfAbsent(
                            LidarMap.bucketKey(s.x(), s.y(), s.z()), ignored -> new ArrayList<>())
                    .add(keyed);
        }
        for (LidarSample s : caveSamples) {
            this.caveSamples
                    .computeIfAbsent(
                            LidarMap.bucketKey(s.x(), s.y(), s.z()), ignored -> new ArrayList<>())
                    .add(s);
        }
        Set<Long> ore = new HashSet<>(this.anomalies.keySet());
        ore.addAll(
                map.bucketKeys(
                        key ->
                                LidarMap.horizontalDistance(
                                                key, context.centerX(), context.centerZ())
                                        <= context.radius() + LidarMap.BUCKET));
        for (long key : ore) {
            oreTasks.add(new Task(key, distance(key), false));
        }
        if (context.analyzer() > 0 && context.caveRadius() > 0.0D) {
            Set<Long> cave = new HashSet<>(this.caveSamples.keySet());
            cave.addAll(
                    map.bucketKeys(
                            key ->
                                    LidarMap.horizontalDistance(
                                                    key, context.centerX(), context.centerZ())
                                            <= context.caveRadius() + LidarMap.BUCKET));
            for (long key : cave) {
                caveTasks.add(new Task(key, distance(key), true));
            }
        }
        Comparator<Task> order =
                Comparator.comparingDouble(Task::distance).thenComparingLong(Task::bucket);
        oreTasks.sort(order);
        caveTasks.sort(order);
    }

    private double distance(long key) {
        return LidarMap.horizontalDistance(key, context.centerX(), context.centerZ());
    }

    public Context context() {
        return context;
    }

    public boolean finished() {
        return finished;
    }

    /** Share of the work done, 0–1. */
    public double progress() {
        int total = oreTasks.size() + caveTasks.size();
        return total == 0 ? (finished ? 1.0D : 0.0D) : (oreCursor + caveCursor) / (double) total;
    }

    /**
     * Folds everything the fronts have passed into {@code map}; finishes when both fronts reached
     * their radius. Returns true once finished.
     */
    public boolean advance(LidarMap map, double oreFront, double caveFront) {
        if (finished) {
            return true;
        }
        int processed = 0;
        while (oreCursor < oreTasks.size()
                && processed < BUCKETS_PER_STEP
                && oreTasks.get(oreCursor).distance() <= oreFront) {
            sweepAnomalies(map, oreTasks.get(oreCursor++).bucket());
            processed++;
        }
        while (caveCursor < caveTasks.size()
                && processed < BUCKETS_PER_STEP
                && caveTasks.get(caveCursor).distance() <= caveFront) {
            sweepCaves(map, caveTasks.get(caveCursor++).bucket());
            processed++;
        }
        for (int i = 0; i < targets.size(); i++) {
            LidarObject target = targets.get(i);
            if (targetIds[i] == 0
                    && Math.hypot(target.x() - context.centerX(), target.z() - context.centerZ())
                            <= oreFront) {
                revealTarget(map, i);
            }
        }
        for (int i = 0; i < caves.size(); i++) {
            LidarObject cave = caves.get(i);
            if (caveIds[i] == 0
                    && Math.hypot(cave.x() - context.centerX(), cave.z() - context.centerZ())
                            <= caveFront) {
                revealCave(map, i);
            }
        }
        if (oreCursor >= oreTasks.size()
                && caveCursor >= caveTasks.size()
                && oreFront >= context.radius()
                && (context.analyzer() <= 0 || caveFront >= context.caveRadius())) {
            finish(map);
        }
        return finished;
    }

    private void sweepAnomalies(LidarMap map, long bucket) {
        List<LidarMap.Keyed> fresh = anomalies.getOrDefault(bucket, List.of());
        List<LidarMap.Keyed> mapped = new ArrayList<>(fresh.size());
        for (LidarMap.Keyed keyed : fresh) {
            int object = keyed.sample().object();
            LidarSample sample =
                    keyed.sample().withObject(object > 0 ? revealTarget(map, object - 1) : 0);
            mapped.add(new LidarMap.Keyed(keyed.key(), sample, keyed.intensity(), keyed.cell()));
        }
        map.mergeAnomalies(
                bucket,
                mapped,
                sample ->
                        covers(sample.x(), sample.z(), context.radius())
                                && sample.y() <= context.topY()
                                && sample.y() >= context.bottomY(),
                context.cell(),
                context.config(),
                context.time(),
                context.staleTicks());
    }

    private void sweepCaves(LidarMap map, long bucket) {
        List<LidarSample> fresh = caveSamples.getOrDefault(bucket, List.of());
        List<LidarSample> mapped = new ArrayList<>(fresh.size());
        for (LidarSample sample : fresh) {
            int object = sample.object();
            mapped.add(sample.withObject(object > 0 ? revealCave(map, object - 1) : 0));
        }
        map.replaceCaves(bucket, mapped, context.analyzer(), context.time(), context.staleTicks());
    }

    private boolean covers(double x, double z, double radius) {
        return Math.hypot(x - context.centerX(), z - context.centerZ()) <= radius;
    }

    private int revealTarget(LidarMap map, int index) {
        if (targetIds[index] != 0) {
            return targetIds[index];
        }
        LidarObject fresh = targets.get(index);
        LidarObject match = null;
        double best = Double.POSITIVE_INFINITY;
        for (LidarObject old : map.objects()) {
            if (old.kind() != LidarObject.Kind.TARGET || matched.contains(old.id())) {
                continue;
            }
            double distance = Math.hypot(old.x() - fresh.x(), old.z() - fresh.z());
            double reach =
                    old.positionError()
                            + fresh.positionError()
                            + Math.max(
                                            Math.max(old.sizeX(), old.sizeZ()),
                                            Math.max(fresh.sizeX(), fresh.sizeZ()))
                                    * 0.5D;
            if (distance > reach || !verticallyCompatible(old, fresh)) {
                continue;
            }
            if (distance < best) {
                best = distance;
                match = old;
            }
        }
        int id;
        if (match == null) {
            id = map.putObject(fresh.withId(0), context.config());
        } else {
            LidarObject merged =
                    LidarObjectMerge.target(
                            match,
                            map.objectConfig(match.id()),
                            fresh,
                            context.config(),
                            context.time(),
                            context.staleTicks(),
                            context.prior());
            id = map.putObject(merged.withId(match.id()), context.config());
        }
        matched.add(id);
        targetIds[index] = id;
        return id;
    }

    private static boolean verticallyCompatible(LidarObject a, LidarObject b) {
        if (!a.depthKnown() || !b.depthKnown()) {
            return true;
        }
        double reach =
                (Float.isFinite(a.depthError()) ? a.depthError() : 16.0F)
                        + (Float.isFinite(b.depthError()) ? b.depthError() : 16.0F)
                        + (Float.isFinite(a.sizeY()) ? a.sizeY() * 0.5F : 0.0F)
                        + (Float.isFinite(b.sizeY()) ? b.sizeY() * 0.5F : 0.0F)
                        + 8.0D;
        return Math.abs(a.y() - b.y()) <= reach;
    }

    private int revealCave(LidarMap map, int index) {
        if (caveIds[index] != 0) {
            return caveIds[index];
        }
        LidarObject fresh = caves.get(index);
        for (LidarObject old : map.objects()) {
            if (old.kind() == LidarObject.Kind.CAVE
                    && !caveObjects.contains(old.id())
                    && old.level() > context.analyzer()
                    && context.time() - old.time() < context.staleTicks()
                    && contains(old, fresh.x(), fresh.y(), fresh.z())) {
                // A better, current map of this cave stands; its walls were kept as well.
                caveIds[index] = old.id();
                caveObjects.add(old.id());
                return old.id();
            }
        }
        int id = map.putObject(fresh.withId(0), context.config());
        caveObjects.add(id);
        caveIds[index] = id;
        return id;
    }

    private static boolean contains(LidarObject box, double x, double y, double z) {
        return Math.abs(x - box.x()) <= box.sizeX() * 0.5D
                && Math.abs(y - box.y()) <= box.sizeY() * 0.5D
                && Math.abs(z - box.z()) <= box.sizeZ() * 0.5D;
    }

    /**
     * Settles what the survey did not see again: an earlier target inside the survey that no new
     * target matched loses confidence (or goes, if the same configuration no longer sees it), and
     * earlier caves inside a newer cave map are replaced.
     */
    private void finish(LidarMap map) {
        for (int i = 0; i < targets.size(); i++) {
            revealTarget(map, i);
        }
        for (int i = 0; i < caves.size(); i++) {
            revealCave(map, i);
        }
        for (LidarObject old : map.objects()) {
            if (old.kind() == LidarObject.Kind.TARGET
                    && !matched.contains(old.id())
                    && covers(old.x(), old.z(), context.radius())) {
                if (map.objectConfig(old.id()) == context.config()) {
                    map.removeObject(old.id());
                    continue;
                }
                float confidence = old.confidence() * 0.5F;
                if (confidence < 0.1F) {
                    map.removeObject(old.id());
                } else {
                    map.putObject(old.withConfidence(confidence), map.objectConfig(old.id()));
                }
            } else if (old.kind() == LidarObject.Kind.CAVE
                    && context.analyzer() > 0
                    && !caveObjects.contains(old.id())
                    && covers(old.x(), old.z(), context.caveRadius())
                    && (old.level() <= context.analyzer()
                            || context.time() - old.time() >= context.staleTicks())) {
                map.removeObject(old.id());
            }
        }
        map.addCoverage(
                new LidarCoverage(
                        (float) context.centerX(),
                        (float) context.centerZ(),
                        (float) context.radius(),
                        (float) context.topY(),
                        (float) context.bottomY(),
                        false,
                        context.level(),
                        context.time()));
        if (context.analyzer() > 0 && context.caveRadius() > 0.0D) {
            map.addCoverage(
                    new LidarCoverage(
                            (float) context.centerX(),
                            (float) context.centerZ(),
                            (float) context.caveRadius(),
                            (float) context.topY(),
                            (float) context.bottomY(),
                            true,
                            context.analyzer(),
                            context.time()));
        }
        finished = true;
    }

    /** Ids the survey's targets received, in target order; 0 before they were revealed. */
    public List<Integer> targetIds() {
        List<Integer> ids = new ArrayList<>(targetIds.length);
        for (int id : targetIds) {
            ids.add(id);
        }
        return ids;
    }
}

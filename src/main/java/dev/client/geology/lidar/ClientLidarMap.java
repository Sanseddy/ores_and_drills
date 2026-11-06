package dev.client.geology.lidar;

import dev.network.LidarUpdatePayload;
import dev.world.geology.lidar.LidarCodec;
import dev.world.geology.lidar.LidarCoverage;
import dev.world.geology.lidar.LidarObject;
import dev.world.geology.lidar.LidarSample;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The client's copy of one 3D map, built only from the server's update slices. Each bucket keeps
 * the client tick at which each of its samples first arrived, so the view can fade in what is new
 * and leave what it already showed alone when a bucket is refined.
 */
public final class ClientLidarMap {
    private long epoch;
    private int revision;
    private int objectRevision = -1;
    private int version;
    private int objectVersion;
    private final Map<Long, Bucket> buckets = new HashMap<>();
    private final Map<Integer, LidarObject> objects = new LinkedHashMap<>();
    private List<LidarCoverage> coverage = List.of();

    /** One bucket's samples with the client tick each first arrived at. */
    public record Bucket(int revision, List<LidarSample> samples, float[] born) {}

    public long epoch() {
        return epoch;
    }

    public int revision() {
        return revision;
    }

    public int objectRevision() {
        return objectRevision;
    }

    /** Changes with every accepted bucket; renderers compare it to skip unchanged frames. */
    public int version() {
        return version;
    }

    public int objectVersion() {
        return objectVersion;
    }

    public Map<Long, Bucket> buckets() {
        return buckets;
    }

    public LidarObject object(int id) {
        return objects.get(id);
    }

    public List<LidarObject> objects() {
        return List.copyOf(objects.values());
    }

    public List<LidarCoverage> coverage() {
        return coverage;
    }

    public int sampleCount() {
        int count = 0;
        for (Bucket bucket : buckets.values()) {
            count += bucket.samples().size();
        }
        return count;
    }

    void accept(LidarUpdatePayload payload, float now) {
        if (payload.reset() || payload.epoch() != epoch) {
            buckets.clear();
            objects.clear();
            coverage = List.of();
            revision = 0;
            objectRevision = -1;
            epoch = payload.epoch();
            version++;
            objectVersion++;
        }
        for (LidarCodec.WireBucket wire : payload.buckets()) {
            Bucket previous = buckets.get(wire.key());
            float[] born = new float[wire.samples().size()];
            Set<Long> known = new HashSet<>();
            if (previous != null) {
                for (LidarSample sample : previous.samples()) {
                    known.add(identity(sample));
                }
            }
            for (int i = 0; i < born.length; i++) {
                born[i] = known.contains(identity(wire.samples().get(i))) ? -1.0E6F : now;
            }
            if (wire.samples().isEmpty()) {
                buckets.remove(wire.key());
            } else {
                buckets.put(wire.key(), new Bucket(wire.revision(), wire.samples(), born));
            }
            revision = Math.max(revision, wire.revision());
            version++;
        }
        if (payload.hasObjects()) {
            objects.clear();
            for (LidarObject object : payload.objects()) {
                objects.put(object.id(), object);
            }
            coverage = List.copyOf(payload.coverage());
            objectRevision = payload.objectRevision();
            objectVersion++;
        }
    }

    private static long identity(LidarSample sample) {
        long x = Float.floatToIntBits(sample.x());
        long y = Float.floatToIntBits(sample.y());
        long z = Float.floatToIntBits(sample.z());
        return ((x * 31L + y) * 31L + z) * 31L + sample.kind().ordinal();
    }

    /** Samples of one object across all buckets, for highlighting it. */
    public List<LidarSample> samplesOf(int objectId) {
        List<LidarSample> result = new ArrayList<>();
        for (Bucket bucket : buckets.values()) {
            for (LidarSample sample : bucket.samples()) {
                if (sample.object() == objectId) {
                    result.add(sample);
                }
            }
        }
        return result;
    }
}

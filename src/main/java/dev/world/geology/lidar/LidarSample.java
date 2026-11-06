package dev.world.geology.lidar;

/**
 * One measurement of the LiDAR-style map. The client expands it into a small cloud of points spread
 * over {@code spreadH} × {@code spreadV}: a wide spread is an uncertain position, a sharp one a
 * well-placed reading. Only processed measurements become samples; nothing in a sample names a
 * deposit or reveals more than the equipment resolved.
 *
 * @param spreadH horizontal half-extent of the point cloud, in blocks
 * @param spreadV vertical half-extent, in blocks
 * @param confidence 0–1; brightness, opacity and point count follow it
 * @param color RGB of the material, or 0 to take the colour of {@link #object}
 * @param object id of the interpreted object the sample belongs to, or 0
 */
public record LidarSample(
        Kind kind,
        float x,
        float y,
        float z,
        float spreadH,
        float spreadV,
        float confidence,
        int color,
        int object,
        int flags) {
    /** The reflection was of lower impedance than its surroundings. */
    public static final int FLAG_NEGATIVE = 1;

    /** The survey did not resolve depth: the sample spans the whole column. */
    public static final int FLAG_DEPTH_UNKNOWN = 2;

    /** A wall point of a void filled with water. */
    public static final int FLAG_WATER = 4;

    /** A wall point of a void filled with lava. */
    public static final int FLAG_LAVA = 8;

    /** What a sample shows and which layer of the view it belongs to. */
    public enum Kind {
        /** A processed seismic reflection above the noise. */
        ANOMALY(Layer.ORE),
        /** A logged block of a borehole core. */
        CORE(Layer.ORE),
        /** Ore the logging tool sensed beside a borehole. */
        SENSED(Layer.ORE),
        /** A mapped cave wall. */
        CAVE(Layer.CAVE),
        /** A suspected, unchecked connection between caves. */
        LINK_SUSPECTED(Layer.CAVE),
        /** A connection the analyser resampled and found open. */
        LINK_CONFIRMED(Layer.CAVE);

        private final Layer layer;

        Kind(Layer layer) {
            this.layer = layer;
        }

        public Layer layer() {
            return layer;
        }

        public static Kind byId(int id) {
            Kind[] values = values();
            return id >= 0 && id < values.length ? values[id] : ANOMALY;
        }
    }

    /** View layers the display mode switches between. */
    public enum Layer {
        ORE,
        CAVE
    }

    public LidarSample {
        spreadH = Math.max(0.0F, spreadH);
        spreadV = Math.max(0.0F, spreadV);
        confidence = Math.max(0.0F, Math.min(1.0F, confidence));
    }

    public LidarSample withObject(int newObject) {
        return new LidarSample(
                kind, x, y, z, spreadH, spreadV, confidence, color, newObject, flags);
    }

    public LidarSample withConfidence(float newConfidence) {
        return new LidarSample(
                kind, x, y, z, spreadH, spreadV, newConfidence, color, object, flags);
    }

    public boolean has(int flag) {
        return (flags & flag) != 0;
    }
}

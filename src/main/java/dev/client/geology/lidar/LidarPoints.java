package dev.client.geology.lidar;

import dev.world.geology.lidar.LidarObject;
import dev.world.geology.lidar.LidarSample;

/**
 * Expands map samples into LiDAR returns. A sample stands for a measurement with a position
 * uncertainty; its points are scattered over that uncertainty, more of them the more certain and
 * the larger it is, so an uncertain reading shows as a thin haze and a sharp one as a tight
 * cluster. Every point is derived from the sample's own position, so the same sample always looks
 * the same and a refined bucket does not shuffle its cloud.
 */
public final class LidarPoints {
    /** Receives one point: world position, ARGB colour, size class and birth time. */
    @FunctionalInterface
    public interface Sink {
        void point(float x, float y, float z, int argb, int size, float born);
    }

    public static final int NEGATIVE_COLOR = 0x4FA8FF;
    public static final int POSITIVE_COLOR = 0xFFB347;
    public static final int UNRESOLVED_COLOR = 0xA9B6CC;

    private LidarPoints() {}

    /** Points a sample becomes at a quality factor (1 = normal). */
    public static int count(LidarSample sample, float quality) {
        float q = Math.max(0.1F, quality);
        return switch (sample.kind()) {
            case ANOMALY -> {
                if (sample.has(LidarSample.FLAG_DEPTH_UNKNOWN)) {
                    yield clamp(Math.round((3.0F + 18.0F * sample.confidence()) * q), 2, 40);
                }
                float size = (float) Math.sqrt(Math.min(64.0F, sample.spreadH()) / 4.0F);
                yield clamp(Math.round((1.0F + 9.0F * sample.confidence() * size) * q), 1, 28);
            }
            case CORE -> clamp(Math.round(3.0F * q), 1, 6);
            case SENSED -> clamp(Math.round(6.0F * q), 2, 12);
            case CAVE, LINK_SUSPECTED, LINK_CONFIRMED -> 1;
        };
    }

    /**
     * @param objectColor RGB of the object the sample belongs to, or 0
     */
    public static void sample(
            LidarSample sample, float born, float quality, int objectColor, Sink sink) {
        int rgb = color(sample, objectColor);
        int alpha = Math.round(255.0F * (0.22F + 0.78F * sample.confidence()));
        int argb = (alpha << 24) | (rgb & 0xFFFFFF);
        int size =
                switch (sample.kind()) {
                    case CORE -> 3;
                    case ANOMALY -> sample.confidence() > 0.6F ? 3 : 2;
                    default -> 2;
                };
        int n = count(sample, quality);
        long seed = seed(sample);
        for (int i = 0; i < n; i++) {
            long a = mix(seed + i * 0x9E3779B97F4A7C15L);
            long b = mix(a);
            long c = mix(b);
            float dx = n == 1 && sample.kind() != LidarSample.Kind.ANOMALY ? 0.0F : spread(a, b);
            float dy = n == 1 && sample.kind() != LidarSample.Kind.ANOMALY ? 0.0F : spread(b, c);
            float dz = n == 1 && sample.kind() != LidarSample.Kind.ANOMALY ? 0.0F : spread(c, a);
            if (sample.has(LidarSample.FLAG_DEPTH_UNKNOWN)) {
                // Uniform over the column: the survey knows nothing about depth.
                dy = unit(c) * 2.0F - 1.0F;
            }
            sink.point(
                    sample.x() + dx * sample.spreadH(),
                    sample.y() + dy * sample.spreadV(),
                    sample.z() + dz * sample.spreadH(),
                    argb,
                    size,
                    born);
        }
    }

    public static int color(LidarSample sample, int objectColor) {
        if (sample.color() != 0) {
            return sample.color();
        }
        if (objectColor != 0) {
            return objectColor;
        }
        if (sample.has(LidarSample.FLAG_DEPTH_UNKNOWN)) {
            return UNRESOLVED_COLOR;
        }
        return sample.has(LidarSample.FLAG_NEGATIVE) ? NEGATIVE_COLOR : POSITIVE_COLOR;
    }

    /**
     * Points filling a target's estimated body: an ellipsoid of its outline and thickness, blurred
     * by its position errors. A target without resolved depth fills the column between {@code topY}
     * and {@code bottomY}.
     */
    public static void body(
            LidarObject object,
            float topY,
            float bottomY,
            float quality,
            int rgb,
            float born,
            Sink sink) {
        if (object.kind() != LidarObject.Kind.TARGET
                && object.kind() != LidarObject.Kind.CORE_ORE) {
            return;
        }
        float rx = Math.max(1.0F, object.sizeX() * 0.5F);
        float rz = Math.max(1.0F, object.sizeZ() * 0.5F);
        boolean depth = object.depthKnown();
        float ry;
        float cy;
        if (!depth) {
            cy = (topY + bottomY) * 0.5F;
            ry = Math.max(1.0F, (topY - bottomY) * 0.5F);
        } else {
            cy = object.y();
            ry =
                    Float.isFinite(object.sizeY())
                            ? Math.max(0.5F, object.sizeY() * 0.5F)
                            : Float.isFinite(object.depthError())
                                    ? Math.max(2.0F, object.depthError())
                                    : 6.0F;
        }
        float lateralBlur = object.positionError() * 0.5F;
        float verticalBlur =
                depth && Float.isFinite(object.depthError()) ? object.depthError() * 0.5F : 0.0F;
        int detail = Math.max(0, object.detail());
        int n =
                clamp(
                        Math.round(
                                (24.0F + 90.0F * (detail + 1) * object.confidence())
                                        * Math.max(0.1F, quality)
                                        * (depth ? 1.0F : 0.5F)),
                        12,
                        480);
        int alpha = Math.round(255.0F * (depth ? 0.35F + 0.5F * object.confidence() : 0.25F));
        int argb = (alpha << 24) | (rgb & 0xFFFFFF);
        float cos = (float) Math.cos(object.angle());
        float sin = (float) Math.sin(object.angle());
        long seed =
                mix(
                        Float.floatToIntBits(object.x()) * 31L
                                + Float.floatToIntBits(object.z())
                                + object.id());
        for (int i = 0; i < n; i++) {
            long a = mix(seed + i * 0x9E3779B97F4A7C15L);
            long b = mix(a);
            long c = mix(b);
            long d = mix(c);
            // A point in the unit ball by rejection-free spherical sampling.
            float u = unit(a) * 2.0F - 1.0F;
            float phi = unit(b) * (float) (Math.PI * 2.0D);
            float r = (float) Math.cbrt(unit(c));
            float s = (float) Math.sqrt(Math.max(0.0F, 1.0F - u * u));
            float px = r * s * (float) Math.cos(phi) * rx;
            float pz = r * s * (float) Math.sin(phi) * rz;
            float py = depth ? r * u * ry : (unit(d) * 2.0F - 1.0F) * ry;
            float wx = px * cos - pz * sin + gaussian(d, a) * lateralBlur;
            float wz = px * sin + pz * cos + gaussian(b, d) * lateralBlur;
            sink.point(
                    object.x() + wx,
                    cy + py + gaussian(c, d) * verticalBlur,
                    object.z() + wz,
                    argb,
                    2,
                    born);
        }
    }

    static long seed(LidarSample sample) {
        long h = Float.floatToIntBits(sample.x());
        h = h * 0x100000001B3L ^ Float.floatToIntBits(sample.y());
        h = h * 0x100000001B3L ^ Float.floatToIntBits(sample.z());
        return mix(h * 31L + sample.kind().ordinal());
    }

    static long mix(long z) {
        z = (z ^ (z >>> 30)) * 0xBF58476D1CE4E5B9L;
        z = (z ^ (z >>> 27)) * 0x94D049BB133111EBL;
        return z ^ (z >>> 31);
    }

    static float unit(long value) {
        return (value >>> 40) / (float) (1L << 24);
    }

    /** Triangular spread in −1…1: two uniforms averaged, denser in the middle like a blur. */
    static float spread(long a, long b) {
        return unit(a) + unit(b) - 1.0F;
    }

    static float gaussian(long a, long b) {
        double u = Math.max(1.0E-7D, unit(a));
        double v = unit(b);
        return (float) (Math.sqrt(-2.0D * Math.log(u)) * Math.cos(Math.PI * 2.0D * v));
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}

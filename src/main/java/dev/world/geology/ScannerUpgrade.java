package dev.world.geology;

/**
 * What each installed upgrade module adds. One kind of module is installed up to {@link #MAX_LEVEL}
 * times; each level improves one aspect of the equipment and, through better calibration, every
 * level narrows ore identification ({@link dev.world.geology.ore.OreIdentification#LEVEL_FACTOR}).
 * The station and the exploration drill share the levels.
 */
public final class ScannerUpgrade {
    public static final int MAX_LEVEL = 4;

    private ScannerUpgrade() {}

    /** The aspect a level improves. */
    public enum Improvement {
        /** A heavier striker and longer recording: larger survey radius. */
        RANGE,
        /** Lower-frequency sweep and stacking: less noise at depth, depth on regional maps. */
        DEPTH,
        /** Calibrated velocity model and positioning: smaller position and depth errors. */
        ACCURACY,
        /** Denser receiver channels: finer map grid and full deposit outlines. */
        DETAIL
    }

    public static int clamp(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    /** Improvement brought by installing level {@code level} (1–4). */
    public static Improvement improvement(int level) {
        return Improvement.values()[clamp(level - 1)];
    }

    public static boolean has(int level, Improvement improvement) {
        return clamp(level) > improvement.ordinal();
    }

    /** Detailed-survey radius multiplier; the regional radius has its own configured value. */
    public static double detailedRadiusFactor(int level) {
        return has(level, Improvement.RANGE) ? 1.5D : 1.0D;
    }

    /**
     * Noise multiplier at {@code depth} blocks below the station: the deep part of the record gains
     * the most from the longer, lower sweep.
     */
    public static double noiseFactor(int level, double depth) {
        if (!has(level, Improvement.DEPTH)) {
            return 1.0D;
        }
        return 1.0D - 0.35D * Math.min(1.0D, Math.max(0.0D, depth) / 200.0D);
    }

    /** Multiplier of the positioning error of reflectors. */
    public static double positionFactor(int level) {
        return has(level, Improvement.ACCURACY) ? 0.5D : 1.0D;
    }

    /** Multiplier of the velocity (depth-scale) error. */
    public static double velocityFactor(int level) {
        return has(level, Improvement.ACCURACY) ? 0.6D : 1.0D;
    }

    /** Map pixels along each side. */
    public static int resolution(SurveyMode mode, int level) {
        int base = mode.resolution();
        return has(level, Improvement.DETAIL) ? base + base / 4 : base;
    }

    /** Whether a survey of {@code mode} at {@code level} quotes depths. */
    public static boolean resolvesDepth(SurveyMode mode, int level) {
        return mode.detailedAttributes() || has(level, Improvement.DEPTH);
    }

    /** Whether candidates name individual ores rather than ore classes. */
    public static boolean namesOres(int level) {
        return clamp(level) >= 1;
    }

    /** Whether the 3D view may show a target's outline and orientation instead of a blob. */
    public static boolean resolvesOutline(int level) {
        return has(level, Improvement.DEPTH);
    }

    /** Whether the 3D view may show a deposit's own lens (thickness, extent) inside its halo. */
    public static boolean resolvesCore(int level) {
        return has(level, Improvement.ACCURACY);
    }
}

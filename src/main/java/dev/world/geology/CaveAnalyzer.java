package dev.world.geology;

/**
 * What each installed cave analyser module adds. The analyser is its own module kind, installed
 * independently of the scanner modules up to {@link #MAX_LEVEL} times. Every level probes the voids
 * on a finer lattice over a wider area, with less positioning noise and fewer false detections;
 * from level 2 suspected connections between neighbouring caves are checked along the passage.
 * Level 0 means no analyser: the station maps no caves.
 */
public final class CaveAnalyzer {
    public static final int MAX_LEVEL = 3;

    private static final int[] SPACING = {0, 8, 5, 3};
    private static final int[] RADIUS = {0, 48, 80, 112};
    private static final double[] JITTER = {0.0D, 2.5D, 1.2D, 0.5D};
    private static final double[] FALSE_RATE = {0.0D, 0.004D, 0.0015D, 0.0004D};
    private static final double[] DETECTION_CELLS = {1.0D, 6.0D, 3.0D, 1.2D};
    private static final double[] WEAK_FACTOR = {1.0D, 1.0D, 0.5D, 0.15D};
    private static final int[] CONFIRM_STEP = {0, 0, 2, 1};
    private static final double[] SWEEP_SPEED = {1.0D, 0.5D, 0.75D, 1.0D};
    private static final int[] MAX_SAMPLES = {0, 8_000, 20_000, 40_000};
    private static final int[] DRILL_RADIUS = {0, 4, 8, 12};
    private static final int[] DRILL_STEP = {1, 2, 1, 1};

    private CaveAnalyzer() {}

    public static int clamp(int level) {
        return Math.max(0, Math.min(MAX_LEVEL, level));
    }

    public static boolean installed(int level) {
        return clamp(level) > 0;
    }

    /** Lattice spacing of the void probes, in blocks; smaller voids fall between the probes. */
    public static int spacing(int level) {
        return SPACING[clamp(level)];
    }

    /** Horizontal radius of the cave map around the station, never wider than the survey. */
    public static int radius(int level, int surveyRadius) {
        return Math.min(RADIUS[clamp(level)], Math.max(0, surveyRadius));
    }

    /** Standard deviation of a mapped wall point's position, in blocks. */
    public static double jitter(int level) {
        return JITTER[clamp(level)];
    }

    /** Chance that a probe in solid rock reads as open: the instrument's false detections. */
    public static double falseRate(int level) {
        return FALSE_RATE[clamp(level)];
    }

    /**
     * Probe count at which a void is detected with probability 1 − 1/e. Larger caves cross more
     * probes and are found reliably; a void of one or two probes is often missed at low levels.
     */
    public static double detectionCells(int level) {
        return DETECTION_CELLS[clamp(level)];
    }

    /**
     * Factor on the detection chance of a void made only of false readings. Better analysers
     * cross-check readings and reject most of them.
     */
    public static double weakFactor(int level) {
        return WEAK_FACTOR[clamp(level)];
    }

    /**
     * Step in blocks at which a suspected connection is resampled along the passage, or 0 when the
     * analyser cannot check connections and only reports them as suspected.
     */
    public static int confirmStep(int level) {
        return CONFIRM_STEP[clamp(level)];
    }

    /** Speed of the void analysis relative to the seismic pulse front. */
    public static double sweepSpeed(int level) {
        return SWEEP_SPEED[clamp(level)];
    }

    /** Most wall points one survey keeps; a denser map at higher levels. */
    public static int maxSamples(int level) {
        return MAX_SAMPLES[clamp(level)];
    }

    /** Radius the exploration drill's analyser senses voids around the hole. */
    public static int drillRadius(int level) {
        return DRILL_RADIUS[clamp(level)];
    }

    /** Every how many blocks of depth the drill's analyser takes a void ring. */
    public static int drillStep(int level) {
        return DRILL_STEP[clamp(level)];
    }
}

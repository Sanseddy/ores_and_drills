package dev.world.level.levelgen;

/**
 * Visual fill stages of an ore deposit block. Each stage carries a fill factor relative to the "normal"
 * look of the original ore texture: {@code 1.0} shows as many ore specks as were detected on the original
 * texture, lower factors remove specks, higher factors add extra ones.
 * <p>
 * The stage is derived from the absolute remaining amount through fixed ranges, so deposit tier, ore type
 * and a block's original amount do not take part here.
 */
public final class OreVisualStages {
    /** Nearly empty, depleted, normal, overfilled. */
    private static final double[] FILL_FACTORS = {0.2D, 0.55D, 1.0D, 1.6D};
    public static final int COUNT = FILL_FACTORS.length;
    public static final int NORMAL_STAGE = 2;
    /**
     * Highest remaining amount shown by each stage but the last: 1-4, 5-24, 25-64, then 65 and more (up to
     * {@link OreDepositData#MAXIMUM_ORE_AMOUNT}).
     */
    private static final int[] STAGE_MAXIMUM_ORE = {4, 24, 64};

    private OreVisualStages() {
    }

    public static int stageFor(int remainingOre) {
        for (int stage = 0; stage < STAGE_MAXIMUM_ORE.length; stage++) {
            if (remainingOre <= STAGE_MAXIMUM_ORE[stage]) {
                return stage;
            }
        }
        return COUNT - 1;
    }

    public static double fillFactor(int stage) {
        return FILL_FACTORS[clamp(stage)];
    }

    public static double maximumFillFactor() {
        return FILL_FACTORS[COUNT - 1];
    }

    public static int clamp(int stage) {
        return Math.max(0, Math.min(COUNT - 1, stage));
    }
}

package dev.world.block;

/** Pure mining-speed scaling shared by the client and server block-breaking paths. */
final class OreDepositMiningSpeed {
    static final float MAXIMUM_DURATION_MULTIPLIER = 4.0F;

    private OreDepositMiningSpeed() {
    }

    /**
     * Converts the synchronized logarithmic richness stage into a destroy-progress multiplier.
     * Stage zero keeps the normal block speed; the richest stage takes four times as long.
     */
    static float progressMultiplier(int visualRichness, int fillStageCount) {
        int maximumVisualRichness = Math.max(0, fillStageCount - 1);
        if (maximumVisualRichness == 0) {
            return 1.0F;
        }

        int clampedRichness = Math.max(0, Math.min(maximumVisualRichness, visualRichness));
        float normalizedRichness = clampedRichness / (float) maximumVisualRichness;
        float durationMultiplier = 1.0F
                + normalizedRichness * (MAXIMUM_DURATION_MULTIPLIER - 1.0F);
        return 1.0F / durationMultiplier;
    }
}

package dev.world.level.levelgen;

/**
 * Horizontal reach of one {@code Feature.ORE} call around its origin, with the exact arithmetic of
 * {@code OreFeature.place}. The feature reads {@code OCEAN_FLOOR_WG} heights over its whole
 * bounding square, fills positions only inside it, and checks the six neighbours of each position
 * for air; nothing it touches lies farther from the origin than {@link #horizontalReach(int)}
 * blocks along X or Z.
 */
final class OreFeatureFootprint {
    private OreFeatureFootprint() {}

    static int horizontalReach(int size) {
        float spread = size / 8.0F;
        int thickness = ceil((size / 16.0F * 2.0F + 1.0F) / 2.0F);
        // Half of the vanilla bounding square, plus the neighbour read by the air-exposure check.
        return ceil(spread) + thickness + 1;
    }

    /** {@code Mth.ceil}, kept here so the arithmetic stays testable without Minecraft classes. */
    private static int ceil(float value) {
        int truncated = (int) value;
        return value > truncated ? truncated + 1 : truncated;
    }
}

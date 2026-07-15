package dev.world.level.levelgen;

/** Pure tier-selection helpers shared by forced uranium worldgen and its unit tests. */
final class ForcedUraniumTierMath {
    private ForcedUraniumTierMath() {
    }

    static int resolvedTier(int configuredTier, boolean forcedAlexUranium, int forcedTier) {
        return forcedAlexUranium ? forcedTier : configuredTier;
    }
}

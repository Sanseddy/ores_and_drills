package dev.world.level.levelgen;

/** Pure source-attempt rarity math, deliberately independent of spatial filters and host density. */
final class OreSourceFrequencyMath {
    private OreSourceFrequencyMath() {
    }

    static double originalFrequency(double expectedAttempts, double sourceBranchChance) {
        double attempts = Math.max(0.0D, expectedAttempts);
        double chance = Math.max(0.0D, Math.min(1.0D, sourceBranchChance));
        return attempts * chance;
    }
}

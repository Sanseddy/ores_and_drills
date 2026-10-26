package dev.world.block;

import dev.util.HybridGrowthMath;

/**
 * Pure remaining-ore mining curve; kept free of Minecraft types so plain unit tests can load it.
 */
final class OreDepositMiningCurve {
    private OreDepositMiningCurve() {}

    static double durationMultiplier(
            int oreCount, double exponentialGrowth, double maxExponentialMultiplier) {
        return HybridGrowthMath.multiplier(oreCount, exponentialGrowth, maxExponentialMultiplier);
    }

    /** Converts the hybrid duration curve into the reciprocal multiplier required by BreakSpeed. */
    static float progressMultiplier(
            int oreCount, double exponentialGrowth, double maxExponentialMultiplier) {
        return (float)
                (1.0D / durationMultiplier(oreCount, exponentialGrowth, maxExponentialMultiplier));
    }
}

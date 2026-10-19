package dev.util;

/**
 * The mod's one "linear, then bounded exponential" growth curve: {@code n * min(cap, e^(growth * max(0, n - 2)))}.
 * Manual mining time grows by it with a block's ore count, and the small-deposit rarity penalty with how many
 * times rarer an ore is than the most common one.
 */
public final class HybridGrowthMath {
    private HybridGrowthMath() {
    }

    public static double multiplier(double count, double exponentialGrowth, double maxExponentialMultiplier) {
        double normalizedCount = Math.max(1.0D, count);
        double exponent = exponentialGrowth * Math.max(0.0D, normalizedCount - 2.0D);
        return normalizedCount * Math.min(maxExponentialMultiplier, Math.exp(exponent));
    }
}

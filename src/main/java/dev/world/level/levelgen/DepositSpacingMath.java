package dev.world.level.levelgen;

/** Pure dynamic-spacing math shared by world generation and regression tests. */
final class DepositSpacingMath {
    private DepositSpacingMath() {
    }

    /**
     * Independent 0..1 placement variation for a MEDIUM/LARGE deposit.  It deliberately has no
     * Size/Richness inputs: those sliders alter the deposit itself, while tier-pair bounds own spacing.
     */
    static double independentDepositScale(double randomUnit) {
        return clamp(randomUnit, 0.0D, Math.nextDown(1.0D));
    }

    static SpacingCalculation requiredDistance(
            double newDepositScale,
            double existingDepositScale,
            double minimumSpacing,
            double maximumSpacing,
            double curveExponent,
            double randomVariation,
            double signedRandomUnit
    ) {
        double boundedMinimum = Math.max(0.0D, minimumSpacing);
        double boundedMaximum = Math.max(boundedMinimum, maximumSpacing);
        double combinedScale = (clamp(newDepositScale, 0.0D, 1.0D)
                + clamp(existingDepositScale, 0.0D, 1.0D)) * 0.5D;
        double curvedScale = Math.pow(combinedScale, Math.max(0.0D, curveExponent));
        double baseDistance = boundedMinimum + curvedScale * (boundedMaximum - boundedMinimum);
        double variation = baseDistance * clamp(randomVariation, 0.0D, 1.0D);
        double randomizedDistance = baseDistance + clamp(signedRandomUnit, -1.0D, 1.0D) * variation;
        return new SpacingCalculation(
                combinedScale,
                curvedScale,
                clamp(randomizedDistance, boundedMinimum, boundedMaximum)
        );
    }

    /**
     * Derives pair ranges from normalized tier position. With global 600-1500 bounds this yields
     * MEDIUM-MEDIUM 600-1275, MEDIUM-LARGE 825-1387.5 and LARGE-LARGE 1050-1500.
     */
    static SpacingBounds tierPairBounds(
            int firstTier,
            int secondTier,
            int minimumTier,
            int maximumTier,
            double globalMinimum,
            double globalMaximum
    ) {
        double boundedMinimum = Math.max(0.0D, globalMinimum);
        double boundedMaximum = Math.max(boundedMinimum, globalMaximum);
        double denominator = Math.max(1.0D, maximumTier - (double) minimumTier);
        double firstPosition = clamp((firstTier - minimumTier) / denominator, 0.0D, 1.0D);
        double secondPosition = clamp((secondTier - minimumTier) / denominator, 0.0D, 1.0D);
        double pairPosition = (firstPosition + secondPosition) * 0.5D;
        double span = boundedMaximum - boundedMinimum;
        double pairMinimum = boundedMinimum + span * 0.5D * pairPosition;
        double pairMaximum = boundedMaximum - span * 0.25D * (1.0D - pairPosition);
        return new SpacingBounds(pairMinimum, Math.max(pairMinimum, pairMaximum));
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record SpacingCalculation(double combinedScale, double curvedScale, double requiredDistance) {
    }

    record SpacingBounds(double minimum, double maximum) {
    }
}

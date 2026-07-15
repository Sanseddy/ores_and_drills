package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepositSpacingMathTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void independentDepositScaleIsOnlyRandomPlacementVariation() {
        assertEquals(0.0D, DepositSpacingMath.independentDepositScale(-1.0D), EPSILON);
        assertEquals(0.25D, DepositSpacingMath.independentDepositScale(0.25D), EPSILON);
        assertTrue(DepositSpacingMath.independentDepositScale(1.0D) < 1.0D);
    }

    @Test
    void largerPairScalesAlwaysRequireMoreDistance() {
        double smallPair = distance(0.1D, 0.2D, 0.0D);
        double mixedPair = distance(0.2D, 0.8D, 0.0D);
        double largePair = distance(0.8D, 1.0D, 0.0D);

        assertTrue(smallPair < mixedPair);
        assertTrue(mixedPair < largePair);
    }

    @Test
    void randomVariationNeverEscapesSharedBounds() {
        assertEquals(600.0D, distance(0.0D, 0.0D, -1.0D), EPSILON);
        assertEquals(1_500.0D, distance(1.0D, 1.0D, 1.0D), EPSILON);

        double lowerVariation = distance(0.5D, 0.5D, -1.0D);
        double upperVariation = distance(0.5D, 0.5D, 1.0D);
        assertTrue(lowerVariation >= 600.0D);
        assertTrue(upperVariation <= 1_500.0D);
        assertTrue(lowerVariation < upperVariation);
    }

    @Test
    void tierBandsProduceTheRequestedApproximatePairDistances() {
        assertTierPairBounds(2, 2, 600.0D, 1_275.0D); // MEDIUM-MEDIUM
        assertTierPairBounds(2, 3, 825.0D, 1_387.5D); // MEDIUM-LARGE
        assertTierPairBounds(3, 3, 1_050.0D, 1_500.0D); // LARGE-LARGE
    }

    private static void assertTierPairBounds(int firstTier, int secondTier, double expectedMinimum, double expectedMaximum) {
        DepositSpacingMath.SpacingBounds bounds = DepositSpacingMath.tierPairBounds(
                firstTier, secondTier, 2, 3, 600.0D, 1_500.0D
        );
        assertEquals(expectedMinimum, bounds.minimum(), EPSILON);
        assertEquals(expectedMaximum, bounds.maximum(), EPSILON);
        assertEquals(expectedMinimum, pairDistance(bounds, 0.0D, 0.0D, -1.0D), EPSILON);
        assertEquals(expectedMaximum, pairDistance(bounds, 1.0D, 1.0D, 1.0D), EPSILON);
    }

    private static double pairDistance(
            DepositSpacingMath.SpacingBounds bounds,
            double firstScale,
            double secondScale,
            double randomUnit
    ) {
        return DepositSpacingMath.requiredDistance(
                firstScale, secondScale, bounds.minimum(), bounds.maximum(), 0.75D, 0.03D, randomUnit
        ).requiredDistance();
    }

    private static double distance(double newScale, double existingScale, double randomUnit) {
        return DepositSpacingMath.requiredDistance(
                newScale,
                existingScale,
                600.0D,
                1_500.0D,
                0.75D,
                0.03D,
                randomUnit
        ).requiredDistance();
    }
}

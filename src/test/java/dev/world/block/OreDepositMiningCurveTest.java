package dev.world.block;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OreDepositMiningCurveTest {
    private static final double K = 0.04D;
    private static final double MAX_MULTIPLIER = 4.0D;

    @Test
    void oneRemainingOreUsesOrdinaryOreSpeed() {
        assertEquals(1.0D, OreDepositMiningCurve.durationMultiplier(1, K, MAX_MULTIPLIER));
        assertEquals(1.0F, OreDepositMiningCurve.progressMultiplier(1, K, MAX_MULTIPLIER));
    }

    @Test
    void twoOreUnitsTakeExactlyTwiceAsLong() {
        assertEquals(2.0D, OreDepositMiningCurve.durationMultiplier(2, K, MAX_MULTIPLIER));
        assertEquals(0.5F, OreDepositMiningCurve.progressMultiplier(2, K, MAX_MULTIPLIER));
    }

    @Test
    void exponentialGrowthSmoothlySupplementsLinearDuration() {
        double two = OreDepositMiningCurve.durationMultiplier(2, K, MAX_MULTIPLIER);
        double three = OreDepositMiningCurve.durationMultiplier(3, K, MAX_MULTIPLIER);
        double ten = OreDepositMiningCurve.durationMultiplier(10, K, MAX_MULTIPLIER);
        assertEquals(3.0D * Math.exp(K), three, 0.000001D);
        assertEquals(10.0D * Math.exp(8.0D * K), ten, 0.000001D);
        assertTrue(three > two);
        assertTrue(ten > 10.0D);
    }

    @Test
    void exponentialFactorIsCappedWithoutRemovingLinearGrowth() {
        assertEquals(
                400.0D,
                OreDepositMiningCurve.durationMultiplier(100, K, MAX_MULTIPLIER),
                0.000001D);
        assertEquals(
                800.0D,
                OreDepositMiningCurve.durationMultiplier(200, K, MAX_MULTIPLIER),
                0.000001D);
    }

    @Test
    void invalidRemainingAmountsUseSingleOreSpeed() {
        assertEquals(1.0F, OreDepositMiningCurve.progressMultiplier(0, K, MAX_MULTIPLIER));
        assertEquals(1.0F, OreDepositMiningCurve.progressMultiplier(-10, K, MAX_MULTIPLIER));
    }
}

package dev.world.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreDepositMiningSpeedTest {
    private static final double K = 0.04D;
    private static final double MAX_MULTIPLIER = 4.0D;

    @Test
    void oneRemainingOreUsesOrdinaryOreSpeed() {
        assertEquals(1.0D, OreDepositMiningSpeed.durationMultiplier(1, K, MAX_MULTIPLIER));
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(1, K, MAX_MULTIPLIER));
    }

    @Test
    void twoOreUnitsTakeExactlyTwiceAsLong() {
        assertEquals(2.0D, OreDepositMiningSpeed.durationMultiplier(2, K, MAX_MULTIPLIER));
        assertEquals(0.5F, OreDepositMiningSpeed.progressMultiplier(2, K, MAX_MULTIPLIER));
    }

    @Test
    void exponentialGrowthSmoothlySupplementsLinearDuration() {
        double two = OreDepositMiningSpeed.durationMultiplier(2, K, MAX_MULTIPLIER);
        double three = OreDepositMiningSpeed.durationMultiplier(3, K, MAX_MULTIPLIER);
        double ten = OreDepositMiningSpeed.durationMultiplier(10, K, MAX_MULTIPLIER);
        assertEquals(3.0D * Math.exp(K), three, 0.000001D);
        assertEquals(10.0D * Math.exp(8.0D * K), ten, 0.000001D);
        assertTrue(three > two);
        assertTrue(ten > 10.0D);
    }

    @Test
    void exponentialFactorIsCappedWithoutRemovingLinearGrowth() {
        assertEquals(400.0D, OreDepositMiningSpeed.durationMultiplier(100, K, MAX_MULTIPLIER), 0.000001D);
        assertEquals(800.0D, OreDepositMiningSpeed.durationMultiplier(200, K, MAX_MULTIPLIER), 0.000001D);
    }

    @Test
    void invalidRemainingAmountsUseSingleOreSpeed() {
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(0, K, MAX_MULTIPLIER));
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(-10, K, MAX_MULTIPLIER));
    }
}

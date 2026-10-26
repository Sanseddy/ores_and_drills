package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DepositShapeTest {
    @Test
    void mediumAndLargeDepositsStayShallowSoTheirBlocksExpandOnXZ() {
        assertEquals(
                2,
                DepositTierMath.verticalLayersForTier(
                        DepositTier.MEDIUM.ordinal(), DepositTier.MEDIUM.normalizedPosition(), 5));
        assertEquals(
                2,
                DepositTierMath.verticalLayersForTier(
                        DepositTier.LARGE.ordinal(), DepositTier.LARGE.normalizedPosition(), 5));
    }
}

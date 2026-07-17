package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DepositShapeTest {
    @Test
    void mediumAndLargeDepositsStayShallowSoTheirBlocksExpandOnXZ() {
        assertEquals(2, DepositTierMath.verticalLayersForTier(
                DepositTier.MEDIUM.ordinal(), DepositTier.MEDIUM.normalizedPosition(), 5
        ));
        assertEquals(2, DepositTierMath.verticalLayersForTier(
                DepositTier.LARGE.ordinal(), DepositTier.LARGE.normalizedPosition(), 5
        ));
    }
}

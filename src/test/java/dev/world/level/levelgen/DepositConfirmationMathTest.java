package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepositConfirmationMathTest {
    @Test
    void completeLargeDepositIsConfirmedEvenInsideOneSection() {
        assertTrue(DepositConfirmationMath.meetsThreshold(206, 87, 1, false, false));
    }

    @Test
    void stillRejectsTooFewBlocksMissingSectionsOrMissingWallExposure() {
        assertFalse(DepositConfirmationMath.meetsThreshold(86, 87, 1, false, false));
        assertFalse(DepositConfirmationMath.meetsThreshold(206, 87, 0, false, false));
        assertFalse(DepositConfirmationMath.meetsThreshold(206, 87, 1, true, false));
        assertTrue(DepositConfirmationMath.meetsThreshold(206, 87, 1, true, true));
    }
}

package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallDepositPlacementPriorityTest {
    @Test
    void caveWallWinsWhenBothPlacementsAreUsable() {
        assertEquals(
                SmallDepositPlacementPriority.Placement.CAVE_WALL,
                SmallDepositPlacementPriority.resolve(true, true)
        );
        assertFalse(SmallDepositPlacementPriority.shouldTryUnderground(true));
    }

    @Test
    void undergroundIsUsedWhenWallShapeIsUnavailable() {
        assertEquals(
                SmallDepositPlacementPriority.Placement.UNDERGROUND,
                SmallDepositPlacementPriority.resolve(false, true)
        );
        assertTrue(SmallDepositPlacementPriority.shouldTryUnderground(false));
    }

    @Test
    void candidateIsCancelledOnlyWhenBothPlacementsFail() {
        assertEquals(
                SmallDepositPlacementPriority.Placement.CANCELLED,
                SmallDepositPlacementPriority.resolve(false, false)
        );
    }
}

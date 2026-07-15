package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreDepositDataTest {
    @Test
    void logarithmicFillStagesHaveOneDepletedAndEightPositiveStates() {
        assertEquals(0, DepositTierMath.calculateFillStage(0, 1, 5_000, 8));
        assertEquals(1, DepositTierMath.calculateFillStage(1, 1, 5_000, 8));
        assertEquals(8, DepositTierMath.calculateFillStage(5_000, 1, 5_000, 8));

        int previous = 1;
        for (int ore = 1; ore <= 5_000; ore++) {
            int stage = DepositTierMath.calculateFillStage(ore, 1, 5_000, 8);
            assertTrue(stage >= previous);
            assertTrue(stage >= 1 && stage <= 8);
            previous = stage;
        }
    }

    @Test
    void stageDependsOnAbsoluteBlockReserveOnly() {
        assertEquals(6, DepositTierMath.calculateFillStage(250, 1, 5_000, 8));
    }
}

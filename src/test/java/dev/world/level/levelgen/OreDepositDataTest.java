package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreDepositDataTest {
    @Test
    void logarithmicFillStagesHaveOneDepletedAndFourPositiveStates() {
        assertEquals(0, DepositTierMath.calculateFillStage(0, 1, 5_000, 4));
        assertEquals(1, DepositTierMath.calculateFillStage(1, 1, 5_000, 4));
        assertEquals(4, DepositTierMath.calculateFillStage(5_000, 1, 5_000, 4));

        int previous = 1;
        for (int ore = 1; ore <= 5_000; ore++) {
            int stage = DepositTierMath.calculateFillStage(ore, 1, 5_000, 4);
            assertTrue(stage >= previous);
            assertTrue(stage >= 1 && stage <= 4);
            previous = stage;
        }
    }

    @Test
    void stageDependsOnAbsoluteBlockReserveOnly() {
        assertEquals(3, DepositTierMath.calculateFillStage(250, 1, 5_000, 4));
    }

}

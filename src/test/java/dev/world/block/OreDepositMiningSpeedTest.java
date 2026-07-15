package dev.world.block;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreDepositMiningSpeedTest {
    private static final int FILL_STAGE_COUNT = 8;

    @Test
    void richestDepositTakesFourTimesAsLongAsNearlyEmptyDeposit() {
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(0, FILL_STAGE_COUNT));
        assertEquals(0.25F, OreDepositMiningSpeed.progressMultiplier(7, FILL_STAGE_COUNT));
    }

    @Test
    void miningProgressSlowsDownAtEveryRicherStage() {
        float previous = OreDepositMiningSpeed.progressMultiplier(0, FILL_STAGE_COUNT);
        for (int richness = 1; richness < FILL_STAGE_COUNT; richness++) {
            float current = OreDepositMiningSpeed.progressMultiplier(richness, FILL_STAGE_COUNT);
            assertTrue(current < previous);
            previous = current;
        }
    }

    @Test
    void invalidRichnessValuesAreClamped() {
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(-10, FILL_STAGE_COUNT));
        assertEquals(0.25F, OreDepositMiningSpeed.progressMultiplier(100, FILL_STAGE_COUNT));
        assertEquals(1.0F, OreDepositMiningSpeed.progressMultiplier(5, 1));
    }
}

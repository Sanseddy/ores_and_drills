package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreVisualStagesTest {
    @Test
    void normalStageShowsExactlyTheOriginalSpeckCount() {
        assertEquals(1.0D, OreVisualStages.fillFactor(OreVisualStages.NORMAL_STAGE));
        assertTrue(OreVisualStages.fillFactor(0) <= 0.25D);
        assertTrue(OreVisualStages.maximumFillFactor() > 1.0D);
    }

    @Test
    void fillFactorsGrowWithEveryStage() {
        for (int stage = 1; stage < OreVisualStages.COUNT; stage++) {
            assertTrue(OreVisualStages.fillFactor(stage) > OreVisualStages.fillFactor(stage - 1));
        }
    }

    @Test
    void stagesUseTheConfiguredRanges() {
        assertEquals(0, OreVisualStages.stageFor(1));
        assertEquals(0, OreVisualStages.stageFor(4));
        assertEquals(1, OreVisualStages.stageFor(5));
        assertEquals(1, OreVisualStages.stageFor(24));
        assertEquals(2, OreVisualStages.stageFor(25));
        assertEquals(2, OreVisualStages.stageFor(64));
        assertEquals(3, OreVisualStages.stageFor(65));
        assertEquals(3, OreVisualStages.stageFor(5_000));
    }

    @Test
    void stageFollowsTheAbsoluteRemainingAmount() {
        assertEquals(0, OreVisualStages.stageFor(0));
        assertEquals(0, OreVisualStages.stageFor(1));
        assertEquals(OreVisualStages.COUNT - 1, OreVisualStages.stageFor(OreDepositData.MAXIMUM_ORE_AMOUNT));
        assertEquals(OreVisualStages.COUNT - 1, OreVisualStages.stageFor(Integer.MAX_VALUE));
        int previous = 0;
        for (int ore = 1; ore <= OreDepositData.MAXIMUM_ORE_AMOUNT; ore++) {
            int stage = OreVisualStages.stageFor(ore);
            assertTrue(stage >= previous);
            previous = stage;
        }
    }
}

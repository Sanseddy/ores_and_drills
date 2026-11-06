package dev.world.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class ScannerUpgradeTest {
    @Test
    void eachLevelAddsOneImprovementInOrder() {
        ScannerUpgrade.Improvement[] order = ScannerUpgrade.Improvement.values();
        for (int level = 1; level <= ScannerUpgrade.MAX_LEVEL; level++) {
            assertEquals(order[level - 1], ScannerUpgrade.improvement(level));
            for (ScannerUpgrade.Improvement improvement : order) {
                assertEquals(improvement.ordinal() < level, ScannerUpgrade.has(level, improvement));
            }
        }
        assertFalse(ScannerUpgrade.has(0, ScannerUpgrade.Improvement.RANGE));
        assertEquals(ScannerUpgrade.MAX_LEVEL, ScannerUpgrade.clamp(99));
        assertEquals(0, ScannerUpgrade.clamp(-3));
    }

    @Test
    void improvementsOnlyEverHelp() {
        for (int level = 1; level <= ScannerUpgrade.MAX_LEVEL; level++) {
            assertTrue(
                    ScannerUpgrade.noiseFactor(level, 150)
                            <= ScannerUpgrade.noiseFactor(level - 1, 150));
            assertTrue(
                    ScannerUpgrade.positionFactor(level)
                            <= ScannerUpgrade.positionFactor(level - 1));
            assertTrue(
                    ScannerUpgrade.velocityFactor(level)
                            <= ScannerUpgrade.velocityFactor(level - 1));
            for (SurveyMode mode : SurveyMode.values()) {
                assertTrue(
                        ScannerUpgrade.resolution(mode, level)
                                >= ScannerUpgrade.resolution(mode, level - 1));
            }
        }
        assertEquals(1.0D, ScannerUpgrade.noiseFactor(4, 0), 1.0E-12D);
        assertTrue(ScannerUpgrade.noiseFactor(4, 300) < 0.7D);
    }

    @Test
    void regionalDepthNeedsTheDepthUpgrade() {
        assertFalse(ScannerUpgrade.resolvesDepth(SurveyMode.REGIONAL, 1));
        assertTrue(ScannerUpgrade.resolvesDepth(SurveyMode.REGIONAL, 2));
        assertTrue(ScannerUpgrade.resolvesDepth(SurveyMode.DETAILED, 0));
    }

    @Test
    void presetsRunFromCloseToWide() {
        List<SurveyPreset> presets = SurveyPreset.of(512, 2048);
        assertEquals(SurveyPreset.COUNT, presets.size());
        for (int i = 1; i < presets.size(); i++) {
            assertTrue(presets.get(i).radius() >= presets.get(i - 1).radius());
        }
        assertEquals(SurveyMode.DETAILED, presets.getFirst().mode());
        assertEquals(SurveyMode.REGIONAL, presets.getLast().mode());
        assertEquals(2048, presets.getLast().radius());
        assertEquals(presets.getLast(), SurveyPreset.at(42, 512, 2048));
        assertEquals(presets.getFirst(), SurveyPreset.at(-1, 512, 2048));
    }

    @Test
    void smallerAreasAndBetterScannersSurveyFaster() {
        int full = SurveyPreset.durationTicks(600, 512, 512, 0);
        assertEquals(600, full);
        assertTrue(SurveyPreset.durationTicks(600, 170, 512, 0) < full);
        assertTrue(SurveyPreset.durationTicks(600, 512, 512, 4) < full);
        assertTrue(SurveyPreset.durationTicks(600, 1, 512, 4) >= 40);
    }

    @Test
    void finerPresetsHaveSmallerCells() {
        List<SurveyPreset> presets = SurveyPreset.of(512, 2048);
        for (int i = 1; i < SurveyPreset.COUNT; i++) {
            if (presets.get(i).mode() == presets.get(i - 1).mode()) {
                assertTrue(presets.get(i).cellSize(2) > presets.get(i - 1).cellSize(2));
            }
        }
    }
}

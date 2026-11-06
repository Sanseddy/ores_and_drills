package dev.world.geology;

import static dev.world.geology.SurveyFixtures.input;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.world.geology.lidar.LidarExtraction;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarSample;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreSignature;
import dev.world.geology.ore.VanillaOres;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SurveyInterpretationTest {
    private static final long DEPOSIT = 5L;

    private static final OreSignature IRON = VanillaOres.signature("iron");

    private static List<SurveyTarget> interpret(int level) {
        SurveyInput input = survey(level);
        SeismicForwardModel.Output output = SeismicForwardModel.run(input, () -> false);
        assertNotNull(output);
        return interpret(input, output);
    }

    private static List<SurveyTarget> interpret(
            SurveyInput input, SeismicForwardModel.Output output) {
        return SurveyInterpretation.interpret(
                input,
                output,
                Map.of(DEPOSIT, new SurveyInterpretation.DepositOre(IRON.oreId(), IRON.mineral())),
                VanillaOres.table());
    }

    private static SurveyInput survey(int level) {
        List<SubsurfaceBody> bodies =
                DepositBodies.bodies(
                        DEPOSIT,
                        3,
                        40,
                        10,
                        -30,
                        25,
                        18,
                        6,
                        IRON.mineral(),
                        NaturalGeology.host(SurveyFixtures.SETTING, 10),
                        1.0D);
        return input(
                SurveyMode.DETAILED, 512, new double[0], new double[0], bodies, null, 777L, level);
    }

    private static SurveyTarget deposit(List<SurveyTarget> targets) {
        for (SurveyTarget target : targets) {
            if (target.key() == DEPOSIT) {
                return target;
            }
        }
        throw new AssertionError("deposit not among the targets");
    }

    @Test
    void lowLevelsNameOnlyOreClasses() {
        SurveyTarget target = deposit(interpret(0));
        assertEquals(SurveyTarget.Detail.FOOTPRINT, target.detail());
        assertFalse(target.candidates().isEmpty());
        for (SurveyTarget.Candidate candidate : target.candidates()) {
            assertTrue(candidate.oreId().isEmpty(), "level 0 named an ore");
        }
        assertTrue(target.depthKnown());
        assertTrue(Double.isNaN(target.thickness()));
    }

    @Test
    void upgradesRevealNamesOutlinesAndTheLens() {
        SurveyTarget named = deposit(interpret(1));
        assertTrue(named.candidates().stream().anyMatch(candidate -> !candidate.oreId().isEmpty()));
        SurveyTarget outline = deposit(interpret(2));
        assertEquals(SurveyTarget.Detail.OUTLINE, outline.detail());
        SurveyTarget lens = deposit(interpret(3));
        assertEquals(SurveyTarget.Detail.CORE, lens.detail());
        assertTrue(Double.isFinite(lens.thickness()));
        // The lens is reported near the deposit, within its stated uncertainty.
        double offset = Math.hypot(lens.x() - 40, lens.z() + 30);
        assertTrue(offset <= lens.positionError() * 4.0D + 8.0D, "lens misplaced by " + offset);
        assertTrue(Math.abs(lens.y() - 10) <= lens.depthError() * 4.0D + 8.0D);
        // Never the exact block position: the survey's own error is in every estimate.
        assertTrue(lens.positionError() > 0.0D);
    }

    @Test
    void theTrueOreLeadsOnceTheScannerIsFullyUpgraded() {
        SurveyTarget target = deposit(interpret(ScannerUpgrade.MAX_LEVEL));
        SurveyTarget.Candidate best = target.candidates().getFirst();
        assertEquals(OreIdentification.Source.ORE, best.source());
        System.out.println("level 4 candidates: " + target.candidates());
        assertTrue(target.oreProbability() > 0.5D);
    }

    @Test
    void theDepositsMapPointsBelongToItsTargetAtEveryLevel() {
        for (int level = 0; level <= ScannerUpgrade.MAX_LEVEL; level++) {
            SurveyInput input = survey(level);
            SeismicForwardModel.Output output = SeismicForwardModel.run(input, () -> false);
            assertNotNull(output);
            List<SurveyTarget> targets = interpret(input, output);
            int object = targets.indexOf(deposit(targets)) + 1;
            int near = 0;
            int attached = 0;
            for (LidarMap.Keyed keyed : LidarExtraction.anomalies(input, output, targets)) {
                LidarSample sample = keyed.sample();
                if (Math.hypot(sample.x() - 40, sample.z() + 30) <= 100.0D) {
                    near++;
                    attached += sample.object() == object ? 1 : 0;
                }
            }
            // From level 3 the target is the lens, far smaller than the halo that makes most of
            // the anomaly; the halo's points must still belong to it rather than to nothing.
            assertTrue(near > 0, "level " + level + " shows no points at the deposit");
            assertTrue(
                    attached >= near * 0.9D,
                    "level " + level + ": " + attached + " of " + near + " points attached");
        }
    }

    @Test
    void interpretationIsDeterministic() {
        assertEquals(interpret(2), interpret(2));
    }
}

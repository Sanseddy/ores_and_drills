package dev.world.geology.lidar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.world.geology.SurveyTarget;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreTraits;
import java.util.List;
import org.junit.jupiter.api.Test;

class CandidateFusionTest {
    private static SurveyTarget.Candidate ore(String id, double p) {
        return new SurveyTarget.Candidate(
                OreIdentification.Source.ORE, OreTraits.Category.METALLIC, id, p);
    }

    private static SurveyTarget.Candidate natural(double p) {
        return new SurveyTarget.Candidate(OreIdentification.Source.DENSE_MASSIF, null, "", p);
    }

    @Test
    void agreeingMeasurementsSharpenTheIdentification() {
        List<SurveyTarget.Candidate> first = List.of(ore("iron", 0.6D), ore("copper", 0.4D));
        List<SurveyTarget.Candidate> second = List.of(ore("iron", 0.6D), ore("copper", 0.4D));
        List<SurveyTarget.Candidate> fused =
                CandidateFusion.fuse(first, second, c -> 0.5D, 5, 0.0D);
        assertEquals("iron", fused.getFirst().oreId());
        assertTrue(fused.getFirst().probability() > 0.6D);
        assertEquals(
                1.0D,
                fused.stream().mapToDouble(SurveyTarget.Candidate::probability).sum(),
                1.0E-9D);
    }

    @Test
    void thePriorIsCountedOnlyOnce() {
        // A rare ore and a common one equally likely in both readings: dividing by the prior
        // favours the rare one, whose likelihood must have been higher to reach the same
        // posterior.
        List<SurveyTarget.Candidate> readings = List.of(ore("rare", 0.5D), ore("common", 0.5D));
        List<SurveyTarget.Candidate> fused =
                CandidateFusion.fuse(
                        readings, readings, c -> c.oreId().equals("rare") ? 0.1D : 0.9D, 5, 0.0D);
        assertEquals("rare", fused.getFirst().oreId());
    }

    @Test
    void candidatesOneReadingLeftOutCountAsUnlikely() {
        List<SurveyTarget.Candidate> fused =
                CandidateFusion.fuse(
                        List.of(ore("iron", 0.9D), natural(0.1D)),
                        List.of(ore("iron", 0.95D)),
                        c -> 0.3D,
                        5,
                        0.0D);
        assertEquals("iron", fused.getFirst().oreId());
        assertTrue(fused.getLast().probability() < 0.01D);
    }

    @Test
    void onlyDifferentConfigurationsAreIndependent() {
        assertFalse(CandidateFusion.independent(5L, 5L));
        assertTrue(CandidateFusion.independent(5L, 6L));
    }

    @Test
    void classListsAndNamedListsAreNotFused() {
        List<SurveyTarget.Candidate> classes =
                List.of(
                        new SurveyTarget.Candidate(
                                OreIdentification.Source.ORE, OreTraits.Category.GEM, "", 1.0D));
        assertFalse(CandidateFusion.comparable(classes, List.of(ore("iron", 1.0D))));
        assertTrue(CandidateFusion.comparable(List.of(ore("a", 1)), List.of(ore("b", 1))));
    }
}

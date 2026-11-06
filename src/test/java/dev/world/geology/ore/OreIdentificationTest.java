package dev.world.geology.ore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.world.geology.NaturalGeology;
import dev.world.geology.RockProperties;
import dev.world.geology.ScannerUpgrade;
import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleFunction;
import org.junit.jupiter.api.Test;

class OreIdentificationTest {
    private static final NaturalGeology.Setting SETTING = NaturalGeology.Setting.OVERWORLD;
    private static final DoubleFunction<RockProperties> HOST = y -> NaturalGeology.host(SETTING, y);

    private static double mean(double[] values) {
        double sum = 0.0D;
        for (double value : values) {
            sum += value;
        }
        return sum / values.length;
    }

    @Test
    void accuracyGrowsWithEveryLevel() {
        List<OreSignature> table = VanillaOres.table();
        double previousSurvey = -1.0D;
        double previousCore = -1.0D;
        for (int level = 0; level <= ScannerUpgrade.MAX_LEVEL; level++) {
            double survey =
                    mean(
                            OreIdentification.identificationProbabilities(
                                    table,
                                    HOST,
                                    SETTING,
                                    OreIdentification.Precision.survey(level, 8.0D)));
            double core =
                    mean(
                            OreIdentification.identificationProbabilities(
                                    table, HOST, SETTING, OreIdentification.Precision.core(level)));
            System.out.printf("level %d: survey %.3f core %.3f%n", level, survey, core);
            assertTrue(survey > previousSurvey, "survey accuracy did not grow at level " + level);
            assertTrue(core > previousCore, "core accuracy did not grow at level " + level);
            assertTrue(core >= survey);
            previousSurvey = survey;
            previousCore = core;
        }
        assertTrue(previousCore > 0.6D, "a fully upgraded analyser should usually name the ore");
    }

    @Test
    void indistinguishableOresShareTheProbability() {
        OreTraits a = VanillaOres.ore("tin", OreTraits.Category.METALLIC, 1, -32, 48, 6.0D);
        OreTraits b = VanillaOres.ore("zinc", OreTraits.Category.METALLIC, 1, -32, 48, 6.0D);
        List<OreSignature> table = OreSignatureMath.build(List.of(a, b), -64, 320);
        double[] truth =
                OreIdentification.expectedOre(HOST.apply(8.0D), table.getFirst().mineral());
        OreIdentification.Result result =
                OreIdentification.identify(
                        new OreIdentification.Observation(truth[0], truth[1], 8.0D, true),
                        table,
                        HOST,
                        SETTING,
                        OreIdentification.Precision.core(4),
                        false);
        assertEquals(0.5D, result.probabilityOf("minecraft:tin_ore"), 1.0E-9D);
        assertEquals(0.5D, result.probabilityOf("minecraft:zinc_ore"), 1.0E-9D);
    }

    @Test
    void theCommonerOfTwoLookAlikesIsMoreLikely() {
        OreTraits common = VanillaOres.ore("tin", OreTraits.Category.METALLIC, 1, -32, 48, 9.0D);
        OreTraits rare = VanillaOres.ore("zinc", OreTraits.Category.METALLIC, 1, -32, 48, 3.0D);
        List<OreSignature> table = OreSignatureMath.build(List.of(common, rare), -64, 320);
        double[] truth = OreIdentification.expectedOre(HOST.apply(8.0D), table.get(1).mineral());
        OreIdentification.Result result =
                OreIdentification.identify(
                        new OreIdentification.Observation(truth[0], truth[1], 8.0D, true),
                        table,
                        HOST,
                        SETTING,
                        OreIdentification.Precision.core(4),
                        false);
        assertEquals(
                3.0D,
                result.probabilityOf("minecraft:tin_ore")
                        / result.probabilityOf("minecraft:zinc_ore"),
                1.0E-9D);
    }

    @Test
    void handlesOresWithoutGenerationData() {
        List<OreTraits> ores = new ArrayList<>(VanillaOres.overworld());
        ores.add(
                new OreTraits(
                        "mystery",
                        "mymod:mystery_ore",
                        OreTraits.Category.OTHER,
                        3,
                        6.0D,
                        0,
                        -1,
                        Double.NaN));
        List<OreSignature> table = OreSignatureMath.build(ores, -64, 320);
        OreSignature mystery = table.getLast();
        double[] truth = OreIdentification.expectedOre(HOST.apply(-30.0D), mystery.mineral());
        OreIdentification.Result result =
                OreIdentification.identify(
                        new OreIdentification.Observation(truth[0], truth[1], -30.0D, true),
                        table,
                        HOST,
                        SETTING,
                        OreIdentification.Precision.core(4),
                        false);
        double total = 0.0D;
        for (OreIdentification.Candidate candidate : result.candidates()) {
            assertFalse(Double.isNaN(candidate.probability()));
            total += candidate.probability();
        }
        assertEquals(1.0D, total, 1.0E-9D);
        assertEquals("mymod:mystery_ore", result.best().oreId());
    }

    @Test
    void aCoreRulesOutNaturalLookAlikes() {
        OreSignature iron = VanillaOres.signature("iron");
        double[] truth = OreIdentification.expectedOre(HOST.apply(0.0D), iron.mineral());
        OreIdentification.Result result =
                OreIdentification.identify(
                        new OreIdentification.Observation(truth[0], truth[1], 0.0D, true),
                        VanillaOres.table(),
                        HOST,
                        SETTING,
                        OreIdentification.Precision.core(0),
                        false);
        assertEquals(1.0D, result.oreProbability(), 1.0E-9D);
        for (OreIdentification.Candidate candidate : result.candidates()) {
            assertEquals(OreIdentification.Source.ORE, candidate.source());
        }
    }

    @Test
    void cavesReadAsCaves() {
        double[] truth = OreIdentification.cavityTruth();
        OreIdentification.Result result =
                OreIdentification.identify(
                        new OreIdentification.Observation(truth[0], truth[1], 20.0D, true),
                        VanillaOres.table(),
                        HOST,
                        SETTING,
                        OreIdentification.Precision.survey(0, 6.0D),
                        true);
        assertEquals(OreIdentification.Source.CAVITY, result.best().source());
        assertTrue(result.oreProbability() < 0.05D);
    }

    @Test
    void weakFeaturelessAnomaliesMayBeNoise() {
        OreIdentification.Observation nothing =
                new OreIdentification.Observation(0.0D, 0.0D, 20.0D, true);
        OreIdentification.Result weak =
                OreIdentification.identify(
                        nothing,
                        VanillaOres.table(),
                        HOST,
                        SETTING,
                        OreIdentification.Precision.survey(0, 4.5D),
                        true);
        OreIdentification.Result strong =
                OreIdentification.identify(
                        nothing,
                        VanillaOres.table(),
                        HOST,
                        SETTING,
                        OreIdentification.Precision.survey(0, 12.0D),
                        true);
        assertEquals(OreIdentification.Source.NOISE, weak.best().source());
        double weakNoise = 0.0D;
        double strongNoise = 0.0D;
        for (OreIdentification.Candidate candidate : weak.candidates()) {
            if (candidate.source() == OreIdentification.Source.NOISE) {
                weakNoise = candidate.probability();
            }
        }
        for (OreIdentification.Candidate candidate : strong.candidates()) {
            if (candidate.source() == OreIdentification.Source.NOISE) {
                strongNoise = candidate.probability();
            }
        }
        assertTrue(weakNoise > strongNoise);
        assertEquals(0.0D, OreIdentification.Precision.core(0).falseAlarm(), 0.0D);
    }

    @Test
    void classesHideOreNamesButKeepTheTotal() {
        OreSignature gold = VanillaOres.signature("gold");
        double[] truth = OreIdentification.expectedOre(HOST.apply(-20.0D), gold.mineral());
        OreIdentification.Result result =
                OreIdentification.identify(
                        new OreIdentification.Observation(truth[0], truth[1], -20.0D, true),
                        VanillaOres.table(),
                        HOST,
                        SETTING,
                        OreIdentification.Precision.survey(0, 10.0D),
                        true);
        OreIdentification.Result classes = result.byCategory();
        double ore = 0.0D;
        for (OreIdentification.Candidate candidate : classes.candidates()) {
            assertNull(candidate.oreId());
            if (candidate.source() == OreIdentification.Source.ORE) {
                ore += candidate.probability();
            }
        }
        assertEquals(result.oreProbability(), ore, 1.0E-9D);
        assertTrue(classes.top(2, 0.0D).candidates().size() <= 2);
    }

    @Test
    void measurementsAreDeterministic() {
        double[] truth = {0.1D, -0.2D};
        OreIdentification.Precision precision = OreIdentification.Precision.survey(2, 5.0D);
        assertEquals(
                OreIdentification.measure(truth, 0.0D, true, precision, 42L),
                OreIdentification.measure(truth, 0.0D, true, precision, 42L));
    }
}

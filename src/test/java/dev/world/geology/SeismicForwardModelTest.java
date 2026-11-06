package dev.world.geology;

import static dev.world.geology.SurveyFixtures.deposit;
import static dev.world.geology.SurveyFixtures.input;
import static dev.world.geology.SurveyFixtures.peakNear;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class SeismicForwardModelTest {
    private static final double REGIONAL_RADIUS = 2048.0D;
    private static final double DETAILED_RADIUS = 512.0D;

    private static SurveyMap run(SurveyInput input) {
        SurveyMap map = SeismicForwardModel.map(input, () -> false);
        assertNotNull(map);
        return map;
    }

    @Test
    void regionalSurveyFindsALargeDepositInsideItsRadius() {
        SurveyMap map =
                run(input(SurveyMode.REGIONAL, REGIONAL_RADIUS, deposit(7L, 3, 900, 20, -600, 1)));
        double threshold = SurveyAnomalies.threshold(SurveyMode.REGIONAL);
        double atDeposit = peakNear(map, 900, -600, 160);
        System.out.printf("regional LARGE at 50 m: SNR %.2f%n", atDeposit);
        assertTrue(atDeposit >= threshold, "LARGE deposit SNR " + atDeposit);
        List<SurveyAnomalies.Anomaly> anomalies = SurveyAnomalies.find(map, null);
        assertFalse(anomalies.isEmpty());
        SurveyAnomalies.Anomaly strongest = anomalies.getFirst();
        double offset = Math.hypot(strongest.x() - 900, strongest.z() + 600);
        assertTrue(offset < 200, "anomaly misplaced by " + offset);
        // Regional surveys never quote depth or polarity.
        assertEquals(0, strongest.polarity());
        assertEquals(Integer.MIN_VALUE, strongest.depthY());
    }

    @Test
    void detailedSurveyResolvesMediumDepositsThatRegionalBarelySees() {
        List<SubsurfaceBody> medium = deposit(9L, 2, 150, 25, 120, 1);
        double regional =
                peakNear(run(input(SurveyMode.REGIONAL, REGIONAL_RADIUS, medium)), 150, 120, 160);
        SurveyMap detailedMap = run(input(SurveyMode.DETAILED, DETAILED_RADIUS, medium));
        double detailed = peakNear(detailedMap, 150, 120, 60);
        System.out.printf("MEDIUM: regional SNR %.2f, detailed SNR %.2f%n", regional, detailed);
        assertTrue(detailed >= SurveyAnomalies.threshold(SurveyMode.DETAILED));
        assertTrue(detailed > regional);
        SurveyAnomalies.Anomaly anomaly =
                SurveyAnomalies.find(
                                detailedMap,
                                new SurveyAnomalies.SurveyMetricsContext(
                                        SurveyFixtures.DATUM_Y, detailedMap.metrics().depthError()))
                        .getFirst();
        // An iron lens is denser and faster than stone: positive polarity, depth near its true Y.
        assertEquals(1, anomaly.polarity());
        assertTrue(Math.abs(anomaly.depthY() - 25) <= 25, "depth " + anomaly.depthY());
        assertTrue(anomaly.depthError() > 0);
    }

    @Test
    void signalWeakensWithDepthAndGrowsWithSize() {
        double shallow =
                peakNear(
                        run(
                                input(
                                        SurveyMode.DETAILED,
                                        DETAILED_RADIUS,
                                        deposit(3L, 3, 100, 40, 0, 1))),
                        100,
                        0,
                        60);
        double deep =
                peakNear(
                        run(
                                input(
                                        SurveyMode.DETAILED,
                                        DETAILED_RADIUS,
                                        deposit(3L, 3, 100, -40, 0, 1))),
                        100,
                        0,
                        60);
        double small =
                peakNear(
                        run(
                                input(
                                        SurveyMode.DETAILED,
                                        DETAILED_RADIUS,
                                        deposit(3L, 2, 100, 0, 0, 1))),
                        100,
                        0,
                        60);
        double large =
                peakNear(
                        run(
                                input(
                                        SurveyMode.DETAILED,
                                        DETAILED_RADIUS,
                                        deposit(3L, 3, 100, 0, 0, 1))),
                        100,
                        0,
                        60);
        System.out.printf(
                "depth: %.2f (30 m) vs %.2f (110 m); size: %.2f (MEDIUM) vs %.2f (LARGE)%n",
                shallow, deep, small, large);
        assertTrue(shallow > deep);
        assertTrue(large > small);
    }

    @Test
    void naturalStructuresProduceAnomaliesWithoutOre() {
        List<SubsurfaceBody> natural =
                NaturalGeology.bodies(
                        42L,
                        SurveyFixtures.SETTING,
                        -REGIONAL_RADIUS,
                        -REGIONAL_RADIUS,
                        REGIONAL_RADIUS,
                        REGIONAL_RADIUS,
                        SurveyFixtures.MIN_Y,
                        50);
        assertTrue(natural.size() > 20);
        SurveyMap map = run(input(SurveyMode.REGIONAL, REGIONAL_RADIUS, natural));
        int anomalies = SurveyAnomalies.find(map, null).size();
        System.out.printf("ore-free regional anomalies: %d%n", anomalies);
        assertTrue(anomalies >= 3, "only " + anomalies + " natural anomalies");
    }

    @Test
    void spreadGeophonesImproveAccuracyMoreThanAClusterOrNone() {
        List<SubsurfaceBody> bodies = deposit(5L, 3, 120, 10, 80, 1);
        SurveyMap none = run(input(SurveyMode.DETAILED, DETAILED_RADIUS, bodies));
        SurveyMap cluster =
                run(
                        input(
                                SurveyMode.DETAILED,
                                DETAILED_RADIUS,
                                new double[] {200, 204, 198, 202},
                                new double[] {150, 152, 147, 149},
                                bodies,
                                null,
                                12345L));
        SurveyMap spread =
                run(
                        input(
                                SurveyMode.DETAILED,
                                DETAILED_RADIUS,
                                new double[] {300, 120, -60, 120},
                                new double[] {80, 260, 80, -100},
                                bodies,
                                null,
                                12345L));
        SurveyMetrics a = none.metrics();
        SurveyMetrics b = cluster.metrics();
        SurveyMetrics c = spread.metrics();
        System.out.printf(
                "lateral %.1f / %.1f / %.1f, depth %.3f / %.3f / %.3f%n",
                a.lateralAccuracy(),
                b.lateralAccuracy(),
                c.lateralAccuracy(),
                a.depthError(),
                b.depthError(),
                c.depthError());
        assertTrue(c.lateralAccuracy() < b.lateralAccuracy());
        assertTrue(b.lateralAccuracy() <= a.lateralAccuracy());
        assertTrue(c.depthError() < b.depthError());
        assertTrue(b.depthError() < a.depthError());
        assertTrue(c.azimuthCoverage() > b.azimuthCoverage());
        double target = peakNear(spread, 120, 80, 60);
        assertTrue(target > peakNear(none, 120, 80, 60));
    }

    @Test
    void identicalConfigurationsGiveIdenticalResults() {
        List<SubsurfaceBody> bodies = new ArrayList<>(deposit(5L, 3, 120, 10, 80, 1));
        bodies.addAll(
                NaturalGeology.bodies(99L, SurveyFixtures.SETTING, -600, -600, 600, 600, -59, 50));
        SurveyMap first = run(input(SurveyMode.DETAILED, DETAILED_RADIUS, bodies));
        SurveyMap second = run(input(SurveyMode.DETAILED, DETAILED_RADIUS, List.copyOf(bodies)));
        assertArrayEquals(first.amplitude(), second.amplitude());
        assertArrayEquals(first.sigma(), second.sigma());
        assertArrayEquals(first.depthY(), second.depthY());
        assertArrayEquals(first.attenuation(), second.attenuation());
        assertEquals(first.metrics(), second.metrics());
        SurveyMap otherSeed =
                run(
                        input(
                                SurveyMode.DETAILED,
                                DETAILED_RADIUS,
                                new double[0],
                                new double[0],
                                bodies,
                                null,
                                777L));
        assertFalse(java.util.Arrays.equals(first.amplitude(), otherSeed.amplitude()));
    }

    @Test
    void minedOutDepositsRespondWeaker() {
        double full =
                peakNear(
                        run(
                                input(
                                        SurveyMode.DETAILED,
                                        DETAILED_RADIUS,
                                        deposit(5L, 3, 0, 20, 0, 1.0))),
                        0,
                        0,
                        60);
        double mined =
                peakNear(
                        run(
                                input(
                                        SurveyMode.DETAILED,
                                        DETAILED_RADIUS,
                                        deposit(5L, 3, 0, 20, 0, 0.2))),
                        0,
                        0,
                        60);
        System.out.printf("remaining 100%%: %.2f, 20%%: %.2f%n", full, mined);
        assertTrue(mined < full);
        // The altered halo stays after mining, so a worked-out deposit is still an anomaly.
        assertTrue(mined > 0.0D);
    }

    @Test
    void tinyDepositsDoNotClutterTheRegionalMap() {
        List<SubsurfaceBody> tiny = new ArrayList<>();
        for (int i = 0; i < 40; i++) {
            tiny.addAll(deposit(100L + i, i % 2, -1500 + i * 70, 30, 300 - i * 13, 1));
        }
        SurveyMap map = run(input(SurveyMode.REGIONAL, REGIONAL_RADIUS, tiny));
        SurveyMap empty = run(input(SurveyMode.REGIONAL, REGIONAL_RADIUS, List.of()));
        int withTiny = SurveyAnomalies.find(map, null).size();
        int without = SurveyAnomalies.find(empty, null).size();
        System.out.printf(
                "regional anomalies: %d with 40 TINY/SMALL, %d empty%n", withTiny, without);
        assertTrue(withTiny <= without + 1);
    }

    @Test
    void compositeOfDistinctSurveysLowersNoise() {
        List<SubsurfaceBody> bodies = deposit(5L, 3, 120, 10, 80, 1);
        SurveyMap first = run(input(SurveyMode.DETAILED, DETAILED_RADIUS, bodies));
        SurveyMap second =
                run(
                        input(
                                SurveyMode.DETAILED,
                                DETAILED_RADIUS,
                                new double[] {300},
                                new double[] {-200},
                                bodies,
                                null,
                                98765L));
        SurveyMap merged = SurveyComposite.merge(first, List.of(second));
        int index = first.indexAt(120, 80);
        assertTrue(merged.sigma()[index] < first.sigma()[index]);
        assertTrue(merged.metrics().noiseLevel() < first.metrics().noiseLevel());
        assertTrue(merged.metrics().lateralAccuracy() < first.metrics().lateralAccuracy());
    }

    @Test
    void cavitiesReadAsNegativeAbsorbingAnomalies() {
        // A cave system: open space between Y 10 and 30 within 60 blocks of (200, -150).
        CavityProbe caves =
                (x, y, z) -> {
                    if (y > 70) {
                        return 1.0D;
                    }
                    double dx = x - 200;
                    double dz = z + 150;
                    return y > 10 && y < 30 && dx * dx + dz * dz < 60 * 60 && ((x + z) & 3) != 0
                            ? 1.0D
                            : 0.0D;
                };
        SurveyInput base = input(SurveyMode.DETAILED, DETAILED_RADIUS, List.of());
        CavityGrid grid =
                CavityGrid.sample(
                        caves,
                        1L,
                        -DETAILED_RADIUS,
                        -DETAILED_RADIUS,
                        DETAILED_RADIUS,
                        DETAILED_RADIUS,
                        16,
                        base.datumY(),
                        base.binHeight(),
                        base.bins(),
                        200_000,
                        () -> false);
        SurveyMap map =
                run(
                        input(
                                SurveyMode.DETAILED,
                                DETAILED_RADIUS,
                                new double[0],
                                new double[0],
                                List.of(),
                                grid,
                                12345L));
        int index = map.indexAt(200, -150);
        System.out.printf(
                "cave: SNR %.2f amplitude %.4f attenuation %d%n",
                map.intensity(index), map.amplitude()[index], map.attenuation()[index] & 0xFF);
        assertTrue(map.intensity(index) >= SurveyAnomalies.threshold(SurveyMode.DETAILED));
        assertTrue(map.amplitude()[index] < 0.0F);
    }

    @Test
    void attributesTheDepositPeakToTheDepositAndIsDeterministic() {
        List<SubsurfaceBody> bodies = deposit(5L, 3, 0, 20, 0, 1);
        SurveyInput input = input(SurveyMode.DETAILED, DETAILED_RADIUS, bodies);
        SeismicForwardModel.Output first = SeismicForwardModel.run(input, () -> false);
        SeismicForwardModel.Output second = SeismicForwardModel.run(input, () -> false);
        assertNotNull(first);
        assertArrayEquals(first.sources(), second.sources());
        int index = first.map().indexAt(0, 0);
        int source = first.sources()[index];
        assertTrue(source >= 0, "deposit peak not attributed");
        assertEquals(5L, input.bodies().get(source).key());
    }

    @Test
    void upgradesLowerDeepNoiseAndRefineTheGrid() {
        List<SubsurfaceBody> bodies = deposit(5L, 3, 0, -40, 0, 1);
        SurveyInput base =
                input(
                        SurveyMode.DETAILED,
                        DETAILED_RADIUS,
                        new double[0],
                        new double[0],
                        bodies,
                        null,
                        12345L,
                        0);
        SurveyInput depth =
                input(
                        SurveyMode.DETAILED,
                        DETAILED_RADIUS,
                        new double[0],
                        new double[0],
                        bodies,
                        null,
                        12345L,
                        2);
        SurveyInput detail =
                input(
                        SurveyMode.DETAILED,
                        DETAILED_RADIUS,
                        new double[0],
                        new double[0],
                        bodies,
                        null,
                        12345L,
                        4);
        SurveyMap baseMap = run(base);
        SurveyMap depthMap = run(depth);
        SurveyMap detailMap = run(detail);
        assertTrue(depthMap.metrics().noiseLevel() < baseMap.metrics().noiseLevel());
        assertTrue(peakNear(depthMap, 0, 0, 60) > peakNear(baseMap, 0, 0, 60));
        assertTrue(detailMap.resolution() > baseMap.resolution());
        assertEquals(ScannerUpgrade.resolution(SurveyMode.DETAILED, 4), detailMap.resolution());
    }
}

package dev.world.geology;

import dev.OresAndDrillsMod;
import dev.world.geology.survey.TerrainCavityProbe;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Calibration against real overworld terrain. The GameTest overworld is flat, so the test builds
 * the vanilla overworld noise router for the world seed and probes its caves directly — exactly
 * what a survey does in a normal world, without generating anything.
 */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OverworldCalibrationGameTests {
    private static final int[][] SITES = {
        {40_000, 40_000}, {-120_000, 75_000}, {260_000, -310_000}
    };

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 5)
    public static void depositsStandOutAmongRealOverworldCaves(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        NoiseGeneratorSettings settings =
                registries
                        .registryOrThrow(Registries.NOISE_SETTINGS)
                        .getOrThrow(NoiseGeneratorSettings.OVERWORLD);
        RandomState state =
                RandomState.create(
                        settings, registries.lookupOrThrow(Registries.NOISE), level.getSeed());
        NaturalGeology.Setting setting = NaturalGeology.Setting.OVERWORLD;
        CavityProbe probe = TerrainCavityProbe.of(settings, state, setting);
        int regionalHits = 0;
        int detailedHits = 0;
        for (int[] site : SITES) {
            double cx = site[0] + 0.5D;
            double cz = site[1] + 0.5D;
            int datum = surface(probe, site[0], site[1]);
            double largeX = cx + 700;
            double largeZ = cz - 400;
            double mediumX = cx - 500;
            double mediumZ = cz + 300;
            List<SubsurfaceBody> deposits = new ArrayList<>();
            deposits.addAll(deposit(11L, 3, largeX, datum - 55, largeZ, setting));
            deposits.addAll(deposit(12L, 2, mediumX, datum - 45, mediumZ, setting));

            long started = System.nanoTime();
            SurveyMap regional =
                    run(
                            probe,
                            setting,
                            SurveyMode.REGIONAL,
                            cx,
                            datum,
                            cz,
                            2048,
                            deposits,
                            level.getSeed());
            long regionalMillis = (System.nanoTime() - started) / 1_000_000L;
            started = System.nanoTime();
            SurveyMap detailed =
                    run(
                            probe,
                            setting,
                            SurveyMode.DETAILED,
                            mediumX + 120,
                            datum,
                            mediumZ - 80,
                            512,
                            deposits,
                            level.getSeed());
            long detailedMillis = (System.nanoTime() - started) / 1_000_000L;

            double largeRegional = peakNear(regional, largeX, largeZ, 160);
            double mediumRegional = peakNear(regional, mediumX, mediumZ, 160);
            double mediumDetailed = peakNear(detailed, mediumX, mediumZ, 60);
            List<SurveyAnomalies.Anomaly> anomalies = SurveyAnomalies.find(regional, null);
            int onDeposits = 0;
            for (SurveyAnomalies.Anomaly anomaly : anomalies) {
                if (Math.hypot(anomaly.x() - largeX, anomaly.z() - largeZ) < 200
                        || Math.hypot(anomaly.x() - mediumX, anomaly.z() - mediumZ) < 200) {
                    onDeposits++;
                }
            }
            System.out.printf(
                    "Overworld site %d,%d datum %d: regional LARGE %.1f MEDIUM %.1f, detailed"
                            + " MEDIUM %.1f, regional anomalies %d (%d on deposits), %d ms / %d"
                            + " ms%n",
                    site[0],
                    site[1],
                    datum,
                    largeRegional,
                    mediumRegional,
                    mediumDetailed,
                    anomalies.size(),
                    onDeposits,
                    regionalMillis,
                    detailedMillis);
            if (largeRegional >= SurveyAnomalies.threshold(SurveyMode.REGIONAL)) {
                regionalHits++;
            }
            if (mediumDetailed >= SurveyAnomalies.threshold(SurveyMode.DETAILED)) {
                detailedHits++;
            }
        }
        helper.assertTrue(
                regionalHits >= 2,
                "A regional survey missed most LARGE deposits among real caves: " + regionalHits);
        helper.assertTrue(
                detailedHits >= 2,
                "A detailed survey missed most MEDIUM deposits among real caves: " + detailedHits);
        helper.succeed();
    }

    private static int surface(CavityProbe probe, int x, int z) {
        for (int y = 250; y > -60; y--) {
            if (probe.contrast(x, y, z) <= 0.0D) {
                return y + 1;
            }
        }
        return 64;
    }

    private static List<SubsurfaceBody> deposit(
            long key, int tier, double x, double y, double z, NaturalGeology.Setting setting) {
        double radius = tier == 3 ? 25.0D : 12.5D;
        return DepositBodies.bodies(
                key,
                tier,
                x,
                y,
                z,
                radius,
                radius * 0.8D,
                tier == 3 ? 6.0D : 4.0D,
                RockPhysics.forOre(ResourceLocation.withDefaultNamespace("iron_ore")),
                NaturalGeology.host(setting, y),
                1.0D);
    }

    private static SurveyMap run(
            CavityProbe probe,
            NaturalGeology.Setting setting,
            SurveyMode mode,
            double cx,
            int datum,
            double cz,
            double radius,
            List<SubsurfaceBody> deposits,
            long seed) {
        int minY = -59;
        List<SubsurfaceBody> bodies = new ArrayList<>(deposits);
        bodies.addAll(
                NaturalGeology.bodies(
                        seed,
                        setting,
                        cx - radius,
                        cz - radius,
                        cx + radius,
                        cz + radius,
                        minY,
                        datum - 16));
        CavityGrid grid =
                CavityGrid.sample(
                        probe,
                        seed,
                        cx - radius,
                        cz - radius,
                        cx + radius,
                        cz + radius,
                        Math.max(16.0D, radius * 2.0D / mode.resolution() * 0.5D),
                        datum,
                        SurveyInput.binHeight(mode),
                        SurveyInput.bins(mode, datum, minY),
                        131_072,
                        () -> false);
        SurveyInput input =
                new SurveyInput(
                        mode,
                        cx,
                        datum,
                        cz,
                        radius,
                        minY,
                        setting,
                        new double[0],
                        new double[0],
                        1.0D,
                        false,
                        bodies,
                        NaturalGeology.horizons(setting, minY - 5),
                        grid,
                        SeedHash.combine(seed, (long) cx, (long) cz),
                        0);
        return SeismicForwardModel.map(input, () -> false);
    }

    private static double peakNear(SurveyMap map, double x, double z, double reach) {
        double best = 0.0D;
        for (int row = 0; row < map.resolution(); row++) {
            for (int column = 0; column < map.resolution(); column++) {
                if (Math.hypot(map.pixelX(column) - x, map.pixelZ(row) - z) <= reach) {
                    best = Math.max(best, map.intensity(column + row * map.resolution()));
                }
            }
        }
        return best;
    }
}

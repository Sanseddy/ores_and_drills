package dev.world.geology;

import dev.OresAndDrillsMod;
import dev.registry.ModBlocks;
import dev.world.geology.lidar.LidarCoverage;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarMapStorage;
import dev.world.geology.lidar.LidarObject;
import dev.world.geology.lidar.LidarSample;
import dev.world.geology.station.SeismicStationBlockEntity;
import dev.world.geology.survey.SeismicSurveyService;
import dev.world.geology.survey.SurveyJob;
import dev.world.geology.survey.SurveyRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The cave analyser and the station's 3D map in a real server: caves from the terrain model where
 * nothing is loaded, caves read from loaded blocks, and a whole station survey folded into the map.
 */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LidarGameTests {
    private static final int REMOTE = 3_300_000;

    private static SurveyRequest request(
            ServerLevel level, String name, BlockPos station, int radius, int analyzer) {
        return new SurveyRequest(
                UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)),
                level.dimension().location(),
                level.getSeed(),
                SurveyMode.DETAILED,
                station,
                radius,
                List.of(),
                1.0D,
                false,
                0,
                analyzer);
    }

    /** Runs one survey to the end and hands its result to {@code check}. */
    private static void run(
            GameTestHelper helper,
            ServerLevel level,
            SurveyRequest request,
            java.util.function.Consumer<SurveyJob.Result> check) {
        AtomicReference<SurveyJob> job = new AtomicReference<>();
        helper.succeedWhen(
                () -> {
                    if (job.get() == null) {
                        job.set(SeismicSurveyService.submit(level, request));
                        helper.fail("Survey started");
                    }
                    SeismicSurveyService.find(level, request.stationId());
                    helper.assertTrue(job.get().isDone(), "Survey running");
                    helper.assertTrue(
                            !job.get().failed(),
                            "Survey failed: " + String.valueOf(job.get().failure()));
                    SeismicSurveyService.release(level, request.stationId());
                    check.accept(job.get().result());
                });
    }

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 4)
    public static void analyserMapsNetherCavesWithoutLoadingChunks(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Missing the noise-generated test dimension");
        BlockPos station = new BlockPos(REMOTE, 90, REMOTE);
        run(
                helper,
                nether,
                request(nether, "lidar-nether", station, 96, 3),
                result -> {
                    SurveyJob.Lidar lidar = result.lidar();
                    helper.assertTrue(
                            lidar.caveRadius() == 96.0D, "Cave radius " + lidar.caveRadius());
                    helper.assertTrue(!lidar.caves().isEmpty(), "No Nether cave was mapped");
                    helper.assertTrue(
                            lidar.caveSamples().stream()
                                    .anyMatch(s -> s.kind() == LidarSample.Kind.CAVE),
                            "No cave wall was mapped");
                    for (LidarSample sample : lidar.caveSamples()) {
                        double dx = sample.x() - station.getX();
                        double dz = sample.z() - station.getZ();
                        helper.assertTrue(
                                Math.sqrt(dx * dx + dz * dz) <= 96.0D + 4.0D,
                                "A wall point lies outside the analysed circle");
                    }
                    for (int dx = -7; dx <= 7; dx++) {
                        for (int dz = -7; dz <= 7; dz++) {
                            helper.assertTrue(
                                    nether.getChunkSource()
                                                    .getChunkNow(
                                                            (REMOTE >> 4) + dx, (REMOTE >> 4) + dz)
                                            == null,
                                    "The analyser loaded a chunk");
                        }
                    }
                });
    }

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 2)
    public static void analyserFindsAChamberDugIntoLoadedRock(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // A rock cube far above the test grid with a 7×7×7 chamber in its middle, several probes
        // wide, inside the test's
        // own chunk: writing into an unloaded neighbour would generate it under other tests.
        BlockPos origin = helper.absolutePos(BlockPos.ZERO);
        BlockPos corner =
                new BlockPos(
                        (origin.getX() & ~15) + 1, origin.getY() + 80, (origin.getZ() & ~15) + 1);
        helper.assertTrue(
                level.isLoaded(corner) && level.isLoaded(corner.offset(14, 0, 14)),
                "The test chunk is not loaded");
        for (int x = 0; x < 15; x++) {
            for (int y = 0; y < 15; y++) {
                for (int z = 0; z < 15; z++) {
                    boolean chamber = x >= 4 && x < 11 && y >= 4 && y < 11 && z >= 4 && z < 11;
                    level.setBlock(
                            corner.offset(x, y, z),
                            chamber
                                    ? Blocks.AIR.defaultBlockState()
                                    : Blocks.STONE.defaultBlockState(),
                            2);
                }
            }
        }
        BlockPos centre = corner.offset(7, 7, 7);
        BlockPos station = corner.offset(7, 15, 7);
        run(
                helper,
                level,
                request(level, "lidar-chamber", station, 24, 3),
                result -> {
                    LidarObject found = null;
                    for (LidarObject cave : result.lidar().caves()) {
                        double distance =
                                Math.sqrt(
                                        Math.pow(cave.x() - (centre.getX() + 0.5D), 2)
                                                + Math.pow(cave.y() - (centre.getY() + 0.5D), 2)
                                                + Math.pow(cave.z() - (centre.getZ() + 0.5D), 2));
                        if (distance <= 3.5D) {
                            found = cave;
                        }
                    }
                    helper.assertTrue(found != null, "The dug chamber was not mapped");
                    helper.assertTrue(
                            found.volume() >= 100.0F && found.volume() <= 1000.0F,
                            "Chamber volume " + found.volume());
                    for (int x = 0; x < 15; x++) {
                        for (int y = 0; y < 15; y++) {
                            for (int z = 0; z < 15; z++) {
                                level.setBlock(
                                        corner.offset(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                            }
                        }
                    }
                });
    }

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 4)
    public static void stationSurveyFillsItsMapAndTheMapSurvivesSaving(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 40, 2));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(origin, ModBlocks.SEISMIC_STATION.get().defaultBlockState(), 3);
        ModBlocks.SEISMIC_STATION_STRUCTURE.placeParts(level, origin);
        var station = (SeismicStationBlockEntity) level.getBlockEntity(origin);
        station.energyStorage().receiveEnergy(station.energyCapacity(), false);
        for (int i = 0; i < CaveAnalyzer.MAX_LEVEL; i++) {
            helper.assertTrue(station.raiseAnalyzerLevel(), "Analyser not installed");
        }
        helper.assertTrue(!station.raiseAnalyzerLevel(), "Analyser went past its maximum");
        // The smallest detailed preset keeps the test short.
        helper.assertTrue(station.startSurvey(0), "Station refused to start");
        helper.succeedWhen(
                () -> {
                    helper.assertTrue(
                            station.status() == SeismicStationBlockEntity.STATUS_FINISHED,
                            "Survey still running: " + station.state());
                    LidarMap map = station.lidarMap();
                    List<LidarCoverage> coverage = map.coverage();
                    helper.assertTrue(
                            coverage.stream().anyMatch(c -> !c.cave())
                                    && coverage.stream().anyMatch(LidarCoverage::cave),
                            "The survey did not record its volumes");
                    helper.assertTrue(map.sampleCount() > 0, "The map has no measurements");
                    LidarMap loaded =
                            LidarMapStorage.load(LidarMapStorage.save(map), 250_000, 1_024);
                    helper.assertTrue(
                            loaded.epoch() == map.epoch()
                                    && loaded.revision() == map.revision()
                                    && loaded.sampleCount() == map.sampleCount()
                                    && loaded.objects().equals(map.objects())
                                    && loaded.coverage().equals(map.coverage()),
                            "The map changed on save");
                    for (long key : map.keys()) {
                        helper.assertTrue(
                                loaded.samples(key).equals(map.samples(key)),
                                "A bucket changed on save");
                    }
                    ModBlocks.SEISMIC_STATION_STRUCTURE.removeParts(level, origin);
                    level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3);
                });
    }
}

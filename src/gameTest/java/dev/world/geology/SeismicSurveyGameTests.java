package dev.world.geology;

import dev.OresAndDrillsMod;
import dev.registry.ModBlocks;
import dev.registry.ModDataComponents;
import dev.world.geology.exploration.BoreholeLog;
import dev.world.geology.exploration.ExplorationDrillBlock;
import dev.world.geology.exploration.ExplorationDrillBlockEntity;
import dev.world.geology.ore.OreSignatureTable;
import dev.world.geology.station.SeismicStationBlockEntity;
import dev.world.geology.station.StationState;
import dev.world.geology.survey.SeismicSurveyService;
import dev.world.geology.survey.SurveyJob;
import dev.world.geology.survey.SurveyRequest;
import dev.world.level.levelgen.ConfirmedDeposit;
import dev.world.level.levelgen.ConfirmedDepositIndex;
import dev.world.level.levelgen.DepositCandidate;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositStonePalette;
import dev.world.level.levelgen.OreDepositTier;
import dev.world.level.levelgen.SeismicDepositForecast;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Seismic survey in a real server. Surveys of real terrain run in the Nether, the GameTest server's
 * noise-generated dimension, at coordinates no other test (and no player) ever loads, so any chunk
 * that appears there was loaded by the survey itself.
 */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeismicSurveyGameTests {
    private static final int REMOTE = 3_100_000;

    private static ServerLevel nether(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null, "Missing the noise-generated test dimension");
        return nether;
    }

    private static SurveyRequest request(
            ServerLevel level,
            String name,
            SurveyMode mode,
            BlockPos station,
            int radius,
            List<BlockPos> geophones) {
        return request(level, name, mode, station, radius, geophones, 0);
    }

    private static SurveyRequest request(
            ServerLevel level,
            String name,
            SurveyMode mode,
            BlockPos station,
            int radius,
            List<BlockPos> geophones,
            int scannerLevel) {
        return new SurveyRequest(
                UUID.nameUUIDFromBytes(name.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                level.dimension().location(),
                level.getSeed(),
                mode,
                station,
                radius,
                geophones,
                1.0D,
                false,
                scannerLevel);
    }

    /** Runs the requests one after another and hands their results to {@code check}. */
    private static void runSurveys(
            GameTestHelper helper,
            ServerLevel level,
            List<SurveyRequest> requests,
            java.util.function.Consumer<List<SurveyJob.Result>> check) {
        List<SurveyJob.Result> results = new ArrayList<>();
        AtomicReference<SurveyJob> current = new AtomicReference<>();
        helper.succeedWhen(
                () -> {
                    while (results.size() < requests.size()) {
                        SurveyRequest request = requests.get(results.size());
                        SurveyJob job = current.get();
                        if (job == null) {
                            current.set(SeismicSurveyService.submit(level, request));
                            helper.fail("Survey " + results.size() + " started");
                        }
                        SeismicSurveyService.find(level, request.stationId());
                        helper.assertTrue(job.isDone(), "Survey " + results.size() + " running");
                        helper.assertTrue(
                                !job.failed(), "Survey failed: " + String.valueOf(job.failure()));
                        results.add(job.result());
                        SeismicSurveyService.release(level, request.stationId());
                        current.set(null);
                    }
                    check.accept(results);
                });
    }

    private static boolean savedOnDisk(ServerLevel level, ChunkPos pos) {
        CollectFields visitor = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
        level.getChunkSource().chunkScanner().scanChunk(pos, visitor).join();
        return visitor.getResult() != null;
    }

    // ------------------------------------------------------------------------------------------
    // 9. Multiblock placement and removal

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void stationPlacesAsOneMultiblockAndBreaksIntoOneItem(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos origin = helper.absolutePos(new BlockPos(2, 2, 2));
        var structure = ModBlocks.SEISMIC_STATION_STRUCTURE;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
                for (int dy = 0; dy < 3; dy++) {
                    level.setBlock(origin.offset(dx, dy, dz), Blocks.AIR.defaultBlockState(), 3);
                }
            }
        }
        level.setBlock(origin.offset(1, 2, 1), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(!structure.canPlace(level, origin), "Placed into an occupied space");
        level.setBlock(origin.offset(1, 2, 1), Blocks.AIR.defaultBlockState(), 3);
        level.setBlock(origin.offset(-1, -1, 1), Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(!structure.canPlace(level, origin), "Placed without full support");
        level.setBlock(origin.offset(-1, -1, 1), Blocks.STONE.defaultBlockState(), 3);
        helper.assertTrue(structure.canPlace(level, origin), "Free supported space refused");

        var player = helper.makeMockPlayer(GameType.SURVIVAL);
        ItemStack stack = new ItemStack(ModBlocks.SEISMIC_STATION_ITEM.get());
        player.setItemInHand(InteractionHand.MAIN_HAND, stack);
        BlockPos floor = origin.below();
        stack.useOn(
                new UseOnContext(
                        player,
                        InteractionHand.MAIN_HAND,
                        new BlockHitResult(
                                Vec3.atCenterOf(floor).add(0.0D, 0.5D, 0.0D),
                                Direction.UP,
                                floor,
                                false)));
        helper.assertTrue(structure.isComplete(level, origin), "Item did not build all 27 blocks");
        var station = (SeismicStationBlockEntity) level.getBlockEntity(origin);
        UUID archive = station.stationId();
        BlockPos corner = origin.offset(1, 2, -1);
        IEnergyStorage energy =
                level.getCapability(Capabilities.EnergyStorage.BLOCK, corner, Direction.UP);
        helper.assertTrue(
                energy != null && energy.receiveEnergy(1000, false) == 1000,
                "A part block does not accept energy for the station");
        helper.assertTrue(station.energyStored() == 1000, "Energy did not reach the main block");

        int baseRadius = station.regionalRadius();
        int baseDetailed = station.detailedRadius();
        ItemStack module =
                new ItemStack(ModBlocks.SEISMIC_UPGRADE_MODULE.get(), ScannerUpgrade.MAX_LEVEL + 1);
        player.setItemInHand(InteractionHand.MAIN_HAND, module);
        BlockHitResult partHit =
                new BlockHitResult(Vec3.atCenterOf(corner), Direction.UP, corner, false);
        level.getBlockState(corner)
                .useItemOn(module, level, player, InteractionHand.MAIN_HAND, partHit);
        helper.assertTrue(
                station.scannerLevel() == 1
                        && station.regionalRadius()
                                == dev.config.SeismicSurveyConfig.UPGRADED_REGIONAL_RADIUS.get()
                        && station.regionalRadius() >= baseRadius
                        && station.detailedRadius() > baseDetailed,
                "The first module did not extend the survey radius");
        for (int i = 1; i <= ScannerUpgrade.MAX_LEVEL; i++) {
            level.getBlockState(corner)
                    .useItemOn(module, level, player, InteractionHand.MAIN_HAND, partHit);
        }
        helper.assertTrue(
                station.scannerLevel() == ScannerUpgrade.MAX_LEVEL && module.getCount() == 1,
                "Modules did not raise the level one at a time up to the maximum");
        var resaved = new SeismicStationBlockEntity(origin, station.getBlockState());
        resaved.loadWithComponents(
                station.saveWithFullMetadata(level.registryAccess()), level.registryAccess());
        helper.assertTrue(
                resaved.scannerLevel() == ScannerUpgrade.MAX_LEVEL, "The level was not saved");
        var client = new SeismicStationBlockEntity(origin, station.getBlockState());
        client.handleUpdateTag(
                station.getUpdateTag(level.registryAccess()), level.registryAccess());
        helper.assertTrue(
                client.scannerLevel() == ScannerUpgrade.MAX_LEVEL,
                "Clients do not learn the level the model shows");
        // Stations from before multi-level upgrades stored one range module: that is level 1.
        CompoundTag legacy = station.saveWithFullMetadata(level.registryAccess());
        legacy.remove("ScannerLevel");
        legacy.putBoolean("RangeModule", true);
        var migrated = new SeismicStationBlockEntity(origin, station.getBlockState());
        migrated.loadWithComponents(legacy, level.registryAccess());
        helper.assertTrue(migrated.scannerLevel() == 1, "A legacy range module was lost");

        level.destroyBlock(corner, true);
        for (BlockPos pos : structure.positions(origin)) {
            helper.assertTrue(
                    !level.getBlockState(pos).is(ModBlocks.SEISMIC_STATION.get())
                            && !level.getBlockState(pos).is(ModBlocks.SEISMIC_STATION_PART.get()),
                    "A station block survived breaking a part");
        }
        List<ItemEntity> drops =
                level.getEntitiesOfClass(ItemEntity.class, structure.bounds(origin).inflate(3.0D));
        int stations = 0;
        for (ItemEntity drop : drops) {
            if (drop.getItem().is(ModBlocks.SEISMIC_STATION_ITEM.get())) {
                stations += drop.getItem().getCount();
                helper.assertTrue(
                        archive.equals(drop.getItem().get(ModDataComponents.STATION_ID.get())),
                        "Dropped station lost its survey archive");
                helper.assertTrue(
                        Integer.valueOf(ScannerUpgrade.MAX_LEVEL)
                                .equals(drop.getItem().get(ModDataComponents.SCANNER_LEVEL.get())),
                        "Dropped station lost its upgrade level");
            }
        }
        helper.assertTrue(stations == 1, "Breaking the station dropped " + stations + " items");
        helper.assertTrue(
                drops.stream()
                        .noneMatch(
                                drop -> drop.getItem().is(ModBlocks.SEISMIC_UPGRADE_MODULE.get())),
                "Installed modules dropped although the station keeps its level");
        drops.forEach(ItemEntity::discard);
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // 6, 10, 12. Upgraded regional radius: no chunk loads, no generation change, bounded ticks

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 8)
    public static void upgradedRegionalSurveyLoadsNoChunksAndKeepsTicksShort(
            GameTestHelper helper) {
        ServerLevel level = nether(helper);
        BlockPos station = new BlockPos(REMOTE, 100, REMOTE);
        List<ChunkPos> probes = new ArrayList<>();
        for (int i = 0; i < 24; i++) {
            double angle = i * Math.PI * 2.0D / 24.0D;
            double distance = 300 + (i % 6) * 700;
            probes.add(
                    new ChunkPos(
                            BlockPos.containing(
                                    station.getX() + Math.cos(angle) * distance,
                                    0,
                                    station.getZ() + Math.sin(angle) * distance)));
        }
        probes.add(new ChunkPos(station));
        for (ChunkPos probe : probes) {
            helper.assertTrue(
                    level.getChunkSource().getChunkNow(probe.x, probe.z) == null
                            && !savedOnDisk(level, probe),
                    "Probe chunk " + probe + " existed before the survey");
        }
        // Other tests generate Nether chunks in the same batch, so only the surveyed area counts.
        int confirmedBefore =
                ConfirmedDepositIndex.get(level).recordsWithin(station, 4096 + 256, null).size();
        SeismicSurveyService.resetLongestTick();
        long started = level.getGameTime();
        AtomicBoolean loaded = new AtomicBoolean();
        SurveyRequest request =
                request(level, "regional-4096", SurveyMode.REGIONAL, station, 4096, List.of());
        SurveyJob job = SeismicSurveyService.submit(level, request);
        helper.succeedWhen(
                () -> {
                    SeismicSurveyService.find(level, request.stationId());
                    for (ChunkPos probe : probes) {
                        if (level.getChunkSource().getChunkNow(probe.x, probe.z) != null) {
                            loaded.set(true);
                        }
                    }
                    helper.assertTrue(job.isDone(), "Regional survey still running");
                    helper.assertTrue(!job.failed(), "Survey failed: " + job.failure());
                    helper.assertTrue(!loaded.get(), "The survey loaded a chunk");
                    for (ChunkPos probe : probes) {
                        helper.assertTrue(
                                !savedOnDisk(level, probe), "The survey generated " + probe);
                    }
                    helper.assertTrue(
                            ConfirmedDepositIndex.get(level)
                                            .recordsWithin(station, 4096 + 256, null)
                                            .size()
                                    == confirmedBefore,
                            "The survey changed the confirmed deposit index");
                    long longest = SeismicSurveyService.longestTickNanos();
                    SurveyMap map = job.result().map();
                    System.out.printf(
                            "Regional 4096 survey: %d ticks, longest service tick %.2f ms,"
                                    + " anomalies %d, peak %.1f%n",
                            level.getGameTime() - started,
                            longest / 1.0E6D,
                            map.metrics().anomalies(),
                            map.metrics().peakIntensity());
                    helper.assertTrue(
                            longest < 50_000_000L,
                            "A survey tick took " + longest / 1_000_000L + " ms");
                    SeismicSurveyService.release(level, request.stationId());
                });
    }

    // ------------------------------------------------------------------------------------------
    // 5. Same seed, configuration and equipment: same result

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 6)
    public static void identicalConfigurationsReproduceTheSurvey(GameTestHelper helper) {
        ServerLevel level = nether(helper);
        BlockPos station = new BlockPos(REMOTE + 20_000, 100, REMOTE);
        List<BlockPos> geophones =
                List.of(station.offset(180, 0, 40), station.offset(-90, 0, -160));
        SurveyRequest first =
                request(level, "repeat-a", SurveyMode.DETAILED, station, 512, geophones);
        SurveyRequest second =
                request(level, "repeat-b", SurveyMode.DETAILED, station, 512, geophones);
        SurveyRequest moved =
                request(
                        level,
                        "repeat-c",
                        SurveyMode.DETAILED,
                        station.offset(64, 0, 0),
                        512,
                        geophones);
        runSurveys(
                helper,
                level,
                List.of(first, second, moved),
                results -> {
                    SurveyMap a = results.get(0).map();
                    SurveyMap b = results.get(1).map();
                    helper.assertTrue(
                            Arrays.equals(a.amplitude(), b.amplitude())
                                    && Arrays.equals(a.sigma(), b.sigma())
                                    && Arrays.equals(a.depthY(), b.depthY())
                                    && Arrays.equals(a.attenuation(), b.attenuation())
                                    && a.metrics().equals(b.metrics())
                                    && results.get(0).targets().equals(results.get(1).targets()),
                            "The same configuration produced a different survey");
                    helper.assertTrue(
                            !Arrays.equals(a.amplitude(), results.get(2).map().amplitude()),
                            "Moving the station did not change the measurement");
                });
    }

    // ------------------------------------------------------------------------------------------
    // 4. Geophones: spread sensors beat a cluster, which beats none

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 6)
    public static void spreadGeophonesImproveAccuracy(GameTestHelper helper) {
        ServerLevel level = nether(helper);
        BlockPos station = new BlockPos(REMOTE + 40_000, 100, REMOTE);
        List<BlockPos> cluster =
                List.of(
                        station.offset(150, 0, 150),
                        station.offset(152, 0, 151),
                        station.offset(149, 0, 153),
                        station.offset(151, 0, 148));
        List<BlockPos> spread =
                List.of(
                        station.offset(220, 0, 0),
                        station.offset(-220, 0, 0),
                        station.offset(0, 0, 220),
                        station.offset(0, 0, -220));
        runSurveys(
                helper,
                level,
                List.of(
                        request(level, "phones-none", SurveyMode.DETAILED, station, 512, List.of()),
                        request(
                                level,
                                "phones-cluster",
                                SurveyMode.DETAILED,
                                station,
                                512,
                                cluster),
                        request(level, "phones-spread", SurveyMode.DETAILED, station, 512, spread)),
                results -> {
                    SurveyMetrics none = results.get(0).map().metrics();
                    SurveyMetrics clustered = results.get(1).map().metrics();
                    SurveyMetrics spreadOut = results.get(2).map().metrics();
                    System.out.printf(
                            "Geophones: lateral %.1f/%.1f/%.1f, depth %.3f/%.3f/%.3f, noise"
                                    + " %.5f/%.5f/%.5f%n",
                            none.lateralAccuracy(),
                            clustered.lateralAccuracy(),
                            spreadOut.lateralAccuracy(),
                            none.depthError(),
                            clustered.depthError(),
                            spreadOut.depthError(),
                            none.noiseLevel(),
                            clustered.noiseLevel(),
                            spreadOut.noiseLevel());
                    helper.assertTrue(
                            spreadOut.lateralAccuracy() < clustered.lateralAccuracy()
                                    && clustered.lateralAccuracy() <= none.lateralAccuracy(),
                            "Spread geophones did not sharpen positions most");
                    helper.assertTrue(
                            spreadOut.depthError() < none.depthError()
                                    && clustered.depthError() < none.depthError(),
                            "Moveout over geophone offsets did not constrain depth");
                    helper.assertTrue(
                            spreadOut.effectiveReceivers() > clustered.effectiveReceivers(),
                            "A cluster counted as several independent receivers");
                });
    }

    // ------------------------------------------------------------------------------------------
    // 1. A forecast LARGE deposit inside the radius shows as an anomaly

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 6)
    public static void largeDepositInsideTheRadiusIsDetected(GameTestHelper helper) {
        ServerLevel level = nether(helper);
        // MEDIUM/LARGE are rare in the Nether: scan a wide area with the cheap coarse forecast and
        // pay for the exact plan only where a cell has a deposit.
        DepositCandidate target = null;
        int cell = SeismicDepositForecast.layout(OreDepositTier.LARGE).cellSizeBlocks();
        int baseX = Math.floorDiv(REMOTE + 60_000, cell);
        int baseZ = Math.floorDiv(REMOTE, cell);
        search:
        for (int dx = 0; dx < 40; dx++) {
            for (int dz = 0; dz < 40; dz++) {
                if (SeismicDepositForecast.predict(
                                level,
                                OreDepositTier.LARGE,
                                baseX + dx,
                                baseZ + dz,
                                List.of(),
                                false)
                        .isEmpty()) {
                    continue;
                }
                for (DepositCandidate candidate :
                        SeismicDepositForecast.predict(
                                level,
                                OreDepositTier.LARGE,
                                baseX + dx,
                                baseZ + dz,
                                List.of(),
                                true)) {
                    // Away from the lava sea (Y 31), whose strong, uneven top masks reflectors
                    // that a velocity error places at the same apparent depth.
                    int y = candidate.center().getY();
                    if (y < 100 && Math.abs(y - 31) >= 20) {
                        target = candidate;
                        break search;
                    }
                }
            }
        }
        helper.assertTrue(
                target != null, "No suitable LARGE deposit forecast in 1600 Nether cells");
        DepositCandidate deposit = target;
        BlockPos station =
                new BlockPos(deposit.center().getX() + 140, 120, deposit.center().getZ() - 90);
        // Geophones around the target, as the survey workflow asks: they fix the velocity.
        List<BlockPos> geophones =
                List.of(
                        new BlockPos(deposit.center().getX() + 160, 120, deposit.center().getZ()),
                        new BlockPos(deposit.center().getX() - 160, 120, deposit.center().getZ()),
                        new BlockPos(deposit.center().getX(), 120, deposit.center().getZ() + 160),
                        new BlockPos(deposit.center().getX(), 120, deposit.center().getZ() - 160));
        runSurveys(
                helper,
                level,
                List.of(
                        request(
                                level,
                                "large-target",
                                SurveyMode.DETAILED,
                                station,
                                512,
                                geophones),
                        request(
                                level,
                                "large-target-l3",
                                SurveyMode.DETAILED,
                                station,
                                512,
                                geophones,
                                3)),
                results -> {
                    SurveyMap map = results.getFirst().map();
                    double peak = 0.0D;
                    for (int row = 0; row < map.resolution(); row++) {
                        for (int column = 0; column < map.resolution(); column++) {
                            double dx = map.pixelX(column) - deposit.center().getX();
                            double dz = map.pixelZ(row) - deposit.center().getZ();
                            if (dx * dx + dz * dz <= 80 * 80) {
                                peak =
                                        Math.max(
                                                peak,
                                                map.intensity(column + row * map.resolution()));
                            }
                        }
                    }
                    System.out.printf(
                            "LARGE %s at %s: peak %.1f sigma within 80 blocks, mineral %s%n",
                            deposit.oreId(),
                            deposit.center(),
                            peak,
                            RockPhysics.forOre(deposit.oreId()));
                    helper.assertTrue(
                            peak >= SurveyAnomalies.threshold(SurveyMode.DETAILED),
                            "The LARGE deposit stayed below the detection threshold: " + peak);
                    for (SurveyTarget anyTarget : results.getFirst().targets()) {
                        for (SurveyTarget.Candidate candidate : anyTarget.candidates()) {
                            helper.assertTrue(
                                    candidate.oreId().isEmpty(),
                                    "A level 0 survey named an ore: " + candidate);
                        }
                    }
                    SurveyTarget found = null;
                    for (SurveyTarget candidate : results.get(1).targets()) {
                        if (candidate.key() == deposit.depositId()) {
                            found = candidate;
                        }
                    }
                    helper.assertTrue(
                            found != null, "The level 3 survey did not trace the LARGE deposit");
                    System.out.printf(
                            "LARGE %s at level 3: %s at %.0f, %.0f, %.0f ±%.0f, candidates %s%n",
                            deposit.oreId(),
                            found.detail(),
                            found.x(),
                            found.y(),
                            found.z(),
                            found.positionError(),
                            found.candidates());
                    helper.assertTrue(
                            found.detail() == SurveyTarget.Detail.CORE
                                    && Double.isFinite(found.thickness())
                                    && Math.hypot(
                                                    found.x() - deposit.center().getX(),
                                                    found.z() - deposit.center().getZ())
                                            <= found.positionError() * 4.0D + 16.0D,
                            "The level 3 survey did not resolve the deposit's lens near it");
                    helper.assertTrue(
                            found.candidates().stream()
                                    .anyMatch(candidate -> !candidate.oreId().isEmpty()),
                            "The level 3 survey named no ore");
                });
    }

    // ------------------------------------------------------------------------------------------
    // 11. Partially worked deposits: real chunk data lowers the response

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void partiallyMinedDepositsRespondWeaker(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // High above the test grid, where neighbouring tests never build.
        BlockPos center = helper.absolutePos(new BlockPos(2, 60, 2));
        List<BlockPos> blocks = new ArrayList<>();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy < 2; dy++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    level.setBlock(
                            pos, ModBlocks.ORE_DEPOSIT.get().defaultBlockState(), Block.UPDATE_ALL);
                    OreDepositData.set(
                            level,
                            pos,
                            OreDepositStonePalette.indexOf(level, Blocks.STONE),
                            OreDepositOrePalette.indexOf(level, Blocks.IRON_ORE),
                            100,
                            0,
                            0,
                            OreDepositTier.MEDIUM.index());
                    blocks.add(pos);
                }
            }
        }
        ConfirmedDeposit deposit =
                new ConfirmedDeposit(
                        0x5E15L,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(
                                Blocks.IRON_ORE),
                        OreDepositTier.MEDIUM,
                        center,
                        blocks.size(),
                        1,
                        null,
                        false);
        var setting = NaturalGeology.Setting.OVERWORLD;
        List<SubsurfaceBody> full = SurveyJob.confirmedBodies(level, deposit, setting);
        for (BlockPos pos : blocks) {
            for (int i = 0; i < 70; i++) {
                OreDepositData.mineOne(level, pos);
            }
        }
        List<SubsurfaceBody> mined = SurveyJob.confirmedBodies(level, deposit, setting);
        SubsurfaceBody fullCore = full.getFirst();
        SubsurfaceBody minedCore = mined.getFirst();
        helper.assertTrue(
                fullCore.kind() == SubsurfaceBody.Kind.ORE_BODY
                        && minedCore.reflectivity() < fullCore.reflectivity() * 0.6D,
                "Mined-out ore did not weaken the deposit's core: "
                        + fullCore.reflectivity()
                        + " -> "
                        + minedCore.reflectivity());
        helper.assertTrue(
                full.getLast().equals(mined.getLast()),
                "Mining changed the altered halo, which keeps no ore");
        // Remaining over initial ore is read back from the loaded chunk data: 30 of 100 units.
        RockProperties host = NaturalGeology.host(setting, deposit.center().getY() + 0.5D);
        double expected =
                SeismicMath.reflectionCoefficient(
                        host,
                        host.mixedWith(
                                RockPhysics.forOre(deposit.oreId()),
                                DepositBodies.CORE_GRADE * 0.3D));
        helper.assertTrue(
                Math.abs(minedCore.reflectivity() - expected) < 1.0E-6D,
                "The core did not use the real remaining ore: " + minedCore.reflectivity());
        for (BlockPos pos : blocks) {
            OreDepositData.remove(level, pos);
            level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        }
        helper.succeed();
    }

    // ------------------------------------------------------------------------------------------
    // 7. Saving and loading survey data

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void surveyArchiveAndFieldDataSurviveSaving(GameTestHelper helper)
            throws Exception {
        var registries = helper.getLevel().registryAccess();
        ResourceLocation gold = Blocks.GOLD_ORE.builtInRegistryHolder().key().location();
        RockProperties goldMineral = RockPhysics.forOre(gold);
        List<SubsurfaceBody> bodies =
                DepositBodies.bodies(
                        9L, 3, 40, 20, -30, 25, 18, 6, goldMineral, RockCatalog.STONE, 1);
        SurveyInput input =
                new SurveyInput(
                        SurveyMode.DETAILED,
                        0.5D,
                        70,
                        0.5D,
                        512,
                        -59,
                        NaturalGeology.Setting.OVERWORLD,
                        new double[] {120.5D},
                        new double[] {-80.5D},
                        1.25D,
                        true,
                        bodies,
                        NaturalGeology.horizons(NaturalGeology.Setting.OVERWORLD, -64),
                        null,
                        4242L,
                        3);
        SeismicForwardModel.Output output = SeismicForwardModel.run(input, () -> false);
        SurveyMap map = output.map();
        List<SurveyTarget> targets =
                SurveyInterpretation.interpret(
                        input,
                        output,
                        java.util.Map.of(
                                9L,
                                new SurveyInterpretation.DepositOre(gold.toString(), goldMineral)),
                        OreSignatureTable.forAnalysis(Level.OVERWORLD.location()));
        helper.assertTrue(!targets.isEmpty(), "The survey found no target to save");
        SurveyRecord record =
                new SurveyRecord(
                        0,
                        Level.OVERWORLD.location(),
                        1234L,
                        99L,
                        SurveyMode.DETAILED,
                        0.5D,
                        70,
                        0.5D,
                        512,
                        -59,
                        NaturalGeology.Setting.OVERWORLD,
                        1.25D,
                        true,
                        4242L,
                        new double[] {120.5D},
                        new double[] {-80.5D},
                        3,
                        map,
                        targets);
        var archive = SurveyArchive.load(new CompoundTag(), registries);
        SurveyRecord stored = archive.add(record, 8);
        var restored =
                SurveyArchive.load(
                        roundTrip(archive.save(new CompoundTag(), registries)), registries);
        SurveyRecord loaded = restored.byId(stored.id());
        helper.assertTrue(loaded != null, "Saved survey is missing");
        helper.assertTrue(
                loaded.targets().equals(targets)
                        && loaded.level() == 3
                        && loaded.configHash() == 99L
                        && loaded.noiseSeed() == 4242L
                        && loaded.wellTie()
                        && Arrays.equals(loaded.geophoneX(), record.geophoneX())
                        && loaded.map().metrics().equals(map.metrics())
                        && Arrays.equals(loaded.map().depthY(), map.depthY())
                        && Arrays.equals(loaded.map().attenuation(), map.attenuation()),
                "Survey configuration, targets or attributes changed on save");
        for (int i = 0; i < map.size(); i++) {
            helper.assertTrue(
                    map.inside(i) == loaded.map().inside(i)
                            && Math.abs(map.intensity(i) - loaded.map().intensity(i)) < 0.02D
                            && Math.abs(map.sigma()[i] - loaded.map().sigma()[i])
                                    <= map.sigma()[i] * 0.001D,
                    "A pixel lost precision on save");
        }
        helper.assertTrue(
                Math.signum(map.amplitude()[map.indexAt(40, -30)])
                        == Math.signum(loaded.map().amplitude()[map.indexAt(40, -30)]),
                "Polarity changed on save");

        var field = GeologySavedData.load(new CompoundTag(), registries);
        field.addGeophone(new BlockPos(10, 64, -3));
        field.addGeophone(new BlockPos(-400, 70, 900));
        field.addBorehole(
                new Borehole(
                        new BlockPos(5, 60, 5),
                        -40,
                        77L,
                        Blocks.GOLD_ORE.builtInRegistryHolder().key().location(),
                        12,
                        9,
                        340,
                        true));
        var reloaded =
                GeologySavedData.load(
                        roundTrip(field.save(new CompoundTag(), registries)), registries);
        helper.assertTrue(
                reloaded.geophonesWithin(BlockPos.ZERO, 2000)
                                .equals(field.geophonesWithin(BlockPos.ZERO, 2000))
                        && reloaded.boreholesWithin(BlockPos.ZERO, 100)
                                .equals(field.boreholesWithin(BlockPos.ZERO, 100)),
                "Geophones or boreholes changed on save");
        helper.succeed();
    }

    private static CompoundTag roundTrip(CompoundTag tag) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        NbtIo.write(tag, new DataOutputStream(bytes));
        return NbtIo.read(
                new DataInputStream(new ByteArrayInputStream(bytes.toByteArray())),
                NbtAccounter.unlimitedHeap());
    }

    // ------------------------------------------------------------------------------------------
    // 8. A survey interrupted by a restart resumes and finishes with the same result

    @GameTest(template = "empty", timeoutTicks = 20 * 60 * 4)
    public static void stationResumesItsSurveyAfterARestart(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // High above the test grid so the 3x3x3 body and its floor touch no other test.
        BlockPos origin = helper.absolutePos(new BlockPos(2, 30, 2));
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                level.setBlock(origin.offset(dx, -1, dz), Blocks.STONE.defaultBlockState(), 3);
            }
        }
        level.setBlock(origin, ModBlocks.SEISMIC_STATION.get().defaultBlockState(), 3);
        ModBlocks.SEISMIC_STATION_STRUCTURE.placeParts(level, origin);
        var station = (SeismicStationBlockEntity) level.getBlockEntity(origin);
        station.energyStorage().receiveEnergy(station.energyCapacity(), false);
        helper.assertTrue(station.startSurvey(SurveyMode.DETAILED), "Station refused to start");
        UUID id = station.stationId();
        int before = SurveyArchive.get(level.getServer(), id).records().size();
        AtomicBoolean restarted = new AtomicBoolean();
        AtomicReference<SurveyJob> reference = new AtomicReference<>();
        helper.succeedWhen(
                () -> {
                    if (!restarted.get()) {
                        helper.assertTrue(
                                station.state() == StationState.PULSING, "Still starting");
                        // What a restart leaves: the saved block entity and no running survey.
                        CompoundTag saved = station.saveWithFullMetadata(level.registryAccess());
                        SeismicSurveyService.release(level, id);
                        var reloaded =
                                new SeismicStationBlockEntity(origin, station.getBlockState());
                        reloaded.loadWithComponents(saved, level.registryAccess());
                        helper.assertTrue(
                                reloaded.state() == StationState.PULSING
                                        && reloaded.mode() == SurveyMode.DETAILED
                                        && reloaded.stationId().equals(id)
                                        && reloaded.pulseTicks() == station.pulseTicks()
                                        && reloaded.energyStored() == station.energyStored(),
                                "The saved station lost its survey state");
                        restarted.set(true);
                        helper.fail("Restarted");
                    }
                    helper.assertTrue(
                            station.status() == SeismicStationBlockEntity.STATUS_FINISHED,
                            "Survey still running: " + station.state());
                    List<SurveyRecord> records = SurveyArchive.get(level.getServer(), id).records();
                    helper.assertTrue(
                            records.size() == before + 1, "Finished survey was not saved");
                    SurveyRecord last = records.getLast();
                    if (reference.get() == null) {
                        // The same configuration, uninterrupted, must give the stored result.
                        reference.set(
                                SeismicSurveyService.submit(
                                        level,
                                        new SurveyRequest(
                                                UUID.nameUUIDFromBytes(id.toString().getBytes()),
                                                level.dimension().location(),
                                                level.getSeed(),
                                                SurveyMode.DETAILED,
                                                origin,
                                                (int) last.radius(),
                                                station.geophones(),
                                                last.environment(),
                                                last.wellTie(),
                                                last.level())));
                    }
                    SurveyJob fresh = reference.get();
                    SeismicSurveyService.find(level, fresh.request().stationId());
                    helper.assertTrue(fresh.isDone(), "Reference survey running");
                    SurveyMap expected = fresh.result().map();
                    helper.assertTrue(
                            fresh.request().configHash() == last.configHash(),
                            "The resumed survey changed its configuration");
                    for (int i = 0; i < expected.size(); i++) {
                        helper.assertTrue(
                                Math.abs(expected.intensity(i) - last.map().intensity(i)) < 0.02D,
                                "A resumed survey differs from an uninterrupted one");
                    }
                    SeismicSurveyService.release(level, fresh.request().stationId());
                    ModBlocks.SEISMIC_STATION_STRUCTURE.removeParts(level, origin);
                    level.setBlock(origin, Blocks.AIR.defaultBlockState(), 3);
                });
    }

    // ------------------------------------------------------------------------------------------
    // Field equipment: geophone registry and the exploration drill

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void geophonesRegisterThemselvesAndStaleRecordsAreDropped(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        BlockPos ground = helper.absolutePos(new BlockPos(1, 1, 1));
        BlockPos phone = ground.above();
        level.setBlock(ground, Blocks.STONE.defaultBlockState(), 3);
        level.setBlock(phone, ModBlocks.GEOPHONE.get().defaultBlockState(), 3);
        GeologySavedData data = GeologySavedData.get(level);
        helper.assertTrue(
                data.geophonesWithin(phone, 1).contains(phone),
                "A placed geophone did not register");
        level.setBlock(ground, Blocks.AIR.defaultBlockState(), 3);
        helper.assertTrue(
                !level.getBlockState(phone).is(ModBlocks.GEOPHONE.get())
                        && !data.geophonesWithin(phone, 1).contains(phone),
                "A geophone without support stayed or stayed registered");
        // A record whose block vanished without the block's own removal hook.
        BlockPos stale = phone.east(2);
        data.addGeophone(stale);
        helper.assertTrue(
                SeismicStationBlockEntity.linkedGeophones(level, phone, 64).stream()
                                .noneMatch(stale::equals)
                        && !data.geophonesWithin(stale, 1).contains(stale),
                "A loaded position without a geophone was still linked");
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 20 * 30)
    public static void explorationDrillLogsTheCoreAndEstimatesOnlyNearby(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        // High above the test grid: the fixture is wider than the test's own columns.
        BlockPos drillPos = helper.absolutePos(new BlockPos(2, 100, 2));
        int oreY = drillPos.getY() - 5;
        List<BlockPos> ore = new ArrayList<>();
        for (int y = drillPos.getY() - 1; y > drillPos.getY() - 12; y--) {
            for (int dx = -6; dx <= 6; dx++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos pos = new BlockPos(drillPos.getX() + dx, y, drillPos.getZ() + dz);
                    boolean body = (y == oreY || y == oreY - 1) && dx * dx + dz * dz <= 36;
                    level.setBlock(
                            pos,
                            body
                                    ? ModBlocks.ORE_DEPOSIT.get().defaultBlockState()
                                    : Blocks.STONE.defaultBlockState(),
                            Block.UPDATE_CLIENTS);
                    if (body) {
                        OreDepositData.set(
                                level,
                                pos,
                                OreDepositStonePalette.indexOf(level, Blocks.STONE),
                                OreDepositOrePalette.indexOf(level, Blocks.GOLD_ORE),
                                10,
                                0,
                                0,
                                OreDepositTier.LARGE.index());
                        ore.add(pos);
                    }
                }
            }
        }
        BlockPos basicPos = drillPos.offset(2, 0, 0);
        level.setBlock(drillPos.below(12), Blocks.BEDROCK.defaultBlockState(), 3);
        level.setBlock(basicPos.below(12), Blocks.BEDROCK.defaultBlockState(), 3);
        level.setBlock(
                drillPos,
                ModBlocks.EXPLORATION_DRILL
                        .get()
                        .defaultBlockState()
                        .setValue(ExplorationDrillBlock.LEVEL, ScannerUpgrade.MAX_LEVEL),
                3);
        level.setBlock(basicPos, ModBlocks.EXPLORATION_DRILL.get().defaultBlockState(), 3);
        var drill = (ExplorationDrillBlockEntity) level.getBlockEntity(drillPos);
        var basic = (ExplorationDrillBlockEntity) level.getBlockEntity(basicPos);
        drill.energyStorage().receiveEnergy(drill.energyCapacity(), false);
        basic.energyStorage().receiveEnergy(basic.energyCapacity(), false);
        helper.assertTrue(drill.start() && basic.start(), "A drill refused to start");
        helper.succeedWhen(
                () -> {
                    helper.assertTrue(
                            drill.state() == ExplorationDrillBlockEntity.STATE_DONE
                                    && basic.state() == ExplorationDrillBlockEntity.STATE_DONE,
                            "Drilling");
                    BoreholeLog basicLog =
                            basic.output().getItem(0).get(ModDataComponents.BOREHOLE_LOG.get());
                    helper.assertTrue(
                            basicLog != null
                                    && !basicLog.analysis().isEmpty()
                                    && basicLog.analysis().stream()
                                            .allMatch(candidate -> candidate.oreId().isEmpty()),
                            "A level 0 analyser named the ore");
                    ItemStack sample = drill.output().getItem(0);
                    BoreholeLog log = sample.get(ModDataComponents.BOREHOLE_LOG.get());
                    helper.assertTrue(log != null, "No core sample");
                    helper.assertTrue(
                            log.ore().isPresent()
                                    && log.ore()
                                            .get()
                                            .equals(
                                                    Blocks.GOLD_ORE
                                                            .builtInRegistryHolder()
                                                            .key()
                                                            .location())
                                    && log.oreTopY() == oreY
                                    && log.oreBottomY() == oreY - 1
                                    && log.industrial(),
                            "The core did not confirm the ore and its depth");
                    System.out.println("Level 4 core analysis: " + log.analysis());
                    helper.assertTrue(
                            log.level() == ScannerUpgrade.MAX_LEVEL
                                    && !log.analysis().isEmpty()
                                    && log.analysis()
                                            .getFirst()
                                            .oreId()
                                            .equals(
                                                    Blocks.GOLD_ORE
                                                            .builtInRegistryHolder()
                                                            .key()
                                                            .location()
                                                            .toString()),
                            "A fully upgraded analyser did not name the ore first");
                    int total = ore.size() * 10;
                    int nearby = 0;
                    for (BlockPos pos : ore) {
                        int dx = pos.getX() - drillPos.getX();
                        int dz = pos.getZ() - drillPos.getZ();
                        if (dx * dx + dz * dz
                                <= ExplorationDrillBlockEntity.ESTIMATE_RADIUS
                                        * ExplorationDrillBlockEntity.ESTIMATE_RADIUS) {
                            nearby += 10;
                        }
                    }
                    helper.assertTrue(
                            log.localUnits() == nearby && log.localUnits() < total,
                            "The core estimate was not limited to the probed volume: "
                                    + log.localUnits()
                                    + " of "
                                    + total);
                    helper.assertTrue(
                            GeologySavedData.get(level).boreholesWithin(drillPos, 1).stream()
                                    .anyMatch(Borehole::found),
                            "The borehole was not recorded for the stations");
                    for (BlockPos pos : ore) {
                        OreDepositData.remove(level, pos);
                    }
                    for (int y = drillPos.getY(); y >= drillPos.getY() - 12; y--) {
                        for (int dx = -6; dx <= 6; dx++) {
                            for (int dz = -6; dz <= 6; dz++) {
                                level.setBlock(
                                        new BlockPos(drillPos.getX() + dx, y, drillPos.getZ() + dz),
                                        Blocks.AIR.defaultBlockState(),
                                        Block.UPDATE_CLIENTS);
                            }
                        }
                    }
                });
    }
}

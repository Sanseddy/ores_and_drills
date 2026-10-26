package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.registry.ModAttachments;
import dev.registry.ModBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.MultiNoiseBiomeSource;
import net.minecraft.world.level.biome.MultiNoiseBiomeSourceParameterLists;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.dimension.DimensionType;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.Function;

/**
 * Statistical checks of automatic TINY/SMALL generation on real vanilla ore features. The overworld check
 * runs the real placed features on the real overworld noise biomes/terrain at the attempt-stream level; the
 * Nether check generates fresh chunks through the normal pipeline and compares the diagnostics counters and
 * chunk data with an independent recomputation of the same attempt stream.
 */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SmallDepositAttemptGameTests {
    private static final long OVERWORLD_SEED = 20261017L;
    private static final double RETENTION = OreDepositConfig.SMALL_DEPOSIT_ATTEMPT_RETENTION;
    private static final double TINY_SHARE = OreDepositConfig.SMALL_DEPOSIT_TINY_SHARE;
    /** The former planner owned at most one TINY per chunk and one SMALL per 2x2 chunks per material. */
    private static final double OLD_CANDIDATE_CAP_PER_CHUNK = 1.0D + 0.25D;

    private static final class Stats {
        long source, kept, tiny, small, sourceHost, keptHost, chunksOverOldTinyCap;
        double sourceY, sourceY2, keptY;
        final Map<String, long[]> biomes = new TreeMap<>();
        final long[] sourceBins = new long[32];
        final long[] keptBins = new long[32];
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "small_deposit_statistics")
    public static void overworldAttemptStreamKeepsSourceDistributions(GameTestHelper helper) throws Exception {
        ServerLevel level = helper.getLevel();
        var registries = level.registryAccess();
        var settings = registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        var biomeSource = MultiNoiseBiomeSource.createFromPreset(registries
                .lookupOrThrow(Registries.MULTI_NOISE_BIOME_SOURCE_PARAMETER_LIST)
                .getOrThrow(MultiNoiseBiomeSourceParameterLists.OVERWORLD));
        var generator = new NoiseBasedChunkGenerator(biomeSource, settings);
        RandomState randomState = RandomState.create(settings.value(), registries.lookupOrThrow(Registries.NOISE), OVERWORLD_SEED);
        Set<ResourceLocation> overworldBiomes = new HashSet<>();
        biomeSource.possibleBiomes().forEach(holder -> holder.unwrapKey().ifPresent(key -> overworldBiomes.add(key.location())));

        Map<String, Map<String, Map<ResourceLocation, OreDepositFeature.Configuration>>> sources = groupSources(
                config -> config.sourceBiome().filter(overworldBiomes::contains).isPresent());
        helper.assertTrue(sources.containsKey("iron") && sources.containsKey("coal") && sources.containsKey("diamond"),
                "Vanilla overworld ore sources were not registered: " + sources.keySet());

        Map<String, Double> survey = SmallDepositAttemptPlanner.surveyAttemptsPerChunk(level, generator,
                pos -> biomeSource.getNoiseBiome(QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                        QuartPos.fromBlock(pos.getZ()), randomState.sampler()).unwrapKey().map(key -> key.location()).orElse(null),
                OVERWORLD_SEED, "minecraft:overworld");
        Function<String, Double> overworldWallChance = material ->
                OreDepositFeature.smallDepositWallChance(SmallDepositAttemptPlanner.relative(survey, material));
        Map<String, Stats> stats = new TreeMap<>();
        int chunks = 0;
        for (int gx = 0; gx < 20; gx++) {
            for (int gz = 0; gz < 20; gz++) {
                ChunkPos chunk = new ChunkPos(gx * 9 - 90, gz * 9 - 90);
                chunks++;
                List<CompactDepositGeometry.Cell> cells = new ArrayList<>();
                List<HostCheck> checks = new ArrayList<>();
                Map<String, Integer> chunkTiny = new HashMap<>();
                BlockPos chunkOrigin = new BlockPos(chunk.getMinBlockX(), 0, chunk.getMinBlockZ());
                for (var material : sources.entrySet()) {
                    Stats s = stats.computeIfAbsent(material.getKey(), ignored -> new Stats());
                    for (var source : material.getValue().entrySet()) {
                        var first = source.getValue().values().iterator().next();
                        long chain = SmallDepositAttemptMath.chainSeed(OVERWORLD_SEED, "minecraft:overworld",
                                source.getKey(), chunk.x, chunk.z);
                        var stream = SmallDepositAttemptPlanner.evaluate(level, generator, chunk,
                                first.vein().orElseThrow().sourcePlacement(), chain, source.getKey());
                        helper.assertFalse(stream.failed(), "Chain failed for " + source.getKey());
                        for (int index = 0; index < stream.positions().size(); index++) {
                            BlockPos pos = stream.positions().get(index);
                            ResourceLocation biome = biomeSource.getNoiseBiome(QuartPos.fromBlock(pos.getX()),
                                    QuartPos.fromBlock(pos.getY()), QuartPos.fromBlock(pos.getZ()), randomState.sampler())
                                    .unwrapKey().map(key -> key.location()).orElse(null);
                            var config = source.getValue().get(biome);
                            if (config == null) continue;
                            var decision = SmallDepositAttemptMath.decide(
                                    SmallDepositAttemptMath.attemptSeed(chain, index, pos.asLong()),
                                    config.sourceProbability(), RETENTION, TINY_SHARE);
                            if (!decision.sourceAttempt()) continue;
                            boolean kept = decision.candidates() > 0;
                            s.source++;
                            s.sourceY += pos.getY();
                            s.sourceY2 += (double) pos.getY() * pos.getY();
                            int bin = Math.floorMod(Math.floorDiv(pos.getY() + 64, 16), 32);
                            s.sourceBins[bin]++;
                            long[] biomeCounts = s.biomes.computeIfAbsent(biome.toString(), ignored -> new long[2]);
                            biomeCounts[0]++;
                            if (kept) {
                                s.kept++;
                                s.keptY += pos.getY();
                                s.keptBins[bin]++;
                                biomeCounts[1]++;
                                if (decision.tiny()) {
                                    s.tiny++;
                                    chunkTiny.merge(material.getKey(), decision.candidates(), Integer::sum);
                                } else {
                                    s.small++;
                                }
                            }
                            cells.add(new CompactDepositGeometry.Cell(pos.getX() - chunkOrigin.getX(), pos.getY(),
                                    pos.getZ() - chunkOrigin.getZ(), 1));
                            checks.add(new HostCheck(s, kept, config.targets()));
                        }
                    }
                }
                chunkTiny.forEach((material, count) -> {
                    if (count > 1) stats.get(material).chunksOverOldTinyCap++;
                });
                BlockState[] states = CompactDepositTerrain.sample(generator, randomState, level, chunkOrigin, cells);
                RandomSource random = RandomSource.create(chunk.toLong());
                for (int index = 0; index < cells.size(); index++) {
                    HostCheck check = checks.get(index);
                    if (!isHost(states[index], check.targets(), random)) continue;
                    check.stats().sourceHost++;
                    if (check.kept()) check.stats().keptHost++;
                }
            }
        }

        List<String> report = new ArrayList<>();
        report.add(String.format(Locale.ROOT, "Overworld attempt stream, seed=%d, %d chunks on a 9-chunk lattice,"
                + " retention=%.2f, TINY share=%.2f", OVERWORLD_SEED, chunks, RETENTION, TINY_SHARE));
        report.add("material | source attempts/chunk | kept/chunk | kept share | TINY share of kept"
                + " | mean Y source -> kept | host rock at origin source -> kept | old cap/chunk"
                + " | chunks over old TINY cap | cave-wall chance");
        long totalSource = 0, totalKept = 0, totalTiny = 0;
        for (var entry : stats.entrySet()) {
            Stats s = entry.getValue();
            if (s.source == 0) continue;
            totalSource += s.source;
            totalKept += s.kept;
            totalTiny += s.tiny;
            double keptShare = s.kept / (double) s.source;
            double meanSource = s.sourceY / s.source;
            double meanKept = s.kept == 0 ? Double.NaN : s.keptY / s.kept;
            double sd = Math.sqrt(Math.max(0.0D, s.sourceY2 / s.source - meanSource * meanSource));
            double hostSource = s.sourceHost / (double) s.source;
            double hostKept = s.kept == 0 ? Double.NaN : s.keptHost / (double) s.kept;
            report.add(String.format(Locale.ROOT, "%s | %.3f | %.3f | %.4f | %.4f | %.1f -> %.1f | %.4f -> %.4f | %.2f | %d | %.3f",
                    entry.getKey(), s.source / (double) chunks, s.kept / (double) chunks, keptShare,
                    s.kept == 0 ? 0.0D : s.tiny / (double) s.kept, meanSource, meanKept, hostSource, hostKept,
                    OLD_CANDIDATE_CAP_PER_CHUNK, s.chunksOverOldTinyCap, overworldWallChance.apply(entry.getKey())));
            if (s.source >= 2_000) {
                double tolerance = 4.0D * Math.sqrt(RETENTION * (1 - RETENTION) / s.source) + 0.002D;
                helper.assertTrue(Math.abs(keptShare - RETENTION) <= tolerance,
                        entry.getKey() + " kept " + keptShare + " of its own attempts instead of " + RETENTION);
                helper.assertTrue(Math.abs(meanKept - meanSource) <= 4.0D * sd / Math.sqrt(s.kept) + 0.5D,
                        entry.getKey() + " kept candidates changed the height distribution: "
                                + meanSource + " -> " + meanKept);
                double hostTolerance = 4.0D * Math.sqrt(Math.max(1.0E-4D, hostSource * (1 - hostSource)) / s.kept) + 0.005D;
                helper.assertTrue(Math.abs(hostKept - hostSource) <= hostTolerance,
                        entry.getKey() + " thinning depends on terrain: " + hostSource + " -> " + hostKept);
                long[] ks = s.keptBins, ss = s.sourceBins;
                for (int bin = 0; bin < ss.length; bin++) {
                    if (ss[bin] < 1_000) continue;
                    double share = ks[bin] / (double) ss[bin];
                    helper.assertTrue(Math.abs(share - RETENTION) <= 4.0D * Math.sqrt(RETENTION * (1 - RETENTION) / ss[bin]) + 0.002D,
                            entry.getKey() + " height band " + (bin * 16 - 64) + " kept " + share);
                }
            }
            for (var biome : s.biomes.entrySet()) {
                if (biome.getValue()[0] >= 1_000) {
                    double share = biome.getValue()[1] / (double) biome.getValue()[0];
                    helper.assertTrue(Math.abs(share - RETENTION) <= 4.0D * Math.sqrt(RETENTION * (1 - RETENTION)
                                    / biome.getValue()[0]) + 0.002D,
                            entry.getKey() + " biome " + biome.getKey() + " kept " + share);
                }
            }
        }
        double keptShare = totalKept / (double) totalSource;
        double tinyShare = totalTiny / (double) totalKept;
        report.add(String.format(Locale.ROOT, "TOTAL | source=%d | kept=%d (%.4f) | TINY=%.4f of kept, %.4f of source"
                        + " | SMALL=%.4f of source | skipped=%.4f",
                totalSource, totalKept, keptShare, tinyShare, totalTiny / (double) totalSource,
                (totalKept - totalTiny) / (double) totalSource, 1 - keptShare));
        // Retention was cut 32x, so even coal keeps fewer candidates per chunk than the former cap allowed on
        // average; the cap itself shows as the absence of chunks holding more than one TINY of a material.
        for (String material : List.of("coal", "iron")) {
            Stats s = stats.get(material);
            helper.assertTrue(s != null && s.chunksOverOldTinyCap > 0,
                    material + " still limited by the former one-TINY-per-chunk cap");
        }
        for (String material : List.of("emerald", "gold", "diamond", "lapis", "redstone")) {
            Stats s = stats.get(material);
            if (s == null) continue;
            report.add(material + " biomes kept/source: " + s.biomes.entrySet().stream()
                    .filter(entry -> entry.getValue()[0] >= 200)
                    .map(entry -> entry.getKey() + "=" + entry.getValue()[1] + "/" + entry.getValue()[0])
                    .toList());
        }
        helper.assertTrue(Math.abs(keptShare - RETENTION) <= 0.005D, "Overall kept share " + keptShare);
        for (String material : stats.keySet()) {
            report.add(String.format(Locale.ROOT, "rarity %s: survey attempts/chunk %.3f, relative %.5f -> cave-wall chance %.3f",
                    material, survey.getOrDefault(material, 0.0D), SmallDepositAttemptPlanner.relative(survey, material),
                    overworldWallChance.apply(material)));
        }
        write("small-deposit-overworld-report.txt", report);
        // Rarer ores reach cave walls less often: common starter ores must clearly beat the rarest ones.
        for (String common : List.of("coal", "iron")) {
            for (String rare : List.of("diamond", "emerald")) {
                if (stats.containsKey(common) && stats.containsKey(rare)) {
                    helper.assertTrue(overworldWallChance.apply(common) > overworldWallChance.apply(rare),
                            common + " must reach cave walls more often than " + rare);
                }
            }
        }
        helper.assertTrue(Math.abs(tinyShare - TINY_SHARE)
                <= 4.0D * Math.sqrt(TINY_SHARE * (1 - TINY_SHARE) / totalKept) + 0.002D, "TINY share " + tinyShare);
        write("small-deposit-overworld-report.txt", report);
        helper.succeed();
    }

    private static double netherWallChance(ServerLevel nether, String material) {
        return OreDepositFeature.smallDepositWallChance(nether, material);
    }

    private record HostCheck(Stats stats, boolean kept, List<OreConfiguration.TargetBlockState> targets) {
    }

    private static boolean isHost(BlockState state, List<OreConfiguration.TargetBlockState> targets, RandomSource random) {
        if (state == null || !state.is(ModBlockTags.STONES) || !state.getFluidState().isEmpty()) return false;
        for (OreConfiguration.TargetBlockState target : targets) {
            if (target.target.test(state, random)) return true;
        }
        return false;
    }

    @GameTest(template = "empty", timeoutTicks = 6000, batch = "small_deposit_generation")
    public static void netherGenerationMatchesAttemptStreamAndReserves(GameTestHelper helper) throws Exception {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(nether != null && nether.getChunkSource().getGenerator() instanceof NoiseBasedChunkGenerator,
                "Nether noise terrain is unavailable");
        // Retention was cut 32x; 24x24 chunks keep enough candidates for the checks below.
        int baseX = 6_000, baseZ = -6_000, size = 24;
        int window = 4;
        // A reloaded test world already holds earlier areas on disk, and loading those chunks back as neighbours
        // is not a fresh decoration. Fixed areas keep every run reproducible; the first untouched one is used.
        Path regions = DimensionType.getStorageFolder(nether.dimension(),
                nether.getServer().getWorldPath(LevelResource.ROOT)).resolve("region");
        while (!untouchedRegions(regions, baseX - window - 16, baseZ - window - 16, size + 2 * window + 32)) {
            baseX += 128;
        }
        Set<Long> decoratedBefore = decorated(nether, baseX - window, baseZ - window, size + 2 * window);
        SmallDepositDiagnostics.reset();
        for (int x = baseX; x < baseX + size; x++) {
            for (int z = baseZ; z < baseZ + size; z++) {
                nether.getChunk(x, z);
            }
        }
        Set<Long> decoratedNow = decorated(nether, baseX - window, baseZ - window, size + 2 * window);
        decoratedNow.removeAll(decoratedBefore);
        helper.assertTrue(decoratedNow.size() >= size * size, "Too few chunks were decorated: " + decoratedNow.size());

        var sources = groupSources(config -> config.sourceBiome()
                .map(id -> id.getPath().contains("nether") || id.getPath().contains("basalt")
                        || id.getPath().contains("soul") || id.getPath().contains("crimson")
                        || id.getPath().contains("warped")).orElse(false));
        Map<String, long[]> expected = new TreeMap<>();
        for (long key : decoratedNow) {
            ChunkPos chunk = new ChunkPos(key);
            for (var material : sources.entrySet()) {
                long[] counts = expected.computeIfAbsent(material.getKey(), ignored -> new long[2]);
                for (var source : material.getValue().entrySet()) {
                    var first = source.getValue().values().iterator().next();
                    long chain = SmallDepositAttemptMath.chainSeed(nether.getSeed(), "minecraft:the_nether",
                            source.getKey(), chunk.x, chunk.z);
                    var stream = SmallDepositAttemptPlanner.evaluate(nether, nether.getChunkSource().getGenerator(),
                            chunk, first.vein().orElseThrow().sourcePlacement(), chain, source.getKey());
                    for (int index = 0; index < stream.positions().size(); index++) {
                        BlockPos pos = stream.positions().get(index);
                        ResourceLocation biome = nether.getBiome(pos).unwrapKey().map(k -> k.location()).orElse(null);
                        var config = source.getValue().get(biome);
                        if (config == null) continue;
                        double lambda = SmallDepositAttemptMath.expectedCandidates(RETENTION,
                                OreDepositFeature.liveFrequencyMultiplierFor(config.targets().getFirst().state.getBlock()));
                        var decision = SmallDepositAttemptMath.decide(
                                SmallDepositAttemptMath.attemptSeed(chain, index, pos.asLong()),
                                config.sourceProbability(), lambda, TINY_SHARE);
                        if (!decision.sourceAttempt()) continue;
                        counts[0]++;
                        counts[1] += decision.candidates();
                    }
                }
            }
        }

        long entries = 0, amountSum = 0, nonPositive = 0;
        for (int x = baseX - window - 1; x < baseX + size + window + 1; x++) {
            for (int z = baseZ - window - 1; z < baseZ + size + window + 1; z++) {
                ChunkAccess chunk = nether.getChunk(x, z, ChunkStatus.EMPTY, false);
                if (chunk == null) continue;
                var data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
                if (data == null) continue;
                long[] counters = new long[3];
                data.forEach(x, z, (pos, entry) -> {
                    if (entry.tier() >= OreDepositFeature.TIER_MEDIUM) return;
                    counters[0]++;
                    counters[1] += entry.initialOre();
                    if (entry.initialOre() <= 0) counters[2]++;
                });
                entries += counters[0];
                amountSum += counters[1];
                nonPositive += counters[2];
            }
        }

        List<String> report = new ArrayList<>();
        report.add("Nether end-to-end generation: " + decoratedNow.size() + " freshly decorated chunks near chunk "
                + baseX + "," + baseZ + " (seed " + nether.getSeed() + ")");
        long source = 0, candidates = 0, placedTiny = 0, placedSmall = 0, blocks = 0, reserve = 0, wall = 0, visible = 0;
        for (var entry : SmallDepositDiagnostics.snapshot().entrySet()) {
            if (!entry.getKey().startsWith("minecraft:the_nether|")) continue;
            var snapshot = entry.getValue();
            report.add(SmallDepositDiagnostics.format(entry.getKey(), snapshot));
            String material = entry.getKey().substring(entry.getKey().indexOf('|') + 1);
            long[] wanted = expected.getOrDefault(material, new long[2]);
            if (OreDepositConfig.SMALL_DEPOSIT_PLACEMENT.get() == OreDepositConfig.SmallDepositPlacement.CAVE_WALL
                    && snapshot.candidates() > 0) {
                double chance = netherWallChance(nether, material);
                double rolled = snapshot.wallRolls() / (double) snapshot.candidates();
                report.add(String.format(Locale.ROOT, "  %s: cave-wall chance %.3f, rolled %.3f of %d candidates",
                        material, chance, rolled, snapshot.candidates()));
                helper.assertTrue(Math.abs(rolled - chance)
                                <= 4.0D * Math.sqrt(chance * (1 - chance) / snapshot.candidates()) + 0.01D,
                        material + " chose cave walls " + rolled + " instead of " + chance);
            }
            helper.assertTrue(wanted[0] == snapshot.sourceAttempts() && wanted[1] == snapshot.candidates(),
                    material + ": generation counted " + snapshot.sourceAttempts() + "/" + snapshot.candidates()
                            + " source attempts/candidates, recomputed stream " + wanted[0] + "/" + wanted[1]);
            source += snapshot.sourceAttempts();
            candidates += snapshot.candidates();
            placedTiny += snapshot.placedTiny();
            placedSmall += snapshot.placedSmall();
            blocks += snapshot.oreBlocks();
            reserve += snapshot.totalReserve();
            wall += snapshot.wallAnchoredDeposits();
            visible += snapshot.visibleDeposits();
        }
        long placed = placedTiny + placedSmall;
        helper.assertTrue(netherWallChance(nether, "quartz") > netherWallChance(nether, "netherite_scrap"),
                "Common quartz must reach cave walls more often than rare ancient debris");
        report.add(String.format(Locale.ROOT, "TOTAL source=%d candidates=%d (%.4f) placed tiny=%d small=%d"
                        + " (on cave walls %.1f%%, visible from air %.1f%%, placement=%s)"
                        + " blocks=%d reserve=%d; chunk data entries=%d reserve=%d non-positive=%d",
                source, candidates, candidates / (double) Math.max(1, source), placedTiny, placedSmall,
                wall * 100.0D / Math.max(1, placed), visible * 100.0D / Math.max(1, placed),
                OreDepositConfig.SMALL_DEPOSIT_PLACEMENT.get(), blocks, reserve, entries, amountSum, nonPositive));
        if (OreDepositConfig.SMALL_DEPOSIT_PLACEMENT.get() == OreDepositConfig.SmallDepositPlacement.CAVE_WALL) {
            helper.assertTrue(visible * 2 > placed, "Most TINY/SMALL deposits must be visible on cave walls: "
                    + visible + " of " + placed);
        }
        write("small-deposit-nether-report.txt", report);

        helper.assertTrue(source > 500, "Too few Nether source attempts for a statistical check: " + source);
        helper.assertTrue(Math.abs(candidates / (double) source - RETENTION)
                        <= 4.0D * Math.sqrt(RETENTION * (1 - RETENTION) / source) + 0.002D,
                "Nether kept share " + candidates / (double) source);
        helper.assertTrue(placedTiny > 0 && placedSmall > 0, "No TINY or SMALL deposit was placed");
        helper.assertTrue(nonPositive == 0, nonPositive + " deposit blocks hold no ore");
        helper.assertTrue(entries == blocks, "Chunk data holds " + entries + " TINY/SMALL blocks, counters " + blocks);
        helper.assertTrue(amountSum == reserve, "Chunk data holds " + amountSum + " units, counters " + reserve);
        helper.assertTrue(reserve >= placedTiny * OreDepositConfig.TINY_DEPOSIT_MIN_RESERVE
                        + placedSmall * OreDepositConfig.SMALL_DEPOSIT_MIN_RESERVE
                        && reserve <= placedTiny * OreDepositConfig.TINY_DEPOSIT_MAX_RESERVE
                        + placedSmall * OreDepositConfig.SMALL_DEPOSIT_MAX_RESERVE,
                "Reserve " + reserve + " outside the configured TINY/SMALL ranges");
        helper.assertTrue(blocks <= reserve, "More deposit blocks than reserve units");

        // /locate: a loaded chunk answers with a confirmed block; an unexplored one only with a labelled prediction.
        ResourceLocation quartz = ResourceLocation.withDefaultNamespace("nether_quartz_ore");
        var locator = new DepositLocator(new DepositTerrainValidator());
        BlockPos center = new BlockPos((baseX + size / 2) * 16, 64, (baseZ + size / 2) * 16);
        for (OreDepositTier tier : List.of(OreDepositTier.TINY, OreDepositTier.SMALL)) {
            var confirmed = locator.findNearest(nether, center, quartz, tier, size * 8).orElse(null);
            helper.assertTrue(confirmed != null && confirmed.confirmed(), "No confirmed " + tier + " quartz near generated chunks");
            var entry = nether.getChunkAt(confirmed.candidate().center()).getData(ModAttachments.ORE_DEPOSITS)
                    .get(confirmed.candidate().center());
            helper.assertTrue(entry != null && entry.tier() == tier.index(), "Confirmed result is not a real " + tier + " block");
            BlockPos far = new BlockPos(-40_000, 64, 40_000);
            var predicted = locator.findNearest(nether, far, quartz, tier, 96).orElse(null);
            helper.assertTrue(predicted != null && !predicted.confirmed(), "No labelled prediction in unexplored chunks");
            helper.assertTrue(nether.getChunkSource().getChunkNow(predicted.candidate().sourceChunkX(),
                    predicted.candidate().sourceChunkZ()) == null, "Prediction came from a loaded chunk");
            report.add(tier + " /locate: confirmed " + confirmed.candidate().center() + ", predicted (unverified) "
                    + predicted.candidate().center());
        }
        write("small-deposit-nether-report.txt", report);
        helper.succeed();
    }

    private static boolean untouchedRegions(Path regions, int minChunkX, int minChunkZ, int span) {
        for (int x = Math.floorDiv(minChunkX, 32); x <= Math.floorDiv(minChunkX + span - 1, 32); x++) {
            for (int z = Math.floorDiv(minChunkZ, 32); z <= Math.floorDiv(minChunkZ + span - 1, 32); z++) {
                if (Files.exists(regions.resolve("r." + x + "." + z + ".mca"))) return false;
            }
        }
        return true;
    }

    private static Set<Long> decorated(ServerLevel level, int minX, int minZ, int span) {
        Set<Long> result = new HashSet<>();
        for (int x = minX; x < minX + span; x++) {
            for (int z = minZ; z < minZ + span; z++) {
                ChunkAccess chunk = level.getChunk(x, z, ChunkStatus.EMPTY, false);
                if (chunk != null && chunk.getPersistedStatus().isOrAfter(ChunkStatus.FEATURES)) {
                    result.add(ChunkPos.asLong(x, z));
                }
            }
        }
        return result;
    }

    /** material -> source signature -> biome -> configuration, filtered by source biome. */
    private static Map<String, Map<String, Map<ResourceLocation, OreDepositFeature.Configuration>>> groupSources(
            Function<OreDepositFeature.Configuration, Boolean> filter) {
        Map<String, Map<String, Map<ResourceLocation, OreDepositFeature.Configuration>>> result = new TreeMap<>();
        SmallDepositAttemptPlanner.sourcesByMaterial().forEach((material, configs) -> {
            for (var config : configs) {
                if (!filter.apply(config) || config.vein().isEmpty() || config.sourceBiome().isEmpty()) continue;
                result.computeIfAbsent(material, ignored -> new TreeMap<>())
                        .computeIfAbsent(config.vein().get().sourceSignature(), ignored -> new HashMap<>())
                        .put(config.sourceBiome().get(), config);
            }
        });
        return result;
    }

    private static void write(String name, List<String> lines) throws Exception {
        Files.write(Path.of(name), lines, StandardCharsets.UTF_8);
        OresAndDrillsMod.LOGGER.info("Small-deposit report {}:\n{}", Path.of(name).toAbsolutePath(), String.join("\n", lines));
    }
}

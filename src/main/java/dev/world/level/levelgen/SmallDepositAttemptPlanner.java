package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.registry.ModBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.QuartPos;
import net.minecraft.core.SectionPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.BiomeFilter;
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.HeightRangePlacement;
import net.minecraft.world.level.levelgen.placement.InSquarePlacement;
import net.minecraft.world.level.levelgen.placement.NoiseBasedCountPlacement;
import net.minecraft.world.level.levelgen.placement.NoiseThresholdCountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementContext;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RandomOffsetPlacement;
import net.minecraft.world.level.levelgen.placement.RarityFilter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Reproduces the attempt stream of an original ore placed feature for automatic TINY/SMALL deposits.
 *
 * <p>The original placement modifiers ({@code count}, {@code rarity_filter}, {@code height_range} of any
 * shape, {@code in_square}, modded modifiers) run once per chunk with a random seeded from the world,
 * dimension, chunk and source feature only. {@code biome} is applied to each final position by the caller,
 * like vanilla's {@code BiomeFilter} on the shared original feature. The same stream is therefore produced
 * by generation and by {@code /locate}, independently of feature order and of neighbouring chunks.
 */
public final class SmallDepositAttemptPlanner {
    /** Technical guard against a pathological modded count; vanilla ores stay far below it. */
    static final int MAX_ATTEMPTS_PER_SOURCE_CHUNK = 4_096;
    private static final long EXTRA_ORIGIN_SALT = 0x4558545241504F53L;
    private static final Set<Class<?>> PREDICTABLE_MODIFIERS = Set.of(
            CountPlacement.class, RarityFilter.class, InSquarePlacement.class, HeightRangePlacement.class,
            BiomeFilter.class, RandomOffsetPlacement.class, NoiseBasedCountPlacement.class,
            NoiseThresholdCountPlacement.class
    );
    private static final Map<ResourceLocation, List<OreDepositFeature.Configuration>> SOURCES = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_FAILURES = ConcurrentHashMap.newKeySet();

    private SmallDepositAttemptPlanner() {
    }

    /** Final positions of one source chain in one chunk, before the biome check. */
    record AttemptStream(List<BlockPos> positions, boolean biomeFiltered, boolean inSquare, boolean truncated,
            boolean failed) {
        static final AttemptStream FAILED = new AttemptStream(List.of(), false, false, false, true);
    }

    static void register(ResourceLocation biome, OreDepositFeature.Configuration config) {
        SOURCES.compute(biome, (ignored, old) -> {
            List<OreDepositFeature.Configuration> values = old == null ? new ArrayList<>() : new ArrayList<>(old);
            values.add(config);
            return List.copyOf(values);
        });
    }

    static void clearBiome(ResourceLocation biome) {
        SOURCES.remove(biome);
        RARITY_SURVEYS.clear();
    }

    static AttemptStream evaluate(
            WorldGenLevel level,
            ChunkGenerator generator,
            ChunkPos chunk,
            List<PlacementModifier> placement,
            long chainSeed,
            String sourceSignature
    ) {
        RandomSource random = RandomSource.create(chainSeed);
        PlacementContext context = new PlacementContext(level, generator, Optional.empty());
        List<BlockPos> current = List.of(SectionPos.of(chunk, level.getMinSection()).origin());
        boolean biomeFiltered = false;
        boolean inSquare = false;
        boolean truncated = false;
        try {
            for (PlacementModifier modifier : placement) {
                if (modifier instanceof BiomeFilter) {
                    // Applied per final position by the caller against the source biome.
                    biomeFiltered = true;
                    continue;
                }
                inSquare |= modifier instanceof InSquarePlacement;
                List<BlockPos> next = new ArrayList<>(current.size());
                for (BlockPos pos : current) {
                    var iterator = modifier.getPositions(context, random, pos).iterator();
                    while (iterator.hasNext()) {
                        if (next.size() >= MAX_ATTEMPTS_PER_SOURCE_CHUNK) {
                            truncated = true;
                            break;
                        }
                        next.add(iterator.next().immutable());
                    }
                    if (truncated) {
                        break;
                    }
                }
                current = next;
                if (current.isEmpty()) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            if (LOGGED_FAILURES.add(sourceSignature)) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: placement chain of {} cannot be evaluated for TINY/SMALL deposits",
                        sourceSignature, exception
                );
            }
            return AttemptStream.FAILED;
        }
        return new AttemptStream(List.copyOf(current), biomeFiltered, inSquare, truncated, false);
    }

    /**
     * Origin of an additional candidate when the Frequency slider asks for more than one per attempt. It keeps
     * the attempt's height and, for an {@code in_square} source, re-draws X/Z uniformly inside the same chunk,
     * so the inherited distributions hold without running the source modifiers a second time.
     */
    static BlockPos extraCandidateOrigin(BlockPos attempt, ChunkPos chunk, long candidateSeed, boolean inSquare) {
        if (!inSquare) {
            return attempt;
        }
        long mixed = SeedMixer.mix(candidateSeed ^ EXTRA_ORIGIN_SALT);
        return new BlockPos(chunk.getMinBlockX() + (int) (mixed & 15L), attempt.getY(),
                chunk.getMinBlockZ() + (int) ((mixed >>> 4) & 15L));
    }

    static boolean predictable(List<PlacementModifier> placement) {
        for (PlacementModifier modifier : placement) {
            if (!PREDICTABLE_MODIFIERS.contains(modifier.getClass())) {
                return false;
            }
        }
        return true;
    }

    /** One seed-predicted candidate whose placement has not been verified against real terrain. */
    record Prediction(DepositCandidate candidate, double hostCoverage) {
    }

    /**
     * Seed prediction for an unexplored chunk. Only sources whose modifiers never read generated terrain are
     * predicted; the vein itself still depends on carvers and structures, so callers must label the result as
     * unverified.
     */
    static List<Prediction> predict(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            String materialKey,
            ResourceLocation oreId,
            OreDepositTier tier
    ) {
        if (tier.index() >= OreDepositFeature.TIER_MEDIUM) {
            return List.of();
        }
        Map<String, Map<ResourceLocation, OreDepositFeature.Configuration>> bySource = new TreeMap<>();
        for (Map.Entry<ResourceLocation, List<OreDepositFeature.Configuration>> entry : SOURCES.entrySet()) {
            for (OreDepositFeature.Configuration config : entry.getValue()) {
                if (config.targets().isEmpty() || config.vein().isEmpty()
                        || !materialKey.equals(OreUnifier.materialKeyFor(config.targets().getFirst().state.getBlock()))
                        || dev.config.OreDimensionOverrides.isDisabled(
                                config.targets().getFirst().state.getBlock(), level.dimension().location())) {
                    continue;
                }
                bySource.computeIfAbsent(config.vein().get().sourceSignature(), ignored -> new HashMap<>())
                        .put(entry.getKey(), config);
            }
        }
        if (bySource.isEmpty()) {
            return List.of();
        }
        ChunkGenerator generator = level.getChunkSource().getGenerator();
        RandomState randomState = level.getChunkSource().randomState();
        Map<Long, NoiseColumn> columns = new HashMap<>();
        ChunkPos chunk = new ChunkPos(chunkX, chunkZ);
        String dimension = level.dimension().location().toString();
        List<Prediction> predictions = new ArrayList<>();
        for (Map.Entry<String, Map<ResourceLocation, OreDepositFeature.Configuration>> source : bySource.entrySet()) {
            OreDepositFeature.Configuration first = source.getValue().values().stream()
                    .min(Comparator.comparing(config -> config.sourceBiome().map(Object::toString).orElse("")))
                    .orElseThrow();
            List<PlacementModifier> placement = first.vein().get().sourcePlacement();
            if (!predictable(placement)) {
                continue;
            }
            long chainSeed = SmallDepositAttemptMath.chainSeed(level.getSeed(), dimension, source.getKey(), chunkX, chunkZ);
            AttemptStream stream = evaluate(level, generator, chunk, placement, chainSeed, source.getKey());
            for (int index = 0; index < stream.positions().size(); index++) {
                BlockPos pos = stream.positions().get(index);
                OreDepositFeature.Configuration config = source.getValue().get(noiseBiome(level, pos));
                if (config == null) {
                    continue;
                }
                double lambda = SmallDepositAttemptMath.expectedCandidates(
                        OreDepositConfig.SMALL_DEPOSIT_ATTEMPT_RETENTION,
                        OreDepositFeature.liveFrequencyMultiplierFor(config.targets().getFirst().state.getBlock()));
                long attemptSeed = SmallDepositAttemptMath.attemptSeed(chainSeed, index, pos.asLong());
                var decision = SmallDepositAttemptMath.decide(attemptSeed, config.sourceProbability(), lambda,
                        OreDepositConfig.SMALL_DEPOSIT_TINY_SHARE);
                OreDepositTier decided = decision.tiny() ? OreDepositTier.TINY : OreDepositTier.SMALL;
                if (decision.candidates() == 0 || decided != tier) {
                    continue;
                }
                for (int candidateIndex = 0; candidateIndex < decision.candidates(); candidateIndex++) {
                    long candidateSeed = SmallDepositAttemptMath.candidateSeed(attemptSeed, candidateIndex);
                    BlockPos origin = candidateIndex == 0 ? pos
                            : extraCandidateOrigin(pos, chunk, candidateSeed, stream.inSquare());
                    if (candidateIndex > 0 && config != source.getValue().get(noiseBiome(level, origin))) {
                        continue;
                    }
                    double coverage = hostCoverage(level, generator, randomState, columns, origin, config.targets(),
                            candidateSeed);
                    if (coverage <= 0.5D) {
                        continue;
                    }
                    predictions.add(new Prediction(new DepositCandidate(
                            SeedMixer.mix(candidateSeed ^ ((long) tier.index() << 56)), oreId, tier, origin,
                            1, 1, 1, candidateSeed, null, 1, chunkX, chunkZ
                    ), coverage));
                }
            }
        }
        return List.copyOf(predictions);
    }

    private static ResourceLocation noiseBiome(ServerLevel level, BlockPos pos) {
        return BiomeNoiseCache.get(level, QuartPos.fromBlock(pos.getX()), QuartPos.fromBlock(pos.getY()),
                QuartPos.fromBlock(pos.getZ())).unwrapKey().map(key -> key.location()).orElse(null);
    }

    /** Share of the origin and its six neighbours that are a replaceable host in base terrain. */
    private static double hostCoverage(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            Map<Long, NoiseColumn> columns,
            BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets,
            long seed
    ) {
        RandomSource random = RandomSource.create(seed);
        int hosts = 0;
        int samples = 0;
        if (!isHost(level, generator, randomState, columns, origin, targets, random)) {
            return 0.0D;
        }
        for (Direction direction : Direction.values()) {
            samples++;
            if (isHost(level, generator, randomState, columns, origin.relative(direction), targets, random)) {
                hosts++;
            }
        }
        return (hosts + 1) / (double) (samples + 1);
    }

    private static boolean isHost(
            ServerLevel level,
            ChunkGenerator generator,
            RandomState randomState,
            Map<Long, NoiseColumn> columns,
            BlockPos pos,
            List<OreConfiguration.TargetBlockState> targets,
            RandomSource random
    ) {
        if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
            return false;
        }
        long key = ChunkPos.asLong(pos.getX(), pos.getZ());
        NoiseColumn column;
        try {
            column = columns.computeIfAbsent(key, ignored -> generator.getBaseColumn(pos.getX(), pos.getZ(), level, randomState));
        } catch (RuntimeException exception) {
            return false;
        }
        BlockState state = column.getBlock(pos.getY());
        if (!state.is(ModBlockTags.STONES) || !state.getFluidState().isEmpty()) {
            return false;
        }
        for (OreConfiguration.TargetBlockState target : targets) {
            if (target.target.test(state, random)) {
                return true;
            }
        }
        return false;
    }

    /** Rarity survey lattice: 16 x 16 chunks, 24 chunks apart (about 6 000 blocks across). */
    static final int RARITY_SURVEY_SIDE = 16;
    static final int RARITY_SURVEY_STEP_CHUNKS = 24;
    private static final Map<ServerLevel, Map<String, Double>> RARITY_SURVEYS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    /**
     * A material's original attempts per chunk relative to the dimension's most frequent material, in (0, 1],
     * from a one-off survey of this world's real attempt streams (real biomes, every source of the material).
     * {@code NaN} when the material has no predictable source in this dimension.
     */
    static double relativeAttemptFrequency(ServerLevel level, String materialKey) {
        Map<String, Double> survey;
        synchronized (RARITY_SURVEYS) {
            survey = RARITY_SURVEYS.get(level);
        }
        if (survey == null) {
            Map<String, Double> computed = surveyAttemptsPerChunk(level, level.getChunkSource().getGenerator(),
                    pos -> noiseBiome(level, pos), level.getSeed(), level.dimension().location().toString());
            synchronized (RARITY_SURVEYS) {
                survey = RARITY_SURVEYS.computeIfAbsent(level, ignored -> computed);
            }
        }
        return relative(survey, materialKey);
    }

    static double relative(Map<String, Double> attemptsPerChunk, String materialKey) {
        double maximum = attemptsPerChunk.values().stream().mapToDouble(Double::doubleValue).max().orElse(0.0D);
        Double value = attemptsPerChunk.get(materialKey);
        return value == null || maximum <= 0.0D ? Double.NaN : value / maximum;
    }

    /**
     * Expected original attempts per chunk of every material, counted exactly like generation: the same
     * chain seeds, the biome check of each final position and the selector branch chance as an expectation.
     */
    static Map<String, Double> surveyAttemptsPerChunk(
            WorldGenLevel heightLevel,
            ChunkGenerator generator,
            java.util.function.Function<BlockPos, ResourceLocation> biomeAt,
            long worldSeed,
            String dimension
    ) {
        Map<String, Map<String, Map<ResourceLocation, OreDepositFeature.Configuration>>> bySource = new TreeMap<>();
        sourcesByMaterial().forEach((material, configs) -> {
            for (OreDepositFeature.Configuration config : configs) {
                if (config.vein().isEmpty() || config.sourceBiome().isEmpty()
                        || !predictable(config.vein().get().sourcePlacement())) {
                    continue;
                }
                bySource.computeIfAbsent(material, ignored -> new TreeMap<>())
                        .computeIfAbsent(config.vein().get().sourceSignature(), ignored -> new HashMap<>())
                        .put(config.sourceBiome().get(), config);
            }
        });
        long started = System.nanoTime();
        Map<String, Double> attempts = new TreeMap<>();
        int half = RARITY_SURVEY_SIDE / 2;
        for (var material : bySource.entrySet()) {
            double total = 0.0D;
            for (var source : material.getValue().entrySet()) {
                List<PlacementModifier> placement = source.getValue().values().iterator().next()
                        .vein().orElseThrow().sourcePlacement();
                for (int gx = -half; gx < half; gx++) {
                    for (int gz = -half; gz < half; gz++) {
                        ChunkPos chunk = new ChunkPos(gx * RARITY_SURVEY_STEP_CHUNKS, gz * RARITY_SURVEY_STEP_CHUNKS);
                        long chainSeed = SmallDepositAttemptMath.chainSeed(worldSeed, dimension, source.getKey(),
                                chunk.x, chunk.z);
                        for (BlockPos pos : evaluate(heightLevel, generator, chunk, placement, chainSeed,
                                source.getKey()).positions()) {
                            OreDepositFeature.Configuration config = source.getValue().get(biomeAt.apply(pos));
                            if (config != null) {
                                total += Math.max(0.0D, Math.min(1.0D, config.sourceProbability()));
                            }
                        }
                    }
                }
            }
            if (total > 0.0D) {
                attempts.put(material.getKey(), total / (RARITY_SURVEY_SIDE * RARITY_SURVEY_SIDE));
            }
        }
        if (OresAndDrillsMod.LOGGER.isDebugEnabled()) {
            OresAndDrillsMod.LOGGER.debug("Ore deposits: TINY/SMALL rarity survey of {} in {} ms: original attempts per chunk {}",
                    dimension, (System.nanoTime() - started) / 1_000_000L, attempts);
        }
        return Map.copyOf(attempts);
    }

    /** Registered source copies per material, for diagnostics and tests. */
    static Map<String, List<OreDepositFeature.Configuration>> sourcesByMaterial() {
        Map<String, List<OreDepositFeature.Configuration>> result = new LinkedHashMap<>();
        SOURCES.entrySet().stream().sorted(Map.Entry.comparingByKey()).forEach(entry -> {
            for (OreDepositFeature.Configuration config : entry.getValue()) {
                if (config.targets().isEmpty()) continue;
                result.computeIfAbsent(OreUnifier.materialKeyFor(config.targets().getFirst().state.getBlock()),
                        ignored -> new ArrayList<>()).add(config);
            }
        });
        return result;
    }
}

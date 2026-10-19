package dev.world.level.levelgen;

import dev.config.OreDimensionOverrides;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.config.OreOverrides;
import dev.config.OreSettingsPresetManager;
import dev.registry.ModAttachments;
import dev.registry.ModBlockTags;
import dev.registry.ModBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;

import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import net.minecraft.world.level.levelgen.structure.StructureStart;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

public class OreDepositFeature extends Feature<OreDepositFeature.Configuration> {
    // Direction.values() allocates a fresh array on every call; deposits use these directions when
    // preventing two independently generated lenses from touching.
    private static final Direction[] HORIZONTAL_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.WEST, Direction.EAST
    };
    private static final Direction[] VERTICAL_DIRECTIONS = {Direction.DOWN, Direction.UP};
    /** Largest horizontal radius of one finalized lens, used by the locate index as a conservative bound. */
    public static final int MAX_UNDERGROUND_RADIUS = 32;
    /** Maximum horizontal correction when a planned origin is not a usable {@code #c:stones} block. */
    public static final int MAX_TAGGED_STONE_RELOCATION_RADIUS = 64;
    /** Vertical correction is bounded separately so high source placements can still reach terrain. */
    static final int MAX_TAGGED_STONE_VERTICAL_RELOCATION_RADIUS = 64;
    /**
     * A failed relocation used to exhaustively inspect a radius-64 three-dimensional diamond. With many
     * retained modded ore attempts that can mean millions of block-state reads for one chunk even though
     * the FEATURES step may only write to its 3x3 chunk window. Nearby stone is overwhelmingly found in
     * the first few shells; cap the exceptional no-stone path so one incompatible placement cannot stall
     * the worldgen worker.
     */
    static final int MAX_TAGGED_STONE_SEARCH_CHECKS = 4_096;
    /** Vanilla 1.21.1 ChunkPyramid configures FEATURES with blockStateWriteRadius(1). */
    static final int FEATURE_WRITE_RADIUS_CHUNKS = 1;
    /** A persisted point may lie at either edge of a lens, so its complete footprint can span two radii. */
    public static final int MAX_DEPOSIT_FOOTPRINT_DIAMETER = Math.max(MAX_UNDERGROUND_RADIUS * 2, CompactDepositGeometry.MAX_HORIZONTAL_REACH * 2);
    private static final int MAX_UNDERGROUND_BLOCKS = 720;
    /**
     * Compatibility guard for extreme per-ore sliders. A single chunk can receive blocks from several
     * neighbouring deposit origins; keeping the attachment payload bounded prevents a maxed-out preset
     * from producing pathological chunk packets (especially with optimized palette implementations).
     */
    private static final int MAX_DEPOSIT_ENTRIES_PER_CHUNK = 360;
    /**
     * Vanilla-like veins reproduce ordinary ore volume: a few hundred ore blocks per chunk, more in
     * mountains. Compact MEDIUM/LARGE bodies already store up to 1,792 entries in one chunk.
     */
    private static final int MAX_VANILLA_LIKE_ENTRIES_PER_CHUNK = 2_048;
    /** OreFeature veins of size 1-3 usually place nothing; size 4 is vanilla's smallest dependable vein. */
    static final int MIN_TINY_VEIN_SIZE = 4;
    static final float TINY_VEIN_SIZE_FRACTION = 0.5F;
    /** TINY/SMALL CAVE_WALL placement: how far a candidate looks for a wall and how hard it tries to face it. */
    static final int SMALL_WALL_SEARCH_HORIZONTAL_RADIUS = 16;
    static final int SMALL_WALL_SEARCH_VERTICAL_RADIUS = 12;
    static final int SMALL_WALL_SEARCH_CHECKS = 8_192;
    static final int SMALL_WALL_VEIN_ORIENTATIONS = 4;
    private static final double BASE_DEPOSIT_ATTEMPTS_PER_CHUNK = 2.0D;
    private static final double SOURCE_DENSITY_SATURATION = 64.0D;
    public static final int MAX_UNDERGROUND_DEPTH = 5;
    private static final double CHUNK_CENTER_TO_CORNER_DISTANCE = Math.sqrt(8.0D * 8.0D + 8.0D * 8.0D);
    private static final double EDGE_ORE_FACTOR = 0.2D;
    private static final AtomicBoolean INVALID_DEPOSIT_STATE_LOGGED = new AtomicBoolean();
    private static volatile Boolean cachedDepositStatesRegistered;
    /**
     * Minimum clearance (in blocks) an ore position must keep above the dimension's actual floor
     * ({@link net.minecraft.world.level.LevelHeightAccessor#getMinBuildHeight()}, read per-dimension rather
     * than assuming a fixed world floor) so veins don't spawn into the bedrock layer.
     */
    static final int BEDROCK_CLEARANCE = 5;

    /** Serialized tier indices used by deposit data and commands; numeric behavior is ordinal-derived. */
    public static final int TIER_TINY = DepositTier.TINY.ordinal();
    public static final int TIER_SMALL = DepositTier.SMALL.ordinal();
    public static final int TIER_MEDIUM = DepositTier.MEDIUM.ordinal();
    public static final int TIER_LARGE = DepositTier.LARGE.ordinal();
    public static final int TIER_COUNT = DepositTier.values().length;
    private static final long ANCHOR_SEED_X = 341873128712L;
    private static final long ANCHOR_SEED_Z = 132897987541L;
    /** Salt for the immutable per-deposit plan (shape, block count and reserve). */
    private static final long DEPOSIT_PLAN_SALT = 0x504C414E4F52454CL;
    /** Salt and bounded probe budget for selecting a real position inside a data-driven 3D biome. */
    private static final long DATA_DRIVEN_BIOME_SEARCH_SALT = 0x42494F4D45534541L;
    private static final ResourceLocation DATA_DRIVEN_BIOME_ANCHOR_ID =
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "biome_anchor");
    private static final int DATA_DRIVEN_BIOME_COLUMNS_PER_CELL = 256;
    private static final int MAX_DATA_DRIVEN_BIOME_ANCHORS = 64;
    // MEDIUM/LARGE lens relocation away from caves, fluids, planned structures and the write bounds.
    private static final int MAX_LENS_SHIFT = 16;
    private static final int LENS_SHIFT_STEP = 2;
    private static final int[] LENS_SHIFT_HEIGHTS = {0, -2, 2, -4, 4};
    private static final int MAX_LENS_SHIFT_EVALUATIONS = 160;
    private static final long LENS_SHIFT_SALT = 0x4C454E5353484946L;
    private static final long FORCED_SLOT_SALT = 0x464F524345445354L;
    private static final int FORCED_ASSUMED_REGION_RADIUS = 150;
    private static final ThreadLocal<DataDrivenDepositLedger.Reservation> PENDING_RESERVATION = new ThreadLocal<>();    private static final int MAX_DATA_DRIVEN_CANDIDATE_CACHE_ENTRIES = 131_072;
    private static final ConcurrentHashMap<Long, DepositCandidate> DATA_DRIVEN_CANDIDATE_CACHE =
            new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<Long, List<BlockPos>> DATA_DRIVEN_BIOME_ANCHOR_CACHE =
            new ConcurrentHashMap<>();
    private static final Set<Long> CLAIMED_DEPOSIT_ATTEMPTS = ConcurrentHashMap.newKeySet();
    /**
     * Guards against the same unified material claiming more than one deposit per chunk+tier. A material
     * unified from several original ore features (stone/deepslate variants, or the same ore registered by
     * multiple mods in one biome) gets its own independent tier-replacement chain per original feature; each
     * chain would otherwise search the same shared attempt-slot pool and could each win a separate slot,
     * spawning several deposits of one material where the weighted lottery only budgeted for one.
     */
    private static final Set<Long> CLAIMED_MATERIAL_SLOTS = ConcurrentHashMap.newKeySet();
    /**
     * Both claim sets above only ever get checked while their own chunk is still being decorated — once a
     * chunk finishes, none of its keys can ever be queried again (chunks don't redecorate). Left unbounded,
     * a long or fast-exploring session accumulates one entry per chunk/tier[/material] forever, growing
     * without limit. Since old entries are already permanently dead weight, wiping the whole set once it
     * crosses this size is free: at worst it forces a currently-decorating chunk to re-roll a slot it just
     * claimed, which the placement logic already tolerates by design (see the claim-deferral comment below).
     */
    private static final int MAX_CLAIM_CACHE_ENTRIES = 400_000;
    private static volatile boolean loggedTierTable;

    public OreDepositFeature(Codec<Configuration> codec) {
        super(codec);
    }

    /**
     * A fixed representative point for a chunk's biome lookup. Different ores place at very different Y
     * (e.g. iron near Y=64, diamond near Y=-31), so sampling the biome at each ore's own placement origin
     * would make the winning material for a chunk depend on which ore's placement attempt happened to run
     * first and at what depth. Anchoring the sample to a single fixed point per chunk makes the winner
     * deterministic regardless of call order.
     */
    private static BlockPos chunkSamplePos(int chunkX, int chunkZ, int y) {
        return new BlockPos((chunkX << 4) + 8, y, (chunkZ << 4) + 8);
    }

    /**
     * Reads the immutable noise biome without asking ServerLevel for a chunk. A normal getBiome call can
     * populate the live chunk cache, after which /locate correctly treats that same unconfirmed FULL chunk
     * as already generated and rejects its own prediction.
     */
    private static Holder<Biome> chunkSampleBiome(ServerLevel level, int chunkX, int chunkZ) {
        BlockPos sample = chunkSamplePos(chunkX, chunkZ, chunkSampleY(level));
        return BiomeNoiseCache.get(level,
                QuartPos.fromBlock(sample.getX()),
                QuartPos.fromBlock(sample.getY()),
                QuartPos.fromBlock(sample.getZ())
        );
    }

    static ResourceLocation automaticSourceBiomeId(ServerLevel level, int chunkX, int chunkZ) {
        return chunkSampleBiome(level, chunkX, chunkZ)
                .unwrapKey().map(key -> key.location()).orElse(null);
    }

    /** One immutable automatic source biome per cell prevents candidate count from scaling with biome count. */
    static boolean matchesAutomaticSourceBiome(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            ResourceLocation sourceBiomeId
    ) {
        if (sourceBiomeId == null) {
            return true;
        }
        return chunkSampleBiome(level, chunkX, chunkZ).is(
                net.minecraft.resources.ResourceKey.create(Registries.BIOME, sourceBiomeId)
        );
    }

    private static int chunkSampleY(net.minecraft.world.level.LevelHeightAccessor level) {
        return Mth.clamp(64, level.getMinBuildHeight(), level.getMaxBuildHeight() - 1);
    }

    // ------------------------------------------------------------------------------------------------
    // Tier scale -> spawn weight -> share. Global bounds and curves are the only numeric inputs.
    // ------------------------------------------------------------------------------------------------

    private static DepositTierMath.TierProfile tierProfile(int tier) {
        return computeTierShares().profiles()[DepositTier.byIndex(tier).ordinal()];
    }

    // Cache the derived table and rebuild it only after a live change to one of the global parameters.
    private static volatile TierShares cachedTierShares;
    private static volatile DepositTierMath.Parameters cachedTierParameters;

    /** Every tier's normalized scale, spawn weight and share, computed together in one pass (section 3). */
    private static TierShares computeTierShares() {
        DepositTierMath.Parameters parameters = tierParameters();
        TierShares cached = cachedTierShares;
        if (cached != null && parameters.equals(cachedTierParameters)) {
            return cached;
        }

        DepositTierMath.TierDistribution distribution = DepositTierMath.distribution(parameters);
        TierShares computed = new TierShares(
                distribution.profiles(),
                distribution.normalizedScales(),
                distribution.spawnWeights(),
                distribution.shares()
        );
        cachedTierParameters = parameters;
        cachedTierShares = computed;
        return computed;
    }

    private static DepositTierMath.Parameters tierParameters() {
        return new DepositTierMath.Parameters(
                OreDepositConfig.MIN_DEPOSIT_BLOCKS,
                Math.min(MAX_UNDERGROUND_BLOCKS, OreDepositConfig.MAX_DEPOSIT_BLOCKS),
                OreDepositConfig.LARGER_TIER_RARITY,
                OreDepositConfig.DEPOSIT_SIZE_RARITY_WEIGHT,
                OreDepositConfig.ORE_AMOUNT_RARITY_WEIGHT
        );
    }

    // ------------------------------------------------------------------------------------------------
    // Section 2 + 3: the independent MEDIUM/LARGE rare-event budget per chunk. TINY/SMALL do not use
    // this shared budget: they retain each material's original placed-feature attempt stream instead.
    // ------------------------------------------------------------------------------------------------

    private static double totalDepositAttempts(ServerLevel level, ResourceLocation biomeId) {
        double totalOriginalFrequency = OreGenerationWeights.totalOriginalFrequency(level.dimension().location(), biomeId);
        if (totalOriginalFrequency <= 0.0D) {
            return 0.0D;
        }

        // Compress wildly different vanilla/modded source counts into a stable 0..1 biome-density factor.
        // Frequency sliders are compensated in attemptsForChunkTier, where the same rare-tier lottery
        // weights are known, so each ore's slider scales only that ore's deposits (Factorio semantics).
        double sourceDensity = 1.0D - Math.exp(-totalOriginalFrequency / SOURCE_DENSITY_SATURATION);
        double attempts = BASE_DEPOSIT_ATTEMPTS_PER_CHUNK
                * sourceDensity
                * OreDepositConfig.LARGE_DEPOSIT_ATTEMPT_MULTIPLIER;
        return Mth.clamp(attempts, 0.0D, OreDepositConfig.MAX_GENERATION_ATTEMPTS_PER_CHUNK);
    }

    private static RandomSource chunkTierSeed(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            ResourceLocation biomeId
    ) {
        return RandomSource.create(DepositSeedMath.cellSeed(
                level.getSeed(),
                level.dimension().location().toString(),
                biomeId == null ? "" : biomeId.toString(),
                "",
                DepositLayouts.tierSalt(tier),
                chunkX,
                chunkZ,
                0,
                0x4155544F5F544945L,
                ANCHOR_SEED_X,
                ANCHOR_SEED_Z
        ));
    }

    private static RandomSource attemptSlotSeed(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            int attemptIndex,
            ResourceLocation biomeId
    ) {
        RandomSource chunkSeed = chunkTierSeed(level, chunkX, chunkZ, tier, biomeId);
        return RandomSource.create(chunkSeed.nextLong() ^ (attemptIndex * ANCHOR_SEED_X + 1));
    }

    /**
     * The physical plan must not depend on the order in which placed features happen to run.  Once a
     * source attempt has selected a material, its lens is fully defined by immutable world values before
     * the first block is inspected or written.  In particular, this deliberately does not use
     * {@link FeaturePlaceContext#random()}, whose stream belongs to the surrounding placed feature.
     */
    private static RandomSource depositPlanSeed(
            ServerLevel level,
            BlockPos origin,
            int tier,
            ResourceLocation oreId
    ) {
        return RandomSource.create(depositPlanSeedValue(level, origin, tier, oreId));
    }

    private static long depositPlanSeedValue(
            ServerLevel level,
            BlockPos origin,
            int tier,
            ResourceLocation oreId
    ) {
        long dimensionSalt = level.dimension().location().toString().hashCode();
        long materialSalt = oreId.toString().hashCode();
        return level.getSeed()
                ^ DEPOSIT_PLAN_SALT
                ^ DepositLayouts.tierSalt(tier)
                ^ (dimensionSalt * 132897987541L)
                ^ (materialSalt * 2654435761L)
                ^ (origin.asLong() * 341873128712L)
                ^ ((long) tier * 999999937L);
    }

    private static RandomSource depositCenterSeed(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            String materialKey,
            ResourceLocation biomeId
    ) {
        return RandomSource.create(DepositSeedMath.cellSeed(
                level.getSeed(),
                level.dimension().location().toString(),
                biomeId == null ? "" : biomeId.toString(),
                materialKey,
                DepositLayouts.tierSalt(tier),
                chunkX,
                chunkZ,
                0,
                DEPOSIT_PLAN_SALT * 31L,
                ANCHOR_SEED_X,
                ANCHOR_SEED_Z
        ));
    }

    private static float liveFrequencyMultiplier(Block block) {
        return OreOverrides.lookupConfigured(block)
                .map(OreOverrides.OreOverride::frequency)
                .orElseGet(() -> OreSettingsPresetManager.resolve(block).multipliers().frequency());
    }

    static float liveSizeMultiplier(Block block) {
        return OreOverrides.lookupConfigured(block)
                .map(OreOverrides.OreOverride::size)
                .orElseGet(() -> OreSettingsPresetManager.resolve(block).multipliers().size());
    }

    /**
     * Vanilla-like TINY/SMALL. This call is one attempt of the original ore feature, already positioned by
     * its own placement modifiers, and the vein is built by {@code Feature.ORE} itself. Every block the
     * vanilla feature would fill becomes a deposit block holding one unit (one mined ore), scaled by the
     * Richness slider. The tier only labels the vein by its block count.
     */
    private static boolean placeVanillaVein(FeaturePlaceContext<Configuration> context, OreVein vein) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Configuration config = context.config();
        BlockPos origin = context.origin();
        List<OreConfiguration.TargetBlockState> targets = config.targets();
        if (targets.isEmpty()) {
            return false;
        }
        // A weighted random-selector branch keeps the probability the original selector gave it.
        if (config.sourceProbability() < 1.0F && random.nextFloat() >= config.sourceProbability()) {
            return false;
        }
        // Each biome owns a copy of the source feature; like BiomeFilter on the shared original, a copy
        // places only inside its own biome. Chunk biome data is already generated at this step.
        ResourceLocation sourceBiome = config.sourceBiome().orElse(null);
        if (!WorldgenReadArea.contains(level, origin)) {
            return false;
        }
        if (sourceBiome != null && !level.getBiome(origin).is(ResourceKey.create(Registries.BIOME, sourceBiome))) {
            return false;
        }
        Block ore = targets.getFirst().state.getBlock();
        // Below 1 the Frequency slider drops attempts; above 1 it adds ore per block so veins keep their shape.
        float frequency = Mth.clamp(liveFrequencyMultiplier(ore), 0.1F, 6.0F);
        if (frequency < 1.0F && random.nextFloat() >= frequency) {
            return false;
        }
        int size = Math.max(1, Math.round(vein.size() * Mth.clamp(liveSizeMultiplier(ore), 0.1F, 6.0F)));
        List<DepositPosition> positions = limitByChunkCompatibilityBudget(
                level,
                collectOreVein(level, context.chunkGenerator(), random, origin, targets, size, vein.airExposureDiscard()),
                MAX_VANILLA_LIKE_ENTRIES_PER_CHUNK
        );
        if (positions.isEmpty()) {
            return false;
        }

        OreDepositPaletteData palette = OreDepositPaletteData.get(level.getLevel());
        Map<Block, Integer> oreIndices = new IdentityHashMap<>();
        for (DepositPosition position : positions) {
            Block oreBlock = position.target().state.getBlock();
            if (!oreIndices.containsKey(oreBlock)) {
                int oreIndex = palette.oreIndex(oreBlock);
                if (oreIndex < 0) {
                    return false;
                }
                oreIndices.put(oreBlock, oreIndex);
            }
        }

        int tier = positions.size() <= tierProfile(TIER_TINY).maximumBlockCount() ? TIER_TINY : TIER_SMALL;
        float orePerBlock = Mth.clamp(config.richnessMultiplier(), 0.1F, 6.0F) * Math.max(1.0F, frequency);
        double[] equalWeights = new double[positions.size()];
        java.util.Arrays.fill(equalWeights, 1.0D);
        int[] amounts = DepositTierMath.distributeOre(
                Math.max(positions.size(), Math.round(positions.size() * orePerBlock)), equalWeights
        );

        Map<Block, Integer> baseIndices = new IdentityHashMap<>();
        BlockState depositState = ModBlocks.ORE_DEPOSIT.get().defaultBlockState();
        List<OreDepositData.GeneratedDeposit> generated = new ArrayList<>(positions.size());
        for (int index = 0; index < positions.size(); index++) {
            DepositPosition position = positions.get(index);
            BlockPos pos = position.pos();
            BlockState host = level.getBlockState(pos);
            Integer baseIndex = baseIndices.get(host.getBlock());
            if (baseIndex == null) {
                baseIndex = palette.baseIndex(host.getBlock());
                if (baseIndex >= 0) {
                    baseIndices.put(host.getBlock(), baseIndex);
                } else {
                    baseIndex = OreDepositStonePalette.nearestByMapColor(
                            level.getLevel(), position.target().state.getMapColor(level, pos).col
                    );
                }
            }
            // Same client-update suppression as every other deposit write during decoration.
            if (!level.setBlock(pos, depositState, Block.UPDATE_NONE)) {
                continue;
            }
            generated.add(new OreDepositData.GeneratedDeposit(
                    pos, baseIndex, oreIndices.get(position.target().state.getBlock()), amounts[index], tier
            ));
        }
        // Vanilla-like veins are as numerous as ordinary veins (dozens per chunk). Recording each one would
        // grow the persistent locate index without bound, so they are written to chunk data only.
        OreDepositData.setGeneratedBatch(level, generated);
        return !generated.isEmpty();
    }

    /** Live per-ore Frequency slider, clamped to the menu range. */
    static double liveFrequencyMultiplierFor(Block block) {
        return Mth.clamp(liveFrequencyMultiplier(block), 0.1F, 6.0F);
    }

    /**
     * Automatic TINY/SMALL for one source copy in one chunk. The source's own placement modifiers yield its
     * original attempts (once, with a stable seed); each attempt inside this copy's biome that the selector
     * branch really runs is one source attempt. The retention gate keeps
     * {@code floor(lambda) + Bernoulli(frac(lambda))} candidates of a single tier from it, where
     * {@code lambda = retention * Frequency}. Each candidate is one vanilla vein at the attempt position;
     * a position in air simply finds no host rock and is refused, never moved elsewhere.
     */
    private static boolean placeSourceAttempts(FeaturePlaceContext<Configuration> context, OreVein vein) {
        WorldGenLevel level = context.level();
        Configuration config = context.config();
        List<OreConfiguration.TargetBlockState> targets = config.targets();
        if (targets.isEmpty()) {
            return false;
        }
        ServerLevel serverLevel = level.getLevel();
        ResourceLocation dimension = serverLevel.dimension().location();
        ChunkPos chunk = new ChunkPos(context.origin());
        Block ore = targets.getFirst().state.getBlock();
        String material = OreUnifier.materialKeyFor(ore);
        SmallDepositDiagnostics.Counters stats = SmallDepositDiagnostics.forMaterial(dimension, material);

        long chainSeed = SmallDepositAttemptMath.chainSeed(
                serverLevel.getSeed(), dimension.toString(), vein.sourceSignature(), chunk.x, chunk.z);
        SmallDepositAttemptPlanner.AttemptStream stream = SmallDepositAttemptPlanner.evaluate(
                level, context.chunkGenerator(), chunk, vein.sourcePlacement(), chainSeed, vein.sourceSignature());
        if (stream.failed()) {
            return stats.refuse(SmallDepositDiagnostics.Refusal.PLACEMENT_ERROR);
        }
        if (stream.truncated()) {
            stats.refuse(SmallDepositDiagnostics.Refusal.ATTEMPT_LIMIT);
        }
        ResourceKey<Biome> sourceBiome = config.sourceBiome()
                .map(id -> ResourceKey.create(Registries.BIOME, id)).orElse(null);
        double lambda = SmallDepositAttemptMath.expectedCandidates(
                OreDepositConfig.SMALL_DEPOSIT_ATTEMPT_RETENTION, liveFrequencyMultiplierFor(ore));
        boolean placedAny = false;
        List<BlockPos> attempts = stream.positions();
        for (int index = 0; index < attempts.size(); index++) {
            BlockPos attempt = attempts.get(index);
            // A modded chain may offset positions out of the decorated chunks; their biome and terrain cannot be
            // read yet. Each attempt has its own seed, so skipping one leaves the others unchanged.
            if (!WorldgenReadArea.contains(level, attempt)) {
                stats.refuse(SmallDepositDiagnostics.Refusal.OUTSIDE_GENERATION_AREA);
                continue;
            }
            // Every biome owns a copy of the source and evaluates the same positions; like BiomeFilter on the
            // shared original feature, each copy keeps only the attempts inside its own biome.
            if (sourceBiome != null && !level.getBiome(attempt).is(sourceBiome)) {
                continue;
            }
            long attemptSeed = SmallDepositAttemptMath.attemptSeed(chainSeed, index, attempt.asLong());
            SmallDepositAttemptMath.Decision decision = SmallDepositAttemptMath.decide(
                    attemptSeed, config.sourceProbability(), lambda, OreDepositConfig.SMALL_DEPOSIT_TINY_SHARE);
            if (!decision.sourceAttempt()) {
                continue;
            }
            stats.sourceAttempts.increment();
            int tier = decision.tiny() ? TIER_TINY : TIER_SMALL;
            for (int candidateIndex = 0; candidateIndex < decision.candidates(); candidateIndex++) {
                stats.candidate(decision.tiny());
                long candidateSeed = SmallDepositAttemptMath.candidateSeed(attemptSeed, candidateIndex);
                BlockPos origin = candidateIndex == 0 ? attempt
                        : SmallDepositAttemptPlanner.extraCandidateOrigin(attempt, chunk, candidateSeed, stream.inSquare());
                if (candidateIndex > 0 && sourceBiome != null && !level.getBiome(origin).is(sourceBiome)) {
                    stats.refuse(SmallDepositDiagnostics.Refusal.OUTSIDE_SOURCE_BIOME);
                    continue;
                }
                placedAny |= placeSourceCandidate(level, context.chunkGenerator(), config, vein, tier, origin,
                        candidateSeed, stats);
            }
        }
        SmallDepositDiagnostics.maybeLog(dimension, material, stats);
        return placedAny;
    }

    private static boolean placeSourceCandidate(
            WorldGenLevel level,
            ChunkGenerator generator,
            Configuration config,
            OreVein vein,
            int tier,
            BlockPos attemptOrigin,
            long candidateSeed,
            SmallDepositDiagnostics.Counters stats
    ) {
        List<OreConfiguration.TargetBlockState> targets = config.targets();
        Block ore = targets.getFirst().state.getBlock();
        boolean tiny = tier == TIER_TINY;
        RandomSource planRandom = RandomSource.create(candidateSeed);
        int veinSize = smallTierVeinSize(vein.size(), tier, liveSizeMultiplier(ore));

        List<DepositPosition> positions = List.of();
        boolean wallAnchored = false;
        if (OreDepositConfig.SMALL_DEPOSIT_PLACEMENT.get() == OreDepositConfig.SmallDepositPlacement.CAVE_WALL
                && SmallDepositAttemptMath.placesOnWall(candidateSeed,
                        smallDepositWallChance(level.getLevel(), OreUnifier.materialKeyFor(ore)))) {
            stats.wallRolls.increment();
            // The vein is laid from the air block in front of the wall; the wall keeps that block and the
            // vanilla feature's whole footprint around it inside the generated area.
            GenerationWriteBounds writeBounds = generationWriteBounds(level);
            GenerationWriteBounds wallArea = writeBounds == null ? null
                    : writeBounds.inset(OreFeatureFootprint.horizontalReach(veinSize) + 1);
            CaveWallAnchor wall = CaveWallDetector.findNearest(
                    level, attemptOrigin, SMALL_WALL_SEARCH_HORIZONTAL_RADIUS, SMALL_WALL_SEARCH_VERTICAL_RADIUS,
                    SMALL_WALL_SEARCH_CHECKS, 1, 2, null,
                    pos -> (wallArea == null || wallArea.contains(pos.getX(), pos.getZ()))
                            && level.ensureCanWrite(pos)
            ).orElse(null);
            if (wall != null) {
                positions = collectWallVein(level, generator, planRandom, wall, targets, veinSize, candidateSeed);
                wallAnchored = !positions.isEmpty();
            }
        }
        if (positions.isEmpty()) {
            // No usable wall nearby (or the cave swallowed the vein): the original attempt position.
            positions = collectOreVein(level, generator, planRandom, attemptOrigin, targets, veinSize,
                    vein.airExposureDiscard());
        }
        if (positions.isEmpty()) {
            return stats.refuse(SmallDepositDiagnostics.Refusal.NO_HOST_ROCK);
        }
        if (touchesExistingDeposit(level, positions)) {
            return stats.refuse(SmallDepositDiagnostics.Refusal.OVERLAP);
        }

        // Richness scales the whole-deposit reserve only; it never adds candidates or blocks.
        float richness = Mth.clamp(config.richnessMultiplier(), 0.1F, 6.0F);
        int reserve = tiny
                ? SmallDepositAttemptMath.sampleReserve(OreDepositConfig.TINY_DEPOSIT_MIN_RESERVE,
                        OreDepositConfig.TINY_DEPOSIT_MAX_RESERVE, richness, planRandom::nextDouble)
                : SmallDepositAttemptMath.sampleReserve(OreDepositConfig.SMALL_DEPOSIT_MIN_RESERVE,
                        OreDepositConfig.SMALL_DEPOSIT_MAX_RESERVE, richness, planRandom::nextDouble);
        if (positions.size() > reserve) {
            // Every placed block holds at least one unit, so a vein larger than its reserve is trimmed. Blocks
            // a cave or the surface exposed are kept first: trimming must not hide veins from cave walls.
            int[] xs = positions.stream().mapToInt(position -> position.pos().getX()).toArray();
            int[] ys = positions.stream().mapToInt(position -> position.pos().getY()).toArray();
            int[] zs = positions.stream().mapToInt(position -> position.pos().getZ()).toArray();
            boolean[] exposed = new boolean[positions.size()];
            for (int index = 0; index < exposed.length; index++) {
                exposed[index] = isExposedToAir(level, positions.get(index).pos());
            }
            int[] kept = SmallDepositAttemptMath.keepForReserve(xs, ys, zs, exposed, reserve);
            List<DepositPosition> trimmed = new ArrayList<>(kept.length);
            for (int index : kept) {
                trimmed.add(positions.get(index));
            }
            stats.blocksTrimmedToReserve.add(positions.size() - trimmed.size());
            positions = trimmed;
        }
        positions = limitByChunkCompatibilityBudget(level, positions);
        if (positions.isEmpty()) {
            return stats.refuse(SmallDepositDiagnostics.Refusal.CHUNK_ENTRY_LIMIT);
        }

        OreDepositPaletteData palette = OreDepositPaletteData.get(level.getLevel());
        Map<Block, Integer> oreIndices = new IdentityHashMap<>();
        for (DepositPosition position : positions) {
            Block oreBlock = position.target().state.getBlock();
            if (!oreIndices.containsKey(oreBlock)) {
                int oreIndex = palette.oreIndex(oreBlock);
                if (oreIndex < 0) {
                    return stats.refuse(SmallDepositDiagnostics.Refusal.PALETTE_FULL);
                }
                oreIndices.put(oreBlock, oreIndex);
            }
        }

        Map<Block, Integer> baseIndices = new IdentityHashMap<>();
        BlockState depositState = ModBlocks.ORE_DEPOSIT.get().defaultBlockState();
        List<BlockPos> written = new ArrayList<>(positions.size());
        List<int[]> indices = new ArrayList<>(positions.size());
        boolean visible = false;
        for (DepositPosition position : positions) {
            BlockPos pos = position.pos();
            BlockState host = level.getBlockState(pos);
            if (!host.is(ModBlockTags.STONES) || !host.getFluidState().isEmpty()) {
                continue;
            }
            Integer baseIndex = baseIndices.get(host.getBlock());
            if (baseIndex == null) {
                baseIndex = palette.baseIndex(host.getBlock());
                if (baseIndex >= 0) {
                    baseIndices.put(host.getBlock(), baseIndex);
                } else {
                    baseIndex = OreDepositStonePalette.nearestByMapColor(
                            level.getLevel(), position.target().state.getMapColor(level, pos).col);
                }
            }
            // Same client-update suppression as every other deposit write during decoration.
            if (!level.setBlock(pos, depositState, Block.UPDATE_NONE)) {
                continue;
            }
            written.add(pos);
            indices.add(new int[] {baseIndex, oreIndices.get(position.target().state.getBlock())});
            if (isExposedToAir(level, pos)) {
                stats.exposedOreBlocks.increment();
                visible = true;
            }
        }
        if (written.isEmpty()) {
            return stats.refuse(SmallDepositDiagnostics.Refusal.WRITE_REJECTED);
        }
        // The reserve is split over the blocks actually written: the sum is exact and no block is empty.
        int[] amounts = SmallDepositAttemptMath.splitReserve(reserve, written.size());
        List<OreDepositData.GeneratedDeposit> generated = new ArrayList<>(written.size());
        for (int index = 0; index < written.size(); index++) {
            generated.add(new OreDepositData.GeneratedDeposit(
                    written.get(index), indices.get(index)[0], indices.get(index)[1], amounts[index], tier));
        }
        // Like vanilla-like veins these are far too numerous for the persistent locate index; /locate reads
        // them from loaded chunk data instead.
        OreDepositData.setGeneratedBatch(level, generated);
        if (wallAnchored) {
            stats.wallAnchoredDeposits.increment();
        }
        if (visible) {
            stats.visibleDeposits.increment();
        }
        return stats.placed(tiny, written.size(), reserve);
    }

    /**
     * A vanilla vein laid on a cave wall so that it is seen from the cave. Its origin is the air block in front
     * of the wall, so the stone half of the vein is the wall's face; a few deterministic orientations are tried
     * until one touches the air. The air-exposure discard is not applied: it would erase exactly that face
     * (it is 1.0 for ancient debris or buried diamonds).
     */
    private static List<DepositPosition> collectWallVein(
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource planRandom,
            CaveWallAnchor wall,
            List<OreConfiguration.TargetBlockState> targets,
            int veinSize,
            long candidateSeed
    ) {
        BlockPos front = wall.position().relative(wall.exposedFace());
        List<DepositPosition> best = List.of();
        for (int attempt = 0; attempt < SMALL_WALL_VEIN_ORIENTATIONS; attempt++) {
            RandomSource random = attempt == 0 ? planRandom : RandomSource.create(SeedMixer.mix(candidateSeed + attempt));
            List<DepositPosition> positions = collectOreVein(level, generator, random, front, targets, veinSize, 0.0F);
            for (DepositPosition position : positions) {
                if (isExposedToAir(level, position.pos())) {
                    return positions;
                }
            }
            if (best.isEmpty()) {
                best = positions;
            }
        }
        return best;
    }

    /** CAVE_WALL share of a material's TINY/SMALL candidates; rarer ores stay buried more often. */
    static double smallDepositWallChance(ServerLevel level, String materialKey) {
        return smallDepositWallChance(SmallDepositAttemptPlanner.relativeAttemptFrequency(level, materialKey));
    }

    static double smallDepositWallChance(double relativeAttemptFrequency) {
        return SmallDepositAttemptMath.wallChance(relativeAttemptFrequency,
                OreDepositConfig.SMALL_DEPOSIT_WALL_CHANCE_COMMON, OreDepositConfig.SMALL_DEPOSIT_WALL_CHANCE_RARE,
                OreDepositConfig.SMALL_DEPOSIT_WALL_RARITY_DECADES);
    }

    /** Same notion as vanilla's air-exposure check: any of the six neighbours is air. */
    private static boolean isExposedToAir(WorldGenLevel level, BlockPos pos) {
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (Direction direction : Direction.values()) {
            neighbor.setWithOffset(pos, direction);
            if (WorldgenReadArea.contains(level, neighbor)
                    && CaveWallDetector.isOpenCaveSpace(level.getBlockState(neighbor))) {
                return true;
            }
        }
        return false;
    }

    /**
     * Regular-planner vein size: SMALL is the source feature's own vein, TINY a halved copy of it, and the
     * Size slider scales both. {@link #MIN_TINY_VEIN_SIZE} keeps a TINY vein able to place a block at all.
     */
    static int smallTierVeinSize(int sourceSize, int tier, float sizeMultiplier) {
        int size = tier == TIER_TINY
                ? Math.max(MIN_TINY_VEIN_SIZE, Math.round(sourceSize * TINY_VEIN_SIZE_FRACTION))
                : Math.max(MIN_TINY_VEIN_SIZE, sourceSize);
        return Mth.clamp(Math.round(size * Mth.clamp(sizeMultiplier, 0.1F, 6.0F)), 1, 64);
    }

    /**
     * One {@code Feature.ORE} vein of {@code size} at {@code origin}, built by the vanilla feature with the
     * source's targets and air-exposure discard. Only positions over a {@code #c:stones} host are kept: a
     * deposit block stores that host in its palette.
     */
    private static List<DepositPosition> collectOreVein(
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource random,
            BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets,
            int size,
            float airExposureDiscard
    ) {
        List<DepositPosition> vein = VanillaOreVein.collect(
                level, generator, random, origin,
                new OreConfiguration(targets, Mth.clamp(size, 0, 64), Mth.clamp(airExposureDiscard, 0.0F, 1.0F))
        );
        List<DepositPosition> hosted = new ArrayList<>(vein.size());
        for (DepositPosition position : vein) {
            BlockState host = level.getBlockState(position.pos());
            if (host.is(ModBlockTags.STONES) && host.getFluidState().isEmpty()) {
                hosted.add(position);
            }
        }
        return hosted;
    }

    /** Builds the immutable geometry plan used by both placement and /locate. */
    public static DepositCandidate createPlannedCandidate(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            ResourceLocation oreId,
            String materialKey,
            float sizeMultiplier
    ) {
        BlockPos center = plannedCenterForChunk(level, chunkX, chunkZ, tier, materialKey, null);
        return createPlannedCandidateAtCenter(
                level, chunkX, chunkZ, tier, oreId, center, sizeMultiplier
        );
    }

    static DepositCandidate createPlannedCandidate(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            ResourceLocation oreId,
            String materialKey,
            float sizeMultiplier,
            ResourceLocation sourceBiomeId
    ) {
        BlockPos center = plannedCenterForChunk(
                level, chunkX, chunkZ, tier, materialKey, sourceBiomeId
        );
        return createPlannedCandidateAtCenter(
                level, chunkX, chunkZ, tier, oreId, center, sizeMultiplier
        );
    }

    private static DepositCandidate createPlannedCandidateAtCenter(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            ResourceLocation oreId,
            BlockPos center,
            float sizeMultiplier
    ) {
        if (tier >= TIER_MEDIUM) {
            Block ore = BuiltInRegistries.BLOCK.get(oreId);
            oreId = BuiltInRegistries.BLOCK.getKey(OreUnifier.canonicalMaterialBlockFor(ore).orElse(ore));
        }
        long shapeSeed = depositPlanSeedValue(level, center, tier, oreId);
        if (tier >= TIER_MEDIUM) {
            CompactDepositGeometry geometry = compactGeometry(shapeSeed, tier, sizeMultiplier);
            long depositId = mix64(shapeSeed ^ center.asLong() ^ ((long) tier << 56));
            return new DepositCandidate(depositId, oreId, OreDepositTier.fromIndex(tier).orElseThrow(), center,
                    geometry.radiusX(), geometry.radiusZ(), geometry.radiusY(), shapeSeed, null,
                    geometry.cells().size(), chunkX, chunkZ, sizeMultiplier);
        }
        RandomSource random = RandomSource.create(shapeSeed);
        DepositTierMath.TierProfile profile = tierProfile(tier);
        int maximumSafeBlocks = tierParameters().maximumSafeDepositBlocks();
        float clampedSize = Mth.clamp(sizeMultiplier, 0.1F, 6.0F);
        int minBlocks = Mth.clamp(Math.round(profile.minimumBlockCount() * clampedSize), 1, maximumSafeBlocks);
        int maxBlocks = Mth.clamp(Math.round(profile.maximumBlockCount() * clampedSize), minBlocks, maximumSafeBlocks);
        double characteristic = Mth.clamp(profile.characteristicBlockCount() * clampedSize, minBlocks, maxBlocks);
        int expectedBlocks = (int) DepositTierMath.sampleAroundCharacteristicValue(
                minBlocks, maxBlocks, characteristic, random::nextDouble
        );
        ShapeDimensions shape = shapeForBlockCount(expectedBlocks, tier, profile.tierPosition(), random);
        long geometry = ((long) shape.radiusX() << 48)
                ^ ((long) shape.radiusZ() << 32)
                ^ ((long) shape.depth() << 24)
                ^ expectedBlocks;
        long depositId = mix64(shapeSeed ^ center.asLong() ^ ((long) tier << 56) ^ geometry);
        OreDepositTier depositTier = OreDepositTier.fromIndex(tier)
                .orElseThrow(() -> new IllegalArgumentException("Unknown deposit tier index: " + tier));
        Direction wallDirection = depositTier.prefersCaveWall()
                ? HORIZONTAL_DIRECTIONS[Math.floorMod(
                        (int) (shapeSeed ^ shapeSeed >>> 32), HORIZONTAL_DIRECTIONS.length
                )]
                : null;
        return new DepositCandidate(
                depositId, oreId, depositTier, center, shape.radiusX(), shape.radiusZ(), shape.depth(), shapeSeed,
                wallDirection, expectedBlocks, chunkX, chunkZ
        );
    }

    /** Candidate formula shared verbatim by datapack world generation and /locate. */
    static DepositCandidate createDataDrivenCandidate(
            ServerLevel level,
            int cellX,
            int cellZ,
            ResourceLocation biomeId,
            ResourceLocation oreId,
            int tier,
            DataDrivenSettings settings
    ) {
        OreDepositTier publicTier = OreDepositTier.fromIndex(tier).orElseThrow();
        long seed = dataDrivenSeed(level, cellX, cellZ, biomeId, oreId, tier, settings);
        DepositCandidate cached = DATA_DRIVEN_CANDIDATE_CACHE.get(seed);
        if (cached != null) {
            return cached;
        }
        RandomSource random = RandomSource.create(seed);
        int cellSizeChunks = DepositLayouts.forTier(publicTier).cellSizeChunks();
        // Keep an immutable fallback so a cell with no matching biome still has a stable (subsequently
        // rejected) plan. The bounded cell traversal below replaces it whenever the allowed 3D biome is
        // actually present. This matters for cave biomes: one random XYZ sample made forced rules silently
        // disappear even though the cell intersected the requested biome.
        int chunkX = cellX * cellSizeChunks + random.nextInt(cellSizeChunks);
        int chunkZ = cellZ * cellSizeChunks + random.nextInt(cellSizeChunks);
        int minimumY = Mth.clamp(
                settings.minY(), level.getMinBuildHeight() + BEDROCK_CLEARANCE, level.getMaxBuildHeight() - 1
        );
        int maximumY = Mth.clamp(
                settings.maxY(), minimumY, level.getMaxBuildHeight() - 1
        );
        BlockPos fallbackCenter = new BlockPos(
                (chunkX << 4) + random.nextInt(16),
                minimumY + random.nextInt(maximumY - minimumY + 1),
                (chunkZ << 4) + random.nextInt(16)
        );
        long biomeSearchSeed = DepositSeedMath.cellSeed(
                level.getSeed(),
                level.dimension().location().toString(),
                biomeId.toString(),
                DATA_DRIVEN_BIOME_ANCHOR_ID.toString(),
                DepositLayouts.tierSalt(publicTier),
                cellX,
                cellZ,
                0,
                settings.ruleSalt(),
                ANCHOR_SEED_X,
                ANCHOR_SEED_Z
        );
        BlockPos center = findDataDrivenBiomeCenter(
                level, cellX, cellZ, cellSizeChunks, minimumY, maximumY,
                biomeId, seed, biomeSearchSeed, fallbackCenter
        );
        DepositCandidate candidate = createPlannedCandidateAtCenter(
                level, center.getX() >> 4, center.getZ() >> 4,
                tier, oreId, center, settings.sizeMultiplier()
        );
        if (tier >= TIER_MEDIUM) {
            // Include the rule/slot seed even if two slots happen to select the same quart-cell center.
            long shapeSeed = SeedMixer.mix(candidate.shapeSeed() ^ seed);
            CompactDepositGeometry geometry = compactGeometry(shapeSeed, tier, settings.sizeMultiplier());
            candidate = new DepositCandidate(SeedMixer.mix(shapeSeed ^ center.asLong()), candidate.oreId(), publicTier,
                    center, geometry.radiusX(), geometry.radiusZ(), geometry.radiusY(), shapeSeed, null,
                    geometry.cells().size(), center.getX() >> 4, center.getZ() >> 4, settings.sizeMultiplier());
        }
        if (DATA_DRIVEN_CANDIDATE_CACHE.size() >= MAX_DATA_DRIVEN_CANDIDATE_CACHE_ENTRIES) {
            DATA_DRIVEN_CANDIDATE_CACHE.clear();
        }
        DepositCandidate previous = DATA_DRIVEN_CANDIDATE_CACHE.putIfAbsent(seed, candidate);
        return previous == null ? candidate : previous;
    }

    /**
     * Finds a deterministic quart column in the requested biome without loading chunks. Up to 64 columns
     * are spread across the complete tier cell by a coprime permutation; every allowed quart Y is checked.
     * This makes underground/cave biomes discoverable while keeping remote /locate and worldgen bounded.
     */
    private static BlockPos findDataDrivenBiomeCenter(
            ServerLevel level,
            int cellX,
            int cellZ,
            int cellSizeChunks,
            int minimumY,
            int maximumY,
            ResourceLocation biomeId,
            long candidateSeed,
            long biomeSearchSeed,
            BlockPos fallback
    ) {
        if (isNoiseBiome(level, fallback, biomeId)) {
            return fallback;
        }

        List<BlockPos> cachedAnchors = DATA_DRIVEN_BIOME_ANCHOR_CACHE.get(biomeSearchSeed);
        if (cachedAnchors != null) {
            return centerFromBiomeAnchors(cachedAnchors, candidateSeed, minimumY, maximumY, fallback);
        }

        int totalChunks = cellSizeChunks * cellSizeChunks;
        int chunkProbes = Math.min(totalChunks, DATA_DRIVEN_BIOME_COLUMNS_PER_CELL);
        int horizontalProbesPerChunk = Math.min(
                16,
                Math.max(1, (DATA_DRIVEN_BIOME_COLUMNS_PER_CELL + chunkProbes - 1) / chunkProbes)
        );
        int minimumQuartY = Math.floorDiv(minimumY, 4);
        int maximumQuartY = Math.floorDiv(maximumY, 4);
        int quartYCount = maximumQuartY - minimumQuartY + 1;
        int quartYProbes = quartYCount;
        long traversalSeed = SeedMixer.mix(biomeSearchSeed ^ DATA_DRIVEN_BIOME_SEARCH_SALT);
        int chunkStart = DeterministicPermutation.start(totalChunks, traversalSeed);
        int chunkStride = DeterministicPermutation.coprimeStride(totalChunks, traversalSeed >>> 19);
        int cellStartChunkX = cellX * cellSizeChunks;
        int cellStartChunkZ = cellZ * cellSizeChunks;
        BlockPos.MutableBlockPos sample = new BlockPos.MutableBlockPos();
        List<BlockPos> anchors = new ArrayList<>();

        for (int chunkProbe = 0; chunkProbe < chunkProbes; chunkProbe++) {
            int chunkIndex = DeterministicPermutation.index(
                    totalChunks, chunkStart, chunkStride, chunkProbe
            );
            int chunkX = cellStartChunkX + chunkIndex % cellSizeChunks;
            int chunkZ = cellStartChunkZ + chunkIndex / cellSizeChunks;
            long chunkSeed = SeedMixer.mix(traversalSeed ^ ChunkPos.asLong(chunkX, chunkZ));
            int horizontalStart = DeterministicPermutation.start(16, chunkSeed);
            int horizontalStride = DeterministicPermutation.coprimeStride(16, chunkSeed >>> 13);
            int quartYStart = DeterministicPermutation.start(quartYCount, chunkSeed >>> 29);
            int quartYStride = DeterministicPermutation.coprimeStride(quartYCount, chunkSeed >>> 43);

            for (int horizontalProbe = 0; horizontalProbe < horizontalProbesPerChunk; horizontalProbe++) {
                int horizontalIndex = DeterministicPermutation.index(
                        16, horizontalStart, horizontalStride, horizontalProbe
                );
                int quartX = horizontalIndex & 3;
                int quartZ = horizontalIndex >> 2;
                int sampleX = (chunkX << 4) + (quartX << 2) + 2;
                int sampleZ = (chunkZ << 4) + (quartZ << 2) + 2;

                for (int quartYOffset = 0; quartYOffset < quartYProbes; quartYOffset++) {
                    int quartYIndex = DeterministicPermutation.index(
                            quartYCount, quartYStart, quartYStride, quartYOffset
                    );
                    int quartY = minimumQuartY + quartYIndex;
                    int sampleY = Mth.clamp((quartY << 2) + 2, minimumY, maximumY);
                    sample.set(sampleX, sampleY, sampleZ);
                    if (!isNoiseBiome(level, sample, biomeId)) {
                        continue;
                    }
                    // One anchor per column: the Y order is already permuted, so the first match is a
                    // random height. Taking every matching height filled the list from two or three
                    // columns in cave biomes and stacked all slots of a cell at the same X/Z.
                    anchors.add(sample.immutable());
                    break;
                }
                if (anchors.size() >= MAX_DATA_DRIVEN_BIOME_ANCHORS) {
                    break;
                }
            }
            if (anchors.size() >= MAX_DATA_DRIVEN_BIOME_ANCHORS) {
                break;
            }
        }
        List<BlockPos> immutableAnchors = List.copyOf(anchors);
        if (DATA_DRIVEN_BIOME_ANCHOR_CACHE.size() >= MAX_DATA_DRIVEN_CANDIDATE_CACHE_ENTRIES) {
            DATA_DRIVEN_BIOME_ANCHOR_CACHE.clear();
        }
        List<BlockPos> previous = DATA_DRIVEN_BIOME_ANCHOR_CACHE.putIfAbsent(
                biomeSearchSeed, immutableAnchors
        );
        return centerFromBiomeAnchors(
                previous == null ? immutableAnchors : previous,
                candidateSeed,
                minimumY,
                maximumY,
                fallback
        );
    }

    private static BlockPos centerFromBiomeAnchors(
            List<BlockPos> anchors,
            long candidateSeed,
            int minimumY,
            int maximumY,
            BlockPos fallback
    ) {
        if (anchors.isEmpty()) {
            return fallback;
        }
        long pointSeed = SeedMixer.mix(candidateSeed ^ DATA_DRIVEN_BIOME_SEARCH_SALT);
        BlockPos anchor = anchors.get(Math.floorMod(pointSeed, anchors.size()));
        RandomSource pointRandom = RandomSource.create(pointSeed);
        int quartX = QuartPos.fromBlock(anchor.getX());
        int quartY = QuartPos.fromBlock(anchor.getY());
        int quartZ = QuartPos.fromBlock(anchor.getZ());
        int quartMinimumY = Math.max(minimumY, quartY << 2);
        int quartMaximumY = Math.min(maximumY, (quartY << 2) + 3);
        return new BlockPos(
                (quartX << 2) + pointRandom.nextInt(4),
                quartMinimumY + pointRandom.nextInt(quartMaximumY - quartMinimumY + 1),
                (quartZ << 2) + pointRandom.nextInt(4)
        );
    }

    /**
     * Forced datapack rules place exactly {@code count} deposits per biome instance. This chunk offers a center
     * from its own real biome data and reserves a slot of the instance's ledger; the slot is committed only if
     * the deposit is built (see {@link #place}), so a failed chunk leaves it for another part of the instance.
     */
    private static DepositCandidate reserveForcedDeposit(
            WorldGenLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            ResourceLocation oreId,
            ResourceLocation biomeId,
            DataDrivenSettings settings,
            DepositTierLayout layout
    ) {
        ServerLevel serverLevel = level.getLevel();
        int minimumY = Mth.clamp(
                settings.minY(), level.getMinBuildHeight() + BEDROCK_CLEARANCE, level.getMaxBuildHeight() - 1
        );
        int maximumY = Mth.clamp(settings.maxY(), minimumY, level.getMaxBuildHeight() - 1);
        long chunkSeed = SeedMixer.mix(serverLevel.getSeed() ^ settings.ruleSalt() ^ FORCED_SLOT_SALT
                ^ ChunkPos.asLong(chunkX, chunkZ) ^ ((long) tier << 56) ^ oreId.hashCode());
        BlockPos center = realBiomePositionInChunk(
                level, chunkX, chunkZ, ResourceKey.create(Registries.BIOME, biomeId), minimumY, maximumY, chunkSeed
        );
        if (center == null) {
            return null;
        }
        DepositCandidate candidate = createPlannedCandidateAtCenter(
                serverLevel, chunkX, chunkZ, tier, oreId, center, settings.sizeMultiplier()
        );
        BiomeInstances.Instance instance = BiomeInstances.at(
                serverLevel, center, biomeId, minimumY, maximumY, layout.cellSizeBlocks()
        );
        // Deposits of one instance must not overlap even after their uncut-lens shift; beyond that they are
        // spread over the instance (region-based caves are assumed to span about 150 blocks).
        int count = Math.max(1, settings.count());
        int noOverlap = 2 * Math.max(candidate.radiusX(), candidate.radiusZ()) + 4 + MAX_LENS_SHIFT;
        int instanceRadius = Math.min(instance.approximateRadius(), FORCED_ASSUMED_REGION_RADIUS);
        int spacing = Math.max(noOverlap, (int) (instanceRadius / Math.sqrt(count)));
        String ledgerKey = Long.toUnsignedString(settings.ruleSalt()) + "|" + oreId + "|" + tier + "|" + instance.key();
        DataDrivenDepositLedger.Reservation reservation = DataDrivenDepositLedger.get(serverLevel)
                .tryReserve(ledgerKey, count, center, spacing);        if (reservation == null) {
            return null;
        }
        PENDING_RESERVATION.set(reservation);
        return candidate;
    }

    /** A deterministic quart-cell center of this chunk whose real biome is the rule biome. */
    private static BlockPos realBiomePositionInChunk(
            WorldGenLevel level,
            int chunkX,
            int chunkZ,
            ResourceKey<Biome> biome,
            int minimumY,
            int maximumY,
            long seed
    ) {
        ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, false);
        if (chunk == null) {
            return null;
        }
        List<BlockPos> matches = new ArrayList<>();
        for (int quartX = QuartPos.fromSection(chunkX); quartX < QuartPos.fromSection(chunkX + 1); quartX++) {
            for (int quartZ = QuartPos.fromSection(chunkZ); quartZ < QuartPos.fromSection(chunkZ + 1); quartZ++) {
                for (int quartY = QuartPos.fromBlock(minimumY); quartY <= QuartPos.fromBlock(maximumY); quartY++) {
                    if (chunk.getNoiseBiome(quartX, quartY, quartZ).is(biome)) {
                        matches.add(new BlockPos(
                                QuartPos.toBlock(quartX) + 2,
                                Mth.clamp(QuartPos.toBlock(quartY) + 2, minimumY, maximumY),
                                QuartPos.toBlock(quartZ) + 2
                        ));
                    }
                }
            }
        }
        return matches.isEmpty() ? null : matches.get((int) Math.floorMod(seed, (long) matches.size()));
    }

    /**
     * Nearest quart-cell center in the planned chunk and its neighbours whose real chunk biome is the rule
     * biome, within the rule's height range. Only chunks already present in the generation region are read.
     */
    private static BlockPos nearestRealBiomePosition(
            WorldGenLevel level,
            BlockPos planned,
            ResourceKey<Biome> biome,
            DataDrivenSettings settings
    ) {
        int minimumY = Mth.clamp(
                settings.minY(), level.getMinBuildHeight() + BEDROCK_CLEARANCE, level.getMaxBuildHeight() - 1
        );
        int maximumY = Mth.clamp(settings.maxY(), minimumY, level.getMaxBuildHeight() - 1);
        int plannedChunkX = planned.getX() >> 4;
        int plannedChunkZ = planned.getZ() >> 4;
        BlockPos best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int chunkX = plannedChunkX - 1; chunkX <= plannedChunkX + 1; chunkX++) {
            for (int chunkZ = plannedChunkZ - 1; chunkZ <= plannedChunkZ + 1; chunkZ++) {
                ChunkAccess chunk = level.getChunk(chunkX, chunkZ, ChunkStatus.BIOMES, false);
                if (chunk == null) {
                    continue;
                }
                for (int quartX = QuartPos.fromSection(chunkX); quartX < QuartPos.fromSection(chunkX + 1); quartX++) {
                    for (int quartZ = QuartPos.fromSection(chunkZ); quartZ < QuartPos.fromSection(chunkZ + 1); quartZ++) {
                        for (int quartY = QuartPos.fromBlock(minimumY); quartY <= QuartPos.fromBlock(maximumY); quartY++) {
                            if (!chunk.getNoiseBiome(quartX, quartY, quartZ).is(biome)) {
                                continue;
                            }
                            BlockPos candidate = new BlockPos(
                                    QuartPos.toBlock(quartX) + 2,
                                    Mth.clamp(QuartPos.toBlock(quartY) + 2, minimumY, maximumY),
                                    QuartPos.toBlock(quartZ) + 2
                            );
                            double distance = candidate.distSqr(planned);
                            if (distance < bestDistance) {
                                bestDistance = distance;
                                best = candidate;
                            }
                        }
                    }
                }
            }
        }
        return best;
    }

    private static boolean isNoiseBiome(ServerLevel level, BlockPos pos, ResourceLocation biomeId) {
        return DataDrivenOreDepositBiomeModifier.matchesPlannedBiome(level, pos, biomeId);
    }

    static boolean passesDataDrivenFrequency(
            ServerLevel level,
            int cellX,
            int cellZ,
            ResourceLocation biomeId,
            ResourceLocation oreId,
            int tier,
            DataDrivenSettings settings
    ) {
        double chance = settings.forced() ? settings.chance() : retainedSpawnChance(tier, settings.chance());
        if (chance >= 1.0D) {
            return true;
        }
        long seed = dataDrivenSeed(level, cellX, cellZ, biomeId, oreId, tier, settings)
                ^ 0x4652455155454E43L;
        return RandomSource.create(SeedMixer.mix(seed)).nextDouble() < chance;
    }

    private static long dataDrivenSeed(
            ServerLevel level,
            int cellX,
            int cellZ,
            ResourceLocation biomeId,
            ResourceLocation oreId,
            int tier,
            DataDrivenSettings settings
    ) {
        return DepositSeedMath.cellSeed(
                level.getSeed(),
                level.dimension().location().toString(),
                biomeId.toString(),
                oreId.toString(),
                DepositLayouts.tierSalt(tier),
                cellX,
                cellZ,
                settings.slot(),
                settings.ruleSalt(),
                ANCHOR_SEED_X,
                ANCHOR_SEED_Z
        );
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }

    /**
     * Immutable geometric center for MEDIUM/LARGE deposits. TINY/SMALL instead keep their original
     * placed-feature origin, because they deliberately use Minecraft's normal ore placement stream.
     */
    public static BlockPos plannedCenterForChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            String materialKey
    ) {
        return plannedCenterForChunk(level, chunkX, chunkZ, tier, materialKey, null);
    }

    private static BlockPos plannedCenterForChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            String materialKey,
            ResourceLocation sourceBiomeId
    ) {
        ResourceLocation biomeId = sourceBiomeId;
        if (biomeId == null) {
            Holder<Biome> biome = chunkSampleBiome(level, chunkX, chunkZ);
            biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);
        }
        RandomSource random = depositCenterSeed(level, chunkX, chunkZ, tier, materialKey, biomeId);
        int[] range = OreGenerationWeights.heightRangeForMaterial(level.dimension().location(), biomeId, materialKey);
        int minimumY = level.getMinBuildHeight() + BEDROCK_CLEARANCE;
        int maximumY = level.getMaxBuildHeight() - 1;
        int minY = range == null ? chunkSampleY(level) : range[0];
        int maxY = range == null ? chunkSampleY(level) : range[1];
        minY = Mth.clamp(Math.min(minY, maxY), minimumY, maximumY);
        maxY = Mth.clamp(Math.max(minY, maxY), minY, maximumY);
        int y = minY + random.nextInt(maxY - minY + 1);
        return new BlockPos((chunkX << 4) + random.nextInt(16), y, (chunkZ << 4) + random.nextInt(16));
    }

    /**
     * Not round()'d: a rare tier averaging e.g. 0.35 attempts per chunk must spawn in roughly 35% of chunks,
     * not always-zero (round down) or always-one (round up/nearest). The guaranteed part also preserves
     * configuration values above one; the fractional roll preserves the remaining expectation exactly.
     */
    private static int attemptsForChunkTier(ServerLevel level, int chunkX, int chunkZ, ResourceLocation biomeId, int tier, TierShares shares) {
        // The Frequency-slider budget compensation must use this tier's own lottery weights (its rarity
        // exponent reshapes them), otherwise sliders leak between ores instead of acting per ore.
        double frequencyBudgetMultiplier = OreGenerationWeights.tierFrequencyBudgetMultiplier(
                level.dimension().location(), biomeId, tier);
        double tierAttempts = Mth.clamp(
                totalDepositAttempts(level, biomeId) * shares.share()[tier] * frequencyBudgetMultiplier
                        * tierFrequencyMultiplier(tier),
                0.0D,
                OreDepositConfig.MAX_GENERATION_ATTEMPTS_PER_CHUNK
        );
        int guaranteedAttempts = (int) Math.floor(tierAttempts);
        double fractionalChance = tierAttempts - guaranteedAttempts;

        boolean extraAttempt = fractionalChance > 0.0D
                && chunkTierSeed(level, chunkX, chunkZ, tier, biomeId).nextDouble() < fractionalChance;
        return guaranteedAttempts + (extraAttempt ? 1 : 0);
    }

    /** Per-tier frequency tuning on top of the shared tier curves: LARGE more common. */
    private static double tierFrequencyMultiplier(int tier) {
        if (tier == TIER_LARGE) {
            return OreDepositConfig.LARGE_DEPOSIT_FREQUENCY_MULTIPLIER;
        }
        return 1.0D;
    }

    private static double retainedSpawnChance(int tier, double chance) {
        return DepositTierMath.retainedTierCandidateProbability(DepositTier.byIndex(tier), chance,
                OreDepositConfig.TINY_SMALL_SPAWN_RETENTION);
    }

    private static boolean isTierAnchorChunk(int chunkX, int chunkZ, int tier) {
        return OreDepositTier.fromIndex(tier)
                .map(value -> DepositLayouts.isAnchorChunk(value, chunkX, chunkZ))
                .orElse(false);
    }

    private static long computeAttemptClaimKey(ServerLevel level, int chunkX, int chunkZ, int tier, int attemptIndex) {
        long salt = DepositLayouts.tierSalt(tier);
        long dimensionSalt = level.dimension().location().toString().hashCode();
        return level.getSeed() ^ (salt * 341873128712L) ^ (dimensionSalt * 132897987541L)
                ^ (((long) chunkX & 0xFFFFFFFFL) << 32) ^ (chunkZ & 0xFFFFFFFFL) ^ tier
                ^ ((long) attemptIndex * 999999937L);
    }

    private static long computeMaterialClaimKey(ServerLevel level, int chunkX, int chunkZ, int tier, String materialKey) {
        long salt = DepositLayouts.tierSalt(tier);
        long dimensionSalt = level.dimension().location().toString().hashCode();
        long materialSalt = materialKey.hashCode();
        return level.getSeed() ^ (salt * 341873128712L) ^ (dimensionSalt * 132897987541L)
                ^ (((long) chunkX & 0xFFFFFFFFL) << 32) ^ (chunkZ & 0xFFFFFFFFL)
                ^ (materialSalt * 2654435761L);
    }

    private static void trimIfOversized(Set<Long> claimCache) {
        if (claimCache.size() > MAX_CLAIM_CACHE_ENTRIES) {
            claimCache.clear();
        }
    }

    /**
     * Section 5 + 6 (steps 6-9): scans this chunk's attempt slots for {@code tier} (cheapest check first —
     * if the tier has zero attempts here, every material bails immediately) and returns the lowest-indexed,
     * not-yet-claimed slot this material's canonical ore id wins under the tier-weighted lottery. Does NOT
     * mark the slot claimed — see the comment at the {@link #place} call site for why claiming is deferred.
     */
    private static int findWinningAttemptSlot(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            ResourceLocation candidateOreId,
            int tier,
            ResourceLocation biomeId,
            TierShares shares
    ) {
        int attempts = attemptsForChunkTier(level, chunkX, chunkZ, biomeId, tier, shares);
        if (tier >= TIER_MEDIUM && isTierAnchorChunk(chunkX, chunkZ, tier)) {
            // A region owns one geometric center, therefore it may own only one material slot.
            attempts = Math.min(1, attempts);
        }
        if (attempts <= 0) {
            return -1;
        }

        ResourceLocation dimension = level.dimension().location();
        for (int attemptIndex = 0; attemptIndex < attempts; attemptIndex++) {
            if (CLAIMED_DEPOSIT_ATTEMPTS.contains(computeAttemptClaimKey(level, chunkX, chunkZ, tier, attemptIndex))) {
                continue;
            }

            double randomValue = attemptSlotSeed(
                    level, chunkX, chunkZ, tier, attemptIndex, biomeId
            ).nextDouble();
            if (OreGenerationWeights.isSelectedForRegion(dimension, biomeId, candidateOreId, tier, randomValue)) {
                return attemptIndex;
            }
        }
        return -1;
    }

    /**
     * Cheap (no chunk generation) prediction for {@code /locate}: would {@code tier}'s attempt slots in this
     * chunk produce {@code materialKey}? Uses the exact same math as real placement, so it can only ever be a
     * prediction — real placement can still legitimately come up empty (e.g. insufficient shape blocks, or
     * touching an existing deposit), so callers must still verify by generating and reading the real chunk data.
     */
    public static boolean predictsMaterialForChunk(ServerLevel level, int chunkX, int chunkZ, int tier, String materialKey) {
        Holder<Biome> biome = chunkSampleBiome(level, chunkX, chunkZ);
        ResourceLocation biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);
        return predictsMaterialForChunk(level, chunkX, chunkZ, tier, materialKey, biomeId);
    }

    static boolean predictsMaterialForChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            String materialKey,
            ResourceLocation biomeId
    ) {
        if (tier < TIER_MEDIUM || tier >= TIER_COUNT) {
            // Automatic TINY/SMALL have no cell plan; they are predicted by SmallDepositAttemptPlanner.
            return false;
        }
        TierShares shares = computeTierShares();
        if (!isTierAnchorChunk(chunkX, chunkZ, tier)) {
            return false;
        }
        int attempts = attemptsForChunkTier(level, chunkX, chunkZ, biomeId, tier, shares);
        if (tier >= TIER_MEDIUM && isTierAnchorChunk(chunkX, chunkZ, tier)) {
            attempts = Math.min(1, attempts);
        }
        if (attempts <= 0) {
            return false;
        }

        ResourceLocation dimension = level.dimension().location();
        for (int attemptIndex = 0; attemptIndex < attempts; attemptIndex++) {
            double randomValue = attemptSlotSeed(
                    level, chunkX, chunkZ, tier, attemptIndex, biomeId
            ).nextDouble();
            if (OreGenerationWeights.isMaterialSelectedForRegion(
                    dimension, biomeId, materialKey, tier, randomValue
            )) {
                // The same seed-only arbitration is used by the receiving-chunk decoration pass.
                return compactSpacingAllows(level, chunkX, chunkZ, tier);
            }
        }
        return false;
    }

    private record CompactGeometryKey(long seed, int tier, int size) {}
    private static final BoundedLoadingCache<CompactGeometryKey, CompactDepositGeometry> COMPACT_GEOMETRIES =
            new BoundedLoadingCache<>(24);
    private record CompactPlanKey(Configuration config, int cellX, int cellZ, int size) {}
    record CompactPlan(DepositCandidate candidate, List<CompactDepositGeometry.Cell> cells,
            List<OreConfiguration.TargetBlockState> targets, int[] amounts, Map<Long,int[]> slices,int totalOre) {
        CompactPlan(DepositCandidate candidate,List<CompactDepositGeometry.Cell> cells,
                List<OreConfiguration.TargetBlockState> targets,int[] amounts) {
            this(candidate,cells,targets,amounts,indexSlices(candidate,cells),java.util.Arrays.stream(amounts).sum());
        }
        private static final int[] NO_CELLS=new int[0];
        int[] indices(ChunkPos chunk) {return slices.getOrDefault(chunk.toLong(),NO_CELLS);}
        private static Map<Long,int[]> indexSlices(DepositCandidate c,List<CompactDepositGeometry.Cell> cells) {
            var lists=new it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap<it.unimi.dsi.fastutil.ints.IntArrayList>();
            for(int i=0;i<cells.size();i++) {
                var cell=cells.get(i);
                long key=ChunkPos.asLong(Math.floorDiv(c.center().getX()+cell.x(),16),Math.floorDiv(c.center().getZ()+cell.z(),16));
                lists.computeIfAbsent(key,ignored -> new it.unimi.dsi.fastutil.ints.IntArrayList()).add(i);
            }
            Map<Long,int[]> result=new java.util.HashMap<>();
            lists.long2ObjectEntrySet().forEach(entry -> result.put(entry.getLongKey(),entry.getValue().toIntArray()));
            return Map.copyOf(result);
        }
    }
    private static final Map<ServerLevel, BoundedLoadingCache<CompactPlanKey, Optional<CompactPlan>>> COMPACT_PLANS =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());
    // Keep cheap absent/distant candidates from evicting full, terrain-filtered plans.
    private static final Map<ServerLevel,BoundedLoadingCache<CompactPlanKey,Optional<DepositCandidate>>> COMPACT_CANDIDATES =
            java.util.Collections.synchronizedMap(new java.util.WeakHashMap<>());

    private static CompactDepositGeometry compactGeometry(long seed, int tier, float size) {
        CompactGeometryKey key = new CompactGeometryKey(seed, tier, Float.floatToIntBits(size));
        return COMPACT_GEOMETRIES.get(key, () -> new CompactDepositGeometry(seed, tier == TIER_LARGE, size));
    }

    static Optional<CompactPlan> cachedCompactPlan(ServerLevel level, Configuration config, int x, int z) {
        Optional<DepositCandidate> candidate=cachedCompactCandidate(level,config,x,z);
        if(candidate.isEmpty())return Optional.empty();
        BoundedLoadingCache<CompactPlanKey, Optional<CompactPlan>> cache;
        synchronized (COMPACT_PLANS) { cache = COMPACT_PLANS.computeIfAbsent(level, ignored -> new BoundedLoadingCache<>(96)); }
        float size = config.dataDriven().map(DataDrivenSettings::sizeMultiplier)
                .orElseGet(() -> liveSizeMultiplier(config.targets().getFirst().state.getBlock()));
        return cache.get(new CompactPlanKey(config,x,z,Float.floatToIntBits(size)), () -> Optional.ofNullable(buildCompactPlanAt(level,config,candidate.get())));
    }

    private static Optional<DepositCandidate> cachedCompactCandidate(ServerLevel level,Configuration config,int x,int z) {
        BoundedLoadingCache<CompactPlanKey,Optional<DepositCandidate>> cache;
        synchronized(COMPACT_CANDIDATES) {cache=COMPACT_CANDIDATES.computeIfAbsent(level,ignored -> new BoundedLoadingCache<>(2048));}
        float size=config.dataDriven().map(DataDrivenSettings::sizeMultiplier)
                .orElseGet(() -> liveSizeMultiplier(config.targets().getFirst().state.getBlock()));
        return cache.get(new CompactPlanKey(config,x,z,Float.floatToIntBits(size)),
                () -> Optional.ofNullable(createCompactCandidate(level,config,x,z)));
    }

    static long[] compactPlanCacheStatistics(ServerLevel level) {
        synchronized (COMPACT_PLANS) {
            var cache = COMPACT_PLANS.get(level);
            return cache == null ? new long[]{0,0,0} : new long[]{cache.size(),cache.hits(),cache.misses()};
        }
    }

    /**
     * Seed-only spacing arbitration. The former SavedData reservation let an already generated neighbour
     * suppress a later slice of the SAME vein. Compare priorities of potential lattice sites instead;
     * even an empty site reserves space. This is deliberately conservative and independent of load order.
     */
    private static boolean compactSpacingAllows(ServerLevel level, int chunkX, int chunkZ, int tier) {
        int minimum = OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING.get();
        int maximum = Math.max(minimum, OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING.get());
        long priority = compactSiteSeed(level, chunkX, chunkZ, tier);
        for (int otherTier = TIER_MEDIUM; otherTier <= TIER_LARGE; otherTier++) {
            OreDepositTier publicTier = OreDepositTier.fromIndex(otherTier).orElseThrow();
            int cellChunks = DepositLayouts.forTier(publicTier).cellSizeChunks();
            int search = (maximum + 31) / 16;
            int minX = Math.floorDiv(chunkX - search, cellChunks), maxX = Math.floorDiv(chunkX + search, cellChunks);
            int minZ = Math.floorDiv(chunkZ - search, cellChunks), maxZ = Math.floorDiv(chunkZ + search, cellChunks);
            for (int cx = minX; cx <= maxX; cx++) {
                for (int cz = minZ; cz <= maxZ; cz++) {
                    long anchor = DepositLayouts.anchorChunkKey(publicTier, cx, cz);
                    int ax = DepositLayouts.chunkX(anchor), az = DepositLayouts.chunkZ(anchor);
                    if (ax == chunkX && az == chunkZ && otherTier == tier) continue;
                    long other = compactSiteSeed(level, ax, az, otherTier);
                    if (Long.compareUnsigned(other, priority) >= 0) continue;
                    DepositSpacingMath.SpacingBounds bounds = DepositSpacingMath.tierPairBounds(
                            tier, otherTier, TIER_MEDIUM, TIER_LARGE, minimum, maximum);
                    double required = DepositSpacingMath.requiredDistance(
                            CompactDepositGeometry.unit(priority, 1), CompactDepositGeometry.unit(other, 1),
                            bounds.minimum(), bounds.maximum(), OreDepositConfig.LARGE_DEPOSIT_SPACING_CURVE,
                            OreDepositConfig.LARGE_DEPOSIT_SPACING_VARIATION,
                            CompactDepositGeometry.unit(priority ^ other, 2) * 2 - 1).requiredDistance();
                    // Centers are inside their source chunks; allow for their independent XZ offsets.
                    double dx = (ax - chunkX) * 16.0, dz = (az - chunkZ) * 16.0;
                    if (Math.hypot(dx, dz) < required + 23) return false;
                }
            }
        }
        return true;
    }

    private static long compactSiteSeed(ServerLevel level, int chunkX, int chunkZ, int tier) {
        return DepositSeedMath.cellSeed(level.getSeed(), level.dimension().location().toString(), "", "",
                DepositLayouts.tierSalt(tier), chunkX, chunkZ, 0, DEPOSIT_PLAN_SALT, ANCHOR_SEED_X, ANCHOR_SEED_Z);
    }

    /** Called by the existing decoration hook. Only the current chunk's blocks and attachments are written. */
    static void placeCompactDeposits(WorldGenLevel level, ChunkPos receivingChunk, Configuration config) {
        if (config.targets().isEmpty() || !depositStatesRegistered()) return;
        if (OreDimensionOverrides.isDisabled(config.targets().getFirst().state.getBlock(),
                level.getLevel().dimension().location())) return;
        DataDrivenSettings settings = config.dataDriven().orElse(null);
        if (settings != null && (!settings.matchesDimension(level.getLevel().dimension().location()) || settings.forced())) return;
        OreDepositTier tier = OreDepositTier.fromIndex(config.sizeTier()).orElseThrow();
        int cellBlocks = DepositLayouts.forTier(tier).cellSizeBlocks();
        int reach = CompactDepositGeometry.MAX_HORIZONTAL_REACH + (settings==null?MAX_TAGGED_STONE_RELOCATION_RADIUS:0);
        int minCellX = Math.floorDiv(receivingChunk.getMinBlockX() - reach, cellBlocks);
        int maxCellX = Math.floorDiv(receivingChunk.getMaxBlockX() + reach, cellBlocks);
        int minCellZ = Math.floorDiv(receivingChunk.getMinBlockZ() - reach, cellBlocks);
        int maxCellZ = Math.floorDiv(receivingChunk.getMaxBlockZ() + reach, cellBlocks);
        for (int cx = minCellX; cx <= maxCellX; cx++) {
            for (int cz = minCellZ; cz <= maxCellZ; cz++) {
                if (settings == null) {
                    long source = DepositLayouts.anchorChunkKey(tier, cx, cz);
                    int sx = DepositLayouts.chunkX(source) * 16, sz = DepositLayouts.chunkZ(source) * 16;
                    if (sx + 15 + reach < receivingChunk.getMinBlockX() || sx - reach > receivingChunk.getMaxBlockX()
                            || sz + 15 + reach < receivingChunk.getMinBlockZ() || sz - reach > receivingChunk.getMaxBlockZ()) continue;
                }
                Optional<DepositCandidate> candidate=cachedCompactCandidate(level.getLevel(),config,cx,cz);
                if(candidate.isEmpty()||!intersectsCompactChunk(candidate.get(),receivingChunk))continue;
                Optional<CompactPlan> plan = cachedCompactPlan(level.getLevel(), config, cx, cz);
                if (plan.isPresent() && intersectsCompactChunk(plan.get().candidate(),receivingChunk)
                        && compactPlanWins(level.getLevel(),plan.get()))
                    placeCompactSlice(level, receivingChunk, plan.get(), settings == null);
            }
        }
    }

    private static boolean intersectsCompactChunk(DepositCandidate c,ChunkPos chunk) {
        return c.center().getX()+c.radiusX()>=chunk.getMinBlockX()&&c.center().getX()-c.radiusX()<=chunk.getMaxBlockX()
                &&c.center().getZ()+c.radiusZ()>=chunk.getMinBlockZ()&&c.center().getZ()-c.radiusZ()<=chunk.getMaxBlockZ();
    }

    static boolean compactPlanWins(ServerLevel level, CompactPlan plan) {
        DepositCandidate candidate = plan.candidate();
        int reach = CompactDepositGeometry.MAX_HORIZONTAL_REACH * 2 + 1 + MAX_TAGGED_STONE_RELOCATION_RADIUS;
        List<Configuration> configurations = new ArrayList<>(ReplaceOreFeaturesBiomeModifier.compactConfigurationsForChunk(
                level,new ChunkPos(candidate.center()),reach));
        configurations.addAll(DataDrivenOreDepositBiomeModifier.compactConfigurations());
        for(CompactPlan forced:ConfirmedDepositIndex.get(level).forcedPlansNear(candidate)) {
            if(candidate.depositId()!=forced.candidate().depositId()
                    && (!compactPlanWinsAgainst(plan,forced)||!compactPlanWinsAgainst(forced,plan)))return false;
        }
        for (Configuration config : configurations) {
            DataDrivenSettings settings = config.dataDriven().orElse(null);
            if (settings != null && !settings.matchesDimension(level.dimension().location())) continue;
            int cellSize = DepositLayouts.forTier(OreDepositTier.fromIndex(config.sizeTier()).orElseThrow()).cellSizeBlocks();
            for (int x = Math.floorDiv(candidate.center().getX()-reach,cellSize); x <= Math.floorDiv(candidate.center().getX()+reach,cellSize); x++) {
                for (int z = Math.floorDiv(candidate.center().getZ()-reach,cellSize); z <= Math.floorDiv(candidate.center().getZ()+reach,cellSize); z++) {
                    Optional<DepositCandidate> otherCandidate=cachedCompactCandidate(level,config,x,z);
                    if(otherCandidate.isEmpty()||compactCandidateWinsAgainst(candidate,otherCandidate.get()))continue;
                    Optional<CompactPlan> other = cachedCompactPlan(level,config,x,z);
                    if (other.isPresent() && !compactPlanWinsAgainst(plan,other.get())) return false;
                }
            }
        }
        return true;
    }

    /** Conservative AABB arbitration also leaves one block of host rock between independent bodies. */
    static boolean compactPlanWinsAgainst(CompactPlan plan, CompactPlan other) {
        return compactCandidateWinsAgainst(plan.candidate(),other.candidate());
    }

    private static boolean compactCandidateWinsAgainst(DepositCandidate a,DepositCandidate b) {
        if (a.depositId() == b.depositId()) return true;
        boolean touches = Math.abs((long)a.center().getX()-b.center().getX()) <= a.radiusX()+b.radiusX()+1
                && Math.abs((long)a.center().getY()-b.center().getY()) <= a.verticalRadius()+b.verticalRadius()+1
                && Math.abs((long)a.center().getZ()-b.center().getZ()) <= a.radiusZ()+b.radiusZ()+1;
        return !touches || Long.compareUnsigned(a.depositId(), b.depositId()) < 0;
    }

    private static DepositCandidate createCompactCandidate(ServerLevel level, Configuration config, int cellX, int cellZ) {
        int tier = config.sizeTier();
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(config.targets().getFirst().state.getBlock());
        ResourceLocation biome = config.sourceBiome().orElse(null);
        DataDrivenSettings settings = config.dataDriven().orElse(null);
        DepositCandidate candidate;
        float size = settings == null ? liveSizeMultiplier(config.targets().getFirst().state.getBlock()) : settings.sizeMultiplier();
        if (settings == null) {
            long anchor = DepositLayouts.anchorChunkKey(OreDepositTier.fromIndex(tier).orElseThrow(), cellX, cellZ);
            int ax = DepositLayouts.chunkX(anchor), az = DepositLayouts.chunkZ(anchor);
            String material = OreUnifier.materialKeyFor(config.targets().getFirst().state.getBlock());
            if (!matchesAutomaticSourceBiome(level, ax, az, biome)
                    || !predictsMaterialForChunk(level, ax, az, tier, material, biome)) return null;
            candidate = createPlannedCandidate(level, ax, az, tier, oreId, material, size, biome);
            if (biome != null) {
                candidate = relocatePredictedCandidateToSourceBiome(level, candidate, biome,
                        config.targets().stream().map(target -> target.target).toList());
                if (candidate == null) return null;
            }
        } else {
            if (biome == null || !passesDataDrivenFrequency(level, cellX, cellZ, biome, oreId, tier, settings)) return null;
            candidate = createDataDrivenCandidate(level, cellX, cellZ, biome, oreId, tier, settings);
            if (!isNoiseBiome(level, candidate.center(), biome)) return null;
        }
        return fitCompactCandidate(level,config,candidate);
    }

    private static DepositCandidate fitCompactCandidate(ServerLevel level,Configuration config,DepositCandidate candidate) {
        ResourceLocation biome=config.sourceBiome().orElse(null);
        CompactDepositGeometry geometry = compactGeometry(candidate.shapeSeed(), config.sizeTier(), candidate.geometrySize());
        java.util.OptionalInt fittedY = geometry.fitOriginY(candidate.center().getY(),
                level.getMinBuildHeight() + BEDROCK_CLEARANCE, level.getMaxBuildHeight());
        if (fittedY.isEmpty()) return null;
        if (fittedY.getAsInt() != candidate.center().getY()) {
            candidate = candidate.withUndergroundCenter(new BlockPos(candidate.center().getX(),fittedY.getAsInt(),candidate.center().getZ()));
            if (biome != null && !isNoiseBiome(level,candidate.center(),biome)) return null;
        }
        return candidate;
    }

    static CompactPlan buildCompactPlanAt(ServerLevel level,Configuration config,DepositCandidate candidate) {
        candidate=fitCompactCandidate(level,config,candidate);
        if(candidate==null)return null;
        int tier=config.sizeTier();
        DataDrivenSettings settings=config.dataDriven().orElse(null);
        CompactDepositGeometry geometry=compactGeometry(candidate.shapeSeed(),tier,candidate.geometrySize());
        ServerChunkCache source = level.getChunkSource();
        Map<CompactDepositGeometry.Cell, OreConfiguration.TargetBlockState> targets = new IdentityHashMap<>();
        List<CompactDepositGeometry.Cell> eligible = new ArrayList<>();
        BlockPos center = candidate.center();
        int quantity=compactTotalOre(candidate.shapeSeed(),tier,0,
                settings==null?config.richnessMultiplier():settings.richnessMultiplier());
        List<CompactDepositGeometry.Cell> physical=CompactDepositGeometry.selectOreCells(geometry.cells(),
                CompactDepositGeometry.oreBlockBudget(quantity),center.getX(),center.getZ());
        if(physical.isEmpty())return null;
        BlockState[] terrain = CompactDepositTerrain.sample(source.getGenerator(), source.randomState(), level, center, physical);
        List<OreConfiguration.TargetBlockState> lateTargets=DataDrivenOreDepositBiomeModifier.lateReplacementTargets(level,config);
        Map<Long,Boolean> predictedBiomes=new java.util.HashMap<>();
        RandomSource targetRandom=RandomSource.create(0);
        for (int cellIndex = 0; cellIndex < physical.size(); cellIndex++) {
            CompactDepositGeometry.Cell cell = physical.get(cellIndex);
            int x = center.getX() + cell.x(), y = center.getY() + cell.y(), z = center.getZ() + cell.z();
            if (y < level.getMinBuildHeight() + BEDROCK_CLEARANCE || y >= level.getMaxBuildHeight()) continue;
            BlockState state = terrain[cellIndex];
            targetRandom.setSeed(candidate.shapeSeed() ^ BlockPos.asLong(x,y,z));
            OreConfiguration.TargetBlockState target = matchingTarget(config.targets(), state,targetRandom, settings == null);
            if(target==null && !lateTargets.isEmpty() && state.getFluidState().isEmpty() && state.is(ModBlockTags.STONES)) {
                // Remote base columns predate cave-mod rock conversion. This grants planning eligibility only;
                // compactHostIsUncut/placeCompactSlice still require the exact configured host in real terrain.
                int qx=QuartPos.fromBlock(x),qy=QuartPos.fromBlock(y),qz=QuartPos.fromBlock(z);
                long key=BlockPos.asLong(qx,qy,qz);
                // Forced centers already came from the chunk's actual biome palette. Cave mods may not
                // expose that biome through the remote noise source; do not reject that verified center.
                if(settings.forced() || predictedBiomes.computeIfAbsent(key,ignored -> DataDrivenOreDepositBiomeModifier.matchesPlannedBiome(
                        level,new BlockPos(QuartPos.toBlock(qx),QuartPos.toBlock(qy),QuartPos.toBlock(qz)),config.sourceBiome().orElseThrow())))
                    target=lateTargets.getFirst();
            }
            if (target != null && state.getFluidState().isEmpty()) {
                eligible.add(cell);
                targets.put(cell, target);
            }
        }
        List<CompactDepositGeometry.Cell> connected = CompactDepositGeometry.connected(eligible, geometry.cells().size());
        if (connected.size() < Math.max(4, physical.size() * 0.60)) {
            OresAndDrillsMod.LOGGER.debug("Compact ore patch {} rejected during terrain planning: {} connected/{} physical blocks; source biome={}, late targets={}",
                    Long.toUnsignedString(candidate.depositId()),connected.size(),physical.size(),config.sourceBiome(),lateTargets.size());
            return null;
        }
        int diameter=Math.max(connected.stream().mapToInt(CompactDepositGeometry.Cell::x).max().orElseThrow()
                -connected.stream().mapToInt(CompactDepositGeometry.Cell::x).min().orElseThrow()+1,
                connected.stream().mapToInt(CompactDepositGeometry.Cell::z).max().orElseThrow()
                -connected.stream().mapToInt(CompactDepositGeometry.Cell::z).min().orElseThrow()+1);
        if(diameter<CompactDepositGeometry.minimumDiameter(tier==TIER_LARGE))return null;
        int total = compactTotalOre(candidate.shapeSeed(), tier, connected.size(),
                settings == null ? config.richnessMultiplier() : settings.richnessMultiplier());
        // Filtering host rock must never silently truncate the configured reserve.
        if ((long) connected.size() * CompactDepositGeometry.MAX_UNITS_PER_BLOCK < total) return null;
        int[] amounts = DepositTierMath.distributeOre(total, connected.stream().mapToDouble(CompactDepositGeometry.Cell::weight).toArray(), CompactDepositGeometry.maximumBlockReserve(total,connected.size()));
        return new CompactPlan(candidate.withExpectedBlocks(connected.size()), connected, connected.stream().map(targets::get).toList(), amounts);
    }

    private static int compactTotalOre(long seed, int tier, int blocks, float richness) {
        return CompactDepositGeometry.totalOre(seed, tier == TIER_LARGE, blocks, richness);
    }

    private boolean placeForcedCompact(FeaturePlaceContext<Configuration> context) {
        Configuration config=context.config();WorldGenLevel level=context.level();
        DataDrivenSettings settings=config.dataDriven().orElseThrow();
        if(config.targets().isEmpty()||config.sourceBiome().isEmpty()||!depositStatesRegistered()
                ||!settings.matchesDimension(level.getLevel().dimension().location()))return false;
        ChunkPos chunk=level instanceof WorldGenRegion region?region.getCenter():new ChunkPos(context.origin());
        DepositCandidate candidate=reserveForcedDeposit(level,chunk.x,chunk.z,config.sizeTier(),
                BuiltInRegistries.BLOCK.getKey(config.targets().getFirst().state.getBlock()),config.sourceBiome().get(),
                settings,DepositLayouts.forTier(OreDepositTier.fromIndex(config.sizeTier()).orElseThrow()));
        if(candidate==null)return false;
        CompactPlan plan=buildCompactPlanAt(level.getLevel(),config,candidate);
        if(plan==null||!compactPlanWins(level.getLevel(),plan))return false;
        ConfirmedDepositIndex index=ConfirmedDepositIndex.get(level.getLevel());
        if(!index.tryBeginSlice(candidate.depositId(),chunk))return false;
        try {
            if(!compactHostIsUncut(level,plan,false)||!index.registerStartingForcedPlan(plan))return false;
            try {
                placeClaimedCompactSlice(level,chunk,plan,false,index);
            } finally {
                if(!index.hasSlice(candidate.depositId(),chunk))index.removeForcedPlan(candidate.depositId());
            }
            if(!index.hasSlice(candidate.depositId(),chunk))return false;
        } finally {
            index.endSlice(candidate.depositId(),chunk);
        }
        // Only already FULL chunks may receive late forced slices, on the server thread.
        // No getChunk/create=true, no writes into neighbours during FEATURES.
        ServerLevel server=level.getLevel();
        server.getServer().tell(new net.minecraft.server.TickTask(server.getServer().getTickCount()+1,() -> {
            Set<ChunkPos> chunks=new java.util.HashSet<>();
            for(var c:plan.cells())chunks.add(new ChunkPos(plan.candidate().center().offset(c.x(),c.y(),c.z())));
            for(ChunkPos pos:chunks)if(server.getChunkSource().getChunkNow(pos.x,pos.z)!=null)
                placePendingForcedSlices(server,pos);
        }));
        return true;
    }

    static void placePendingForcedSlices(WorldGenLevel level,ChunkPos chunk) {
        ConfirmedDepositIndex index=ConfirmedDepositIndex.get(level.getLevel());
        for(CompactPlan plan:index.forcedPlansFor(chunk))
            if(index.forcedPlanReady(plan.candidate().depositId()))placeCompactSlice(level,chunk,plan,false);
    }

    public static void onCompactChunkLoad(net.neoforged.neoforge.event.level.ChunkEvent.Load event) {
        if(event.getLevel() instanceof ServerLevel level) {
            ChunkPos pos=event.getChunk().getPos();
            level.getServer().tell(new net.minecraft.server.TickTask(level.getServer().getTickCount()+1,() -> {
                if(level.getChunkSource().getChunkNow(pos.x,pos.z)!=null)placePendingForcedSlices(level,pos);
            }));
        }
    }

    static net.minecraft.nbt.CompoundTag saveCompactPlan(CompactPlan plan,net.minecraft.core.HolderLookup.Provider registries) {
        var tag=new net.minecraft.nbt.CompoundTag();var c=plan.candidate();
        tag.putLong("Id",c.depositId());tag.putString("Ore",c.oreId().toString());tag.putInt("Tier",c.tier().index());
        tag.putLong("Center",c.center().asLong());tag.putLong("Seed",c.shapeSeed());tag.putFloat("Size",c.geometrySize());
        tag.putIntArray("Radii",new int[]{c.radiusX(),c.radiusZ(),c.verticalRadius()});
        List<OreConfiguration.TargetBlockState> palette=new ArrayList<>();
        int[] coordinates=new int[plan.cells().size()*4];
        for(int i=0;i<plan.cells().size();i++) {
            var cell=plan.cells().get(i);var target=plan.targets().get(i);
            int targetIndex=palette.indexOf(target);if(targetIndex<0){targetIndex=palette.size();palette.add(target);}
            coordinates[i*4]=cell.x();coordinates[i*4+1]=cell.y();coordinates[i*4+2]=cell.z();coordinates[i*4+3]=targetIndex;
        }
        boolean compact=palette.size()<=256;
        for(int i=0;i<coordinates.length;i++)if((i&3)!=3 && (coordinates[i]<-128||coordinates[i]>127))compact=false;
        if(compact) {
            byte[] packed=new byte[coordinates.length],amounts=new byte[plan.amounts().length*2];
            for(int i=0;i<coordinates.length;i++)packed[i]=(byte)coordinates[i];
            for(int i=0;i<plan.amounts().length;i++) {
                amounts[i*2]=(byte)plan.amounts()[i];amounts[i*2+1]=(byte)(plan.amounts()[i]>>>8);
            }
            tag.putByteArray("PackedCells",packed);tag.putByteArray("PackedAmounts",amounts);
        } else throw new IllegalArgumentException("Compact plan exceeds packed format bounds");
        tag.putInt("Format",1);
        tag.put("Targets",OreConfiguration.TargetBlockState.CODEC.listOf().encodeStart(
                registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),palette).getOrThrow());
        return tag;
    }

    static CompactPlan loadCompactPlan(net.minecraft.nbt.CompoundTag tag,net.minecraft.core.HolderLookup.Provider registries) {
        if(tag.getInt("Format")!=1)throw new IllegalArgumentException("Unsupported compact plan format");
        int[] coords,amounts,radii=tag.getIntArray("Radii");
        if(tag.contains("PackedCells",net.minecraft.nbt.Tag.TAG_BYTE_ARRAY)) {
            byte[] packed=tag.getByteArray("PackedCells"),reserves=tag.getByteArray("PackedAmounts");
            if(packed.length%4!=0 || reserves.length!=(long)packed.length/2)throw new IllegalArgumentException("Invalid packed compact plan");
            coords=new int[packed.length];amounts=new int[reserves.length/2];
            for(int i=0;i<packed.length;i++)coords[i]=(i&3)==3?packed[i]&255:packed[i];
            for(int i=0;i<amounts.length;i++)amounts[i]=(reserves[i*2]&255)|((reserves[i*2+1]&255)<<8);
        } else throw new IllegalArgumentException("Missing packed compact plan");
        if(radii.length!=3||coords.length!=amounts.length*4||amounts.length<1||amounts.length>24_000)
            throw new IllegalArgumentException("Invalid compact plan arrays");
        var palette=OreConfiguration.TargetBlockState.CODEC.listOf().parse(
                registries.createSerializationContext(net.minecraft.nbt.NbtOps.INSTANCE),tag.get("Targets")).getOrThrow();
        BlockPos center=BlockPos.of(tag.getLong("Center"));
        var candidate=new DepositCandidate(tag.getLong("Id"),ResourceLocation.parse(tag.getString("Ore")),
                OreDepositTier.fromIndex(tag.getInt("Tier")).orElseThrow(),center,radii[0],radii[1],radii[2],tag.getLong("Seed"),
                null,amounts.length,center.getX()>>4,center.getZ()>>4,tag.getFloat("Size"));
        List<CompactDepositGeometry.Cell> cells=new ArrayList<>();List<OreConfiguration.TargetBlockState> targets=new ArrayList<>();
        for(int i=0;i<amounts.length;i++) {
            if(amounts[i]<1||amounts[i]>CompactDepositGeometry.MAX_UNITS_PER_BLOCK)throw new IllegalArgumentException("Invalid reserve");
            cells.add(new CompactDepositGeometry.Cell(coords[i*4],coords[i*4+1],coords[i*4+2],1));
            targets.add(palette.get(coords[i*4+3]));
        }
        return new CompactPlan(candidate,List.copyOf(cells),List.copyOf(targets),amounts);
    }

    static void placeCompactSlice(WorldGenLevel level, ChunkPos chunk, CompactPlan plan, boolean stoneFallback) {
        ConfirmedDepositIndex index = ConfirmedDepositIndex.get(level.getLevel());
        long id=plan.candidate().depositId();
        // Duplicate/reentrant commits skip immediately. Other chunks can still consult the
        // index while a block callback waits for their generation (e.g. Sable physics).
        if(!index.tryBeginSlice(id,chunk))return;
        try {
            placeClaimedCompactSlice(level,chunk,plan,stoneFallback,index);
        } finally {
            index.endSlice(id,chunk);
        }
    }

    private static void placeClaimedCompactSlice(WorldGenLevel level, ChunkPos chunk, CompactPlan plan,
            boolean stoneFallback, ConfirmedDepositIndex index) {
        DepositWorldgenGuard.protectPlan(level.getLevel(),plan,chunk);
        DepositCandidate candidate = plan.candidate();
        if (candidate.center().getX() + candidate.radiusX() < chunk.getMinBlockX()
                || candidate.center().getX() - candidate.radiusX() > chunk.getMaxBlockX()
                || candidate.center().getZ() + candidate.radiusZ() < chunk.getMinBlockZ()
                || candidate.center().getZ() - candidate.radiusZ() > chunk.getMaxBlockZ()) return;
        // Base-column planning predates gravel, caves and structures added by decoration.
        // Clip individual invalid hosts instead of discarding a whole chunk of the patch.
        OreDepositPaletteData palette = OreDepositPaletteData.get(level.getLevel());
        Map<Block, Integer> ores = new IdentityHashMap<>(), bases = new IdentityHashMap<>();
        List<OreDepositData.GeneratedDeposit> generated = new ArrayList<>();
        List<BlockState> originalHosts = new ArrayList<>();
        List<CompactDepositGeometry.Cell> accepted = new ArrayList<>();
        List<BoundingBox> structures = plannedStructureBoxes(level);
        Map<Long,Map<Long,BlockState>> gravelHosts = new java.util.HashMap<>();
        Map<String,Integer> clipped = new java.util.LinkedHashMap<>();
        int plannedBlocks = 0, sliceReserve = 0;
        BlockState depositState = ModBlocks.ORE_DEPOSIT.get().defaultBlockState();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        RandomSource targetRandom=RandomSource.create(0);
        for (int i : plan.indices(chunk)) {
            CompactDepositGeometry.Cell cell = plan.cells().get(i);
            pos.set(candidate.center().getX() + cell.x(), candidate.center().getY() + cell.y(), candidate.center().getZ() + cell.z());
            plannedBlocks++;
            sliceReserve += plan.amounts()[i];
            BlockState host = level.getBlockState(pos);
            BlockState originalHost = host;
            if(host.is(Blocks.GRAVEL))host=baseHostBeforeGravel(level,pos,plan,gravelHosts);
            String reason = host.is(ModBlocks.ORE_DEPOSIT.get()) ? "existing_deposit"
                    : !host.getFluidState().isEmpty() ? "fluid" : null;
            if (reason == null) for (BoundingBox structure : structures) if (structure.isInside(pos)) {
                reason = "structure"; break;
            }
            OreConfiguration.TargetBlockState target = plan.targets().get(i);
            targetRandom.setSeed(candidate.shapeSeed() ^ pos.asLong());
            if (reason == null && matchingTarget(List.of(target), host,targetRandom, stoneFallback) == null)
                reason = "host:" + BuiltInRegistries.BLOCK.getKey(host.getBlock());
            if (reason != null) { clipped.merge(reason,1,Integer::sum); continue; }
            int ore = ores.computeIfAbsent(target.state.getBlock(), palette::oreIndex);
            int base = bases.computeIfAbsent(host.getBlock(), block -> {
                int baseIndex = palette.baseIndex(block);
                return baseIndex >= 0 ? baseIndex : OreDepositStonePalette.nearestByMapColor(level.getLevel(), target.state.getMapColor(level, pos).col);
            });
            if (ore < 0 || base < 0 || !level.ensureCanWrite(pos)) {
                OresAndDrillsMod.LOGGER.debug("Compact ore patch {} chunk [{},{}] rejected: palette or write bounds at {}",
                        Long.toUnsignedString(candidate.depositId()),chunk.x,chunk.z,pos);
                return;
            }
            generated.add(new OreDepositData.GeneratedDeposit(pos.immutable(), base, ore, plan.amounts()[i], candidate.tier().index()));
            originalHosts.add(originalHost);
            accepted.add(cell);
        }
        if (plannedBlocks == 0) return;
        if (generated.isEmpty() || (long)generated.size() * CompactDepositGeometry.MAX_UNITS_PER_BLOCK < sliceReserve) {
            OresAndDrillsMod.LOGGER.debug("Compact ore patch {} chunk [{},{}] rejected: {} valid/{} planned blocks cannot hold {} units; clipped={}",
                    Long.toUnsignedString(candidate.depositId()),chunk.x,chunk.z,generated.size(),plannedBlocks,sliceReserve,clipped);
            return;
        }
        ChunkAccess owner=level.getChunk(chunk.x,chunk.z);
        OreDepositChunkData existing=owner.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        int compactLimit=CompactDepositGeometry.MAX_ORE_BLOCKS_PER_CHUNK + MAX_DEPOSIT_ENTRIES_PER_CHUNK;
        if (generated.size() + (existing==null?0:existing.size()) > compactLimit) {
            OresAndDrillsMod.LOGGER.debug("Compact ore patch {} chunk [{},{}] rejected: attachment capacity {}",
                    Long.toUnsignedString(candidate.depositId()),chunk.x,chunk.z,compactLimit);
            return;
        }
        if (!clipped.isEmpty()) {
            // Keep the chunk's immutable share of Q. Other chunks retain their own shares,
            // so clipping cannot duplicate reserves or depend on their generation order.
            int[] amounts=DepositTierMath.distributeOre(sliceReserve,
                    accepted.stream().mapToDouble(CompactDepositGeometry.Cell::weight).toArray(),CompactDepositGeometry.MAX_UNITS_PER_BLOCK);
            for(int i=0;i<generated.size();i++) {
                var entry=generated.get(i);
                generated.set(i,new OreDepositData.GeneratedDeposit(entry.pos(),entry.baseIndex(),entry.oreIndex(),amounts[i],entry.tier()));
            }
            OresAndDrillsMod.LOGGER.debug("Compact ore patch {} chunk [{},{}] clipped {} of {} blocks: {}; redistributed {} units",
                    Long.toUnsignedString(candidate.depositId()),chunk.x,chunk.z,plannedBlocks-generated.size(),plannedBlocks,clipped,sliceReserve);
        }
        // Prepare the complete current-chunk batch before any mutation. Unexpected write failure
        // rolls back that batch; no partial resources or confirmation are committed.
        int updateFlags=level instanceof ServerLevel?Block.UPDATE_CLIENTS:Block.UPDATE_NONE;
        for (int i = 0; i < generated.size(); i++) {
            if (!level.setBlock(generated.get(i).pos(), depositState, updateFlags)) {
                for (int j = i - 1; j >= 0; j--) level.setBlock(generated.get(j).pos(), originalHosts.get(j), updateFlags);
                OresAndDrillsMod.LOGGER.debug("Compact ore patch {} chunk [{},{}] rolled back: block write failed at {}",
                        Long.toUnsignedString(candidate.depositId()),chunk.x,chunk.z,generated.get(i).pos());
                return;
            }
        }
        OreDepositData.setGeneratedBatch(level, generated);
        if (!generated.isEmpty()) {
            if(level instanceof ServerLevel server)server.getChunk(chunk.x,chunk.z).syncData(ModAttachments.ORE_DEPOSITS);
            int occupiedSections = (int)generated.stream().mapToLong(entry -> net.minecraft.core.SectionPos.asLong(entry.pos())).distinct().count();
            index.confirmSlice(candidate, generated.size(), chunk, occupiedSections);
            OresAndDrillsMod.LOGGER.debug("Compact ore patch {} {} chunk [{},{}]: {}/{} blocks, {} units (complete plan: {} blocks, {} units)",
                    Long.toUnsignedString(candidate.depositId()), candidate.tier(), chunk.x, chunk.z,
                    generated.size(), plan.cells().size(), generated.stream().mapToInt(OreDepositData.GeneratedDeposit::amount).sum(),
                    plan.cells().size(), plan.totalOre());
        }
    }

    private static BlockState baseHostBeforeGravel(WorldGenLevel level,BlockPos pos,CompactPlan plan,
            Map<Long,Map<Long,BlockState>> chunks) {
        // Forced centers are chosen after earlier decoration. Their gravel was not yet
        // protected by a known plan; recover only the predicted base rock at this cell.
        long chunkKey=ChunkPos.asLong(pos.getX()>>4,pos.getZ()>>4);
        Map<Long,BlockState> hosts=chunks.computeIfAbsent(chunkKey,ignored -> {
            List<CompactDepositGeometry.Cell> gravel=new ArrayList<>();
            BlockPos center=plan.candidate().center();
            BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();
            for(int i:plan.indices(new ChunkPos(chunkKey))) {
                var cell=plan.cells().get(i);
                cursor.set(center.getX()+cell.x(),center.getY()+cell.y(),center.getZ()+cell.z());
                if(level.getBlockState(cursor).is(Blocks.GRAVEL))gravel.add(cell);
            }
            ServerChunkCache source=level.getLevel().getChunkSource();
            BlockState[] terrain=CompactDepositTerrain.sample(source.getGenerator(),source.randomState(),level.getLevel(),center,gravel);
            Map<Long,BlockState> recovered=new java.util.HashMap<>();
            for(int i=0;i<gravel.size();i++) {
                var cell=gravel.get(i);BlockState base=terrain[i];
                if(base.getFluidState().isEmpty() && base.is(ModBlockTags.STONES))
                    recovered.put(BlockPos.asLong(center.getX()+cell.x(),center.getY()+cell.y(),center.getZ()+cell.z()),base);
            }
            return recovered;
        });
        return hosts.getOrDefault(pos.asLong(),Blocks.GRAVEL.defaultBlockState());
    }

    private static boolean compactHostIsUncut(WorldGenLevel level, CompactPlan plan, boolean stoneFallback) {
        ChunkPos receiving=level instanceof WorldGenRegion region?region.getCenter():new ChunkPos(plan.candidate().center());
        return compactHostIsUncut(level,plan,stoneFallback,receiving);
    }

    private static boolean compactHostIsUncut(WorldGenLevel level, CompactPlan plan, boolean stoneFallback,ChunkPos receiving) {
        DepositCandidate candidate = plan.candidate();
        ConfirmedDepositIndex index = ConfirmedDepositIndex.get(level.getLevel());
        OreDepositPaletteData palette = OreDepositPaletteData.get(level.getLevel());
        Map<Block, Integer> oreIndices = new IdentityHashMap<>();
        List<BoundingBox> structures = plannedStructureBoxes(level);
        Map<Long,Map<Long,BlockState>> gravelHosts = new java.util.HashMap<>();
        Map<Long, Integer> newPerChunk = new java.util.HashMap<>();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int i = 0; i < plan.cells().size(); i++) {
            CompactDepositGeometry.Cell cell = plan.cells().get(i);
            pos.set(candidate.center().getX() + cell.x(), candidate.center().getY() + cell.y(), candidate.center().getZ() + cell.z());
            if (index.hasSlice(candidate.depositId(), pos.getX() >> 4, pos.getZ() >> 4)) continue;
            // Far chunks in FEATURES may have only reached BIOMES/NOISE. Never require their
            // carved terrain or upgrade their status: each chunk validates only its own slice.
            if(!(level instanceof ServerLevel) && ((pos.getX()>>4)!=receiving.x||(pos.getZ()>>4)!=receiving.z))continue;
            if (level instanceof WorldGenRegion region && !region.hasChunk(pos.getX() >> 4, pos.getZ() >> 4)) continue;
            if (level instanceof ServerLevel server && server.getChunkSource().getChunkNow(pos.getX() >> 4,pos.getZ() >> 4)==null) continue;
            BlockState state = level.getBlockState(pos);
            if(state.is(Blocks.GRAVEL))state=baseHostBeforeGravel(level,pos,plan,gravelHosts);
            if (!state.getFluidState().isEmpty()) return false;
            for (BoundingBox structure : structures) if (structure.isInside(pos)) return false;
            ChunkAccess owner = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            OreDepositChunkData data = owner.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
            OreConfiguration.TargetBlockState target = plan.targets().get(i);
            int oreIndex = oreIndices.computeIfAbsent(target.state.getBlock(), palette::oreIndex);
            if (oreIndex < 0) return false;
            if (state.is(ModBlocks.ORE_DEPOSIT.get())) {
                // Matching ore/amount alone cannot identify an independently generated deposit.
                // Our completed slices were skipped by their persistent identity above.
                return false;
            }
            if (matchingTarget(List.of(target), state,
                    RandomSource.create(candidate.shapeSeed() ^ pos.asLong()), stoneFallback) == null) return false;
            long key = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            int additions = newPerChunk.merge(key, 1, Integer::sum);
            // A complete LARGE column occupies at most seven layers. Keep the TINY/SMALL
            // allowance in addition to this compact body's bounded volume.
            int compactLimit = CompactDepositGeometry.MAX_ORE_BLOCKS_PER_CHUNK + MAX_DEPOSIT_ENTRIES_PER_CHUNK;
            if (additions + (data == null ? 0 : data.size()) > compactLimit) return false;
        }
        return true;
    }

    private static boolean depositStatesRegistered() {
        Boolean cached = cachedDepositStatesRegistered;
        if (cached != null) {
            return cached;
        }

        Block depositBlock = ModBlocks.ORE_DEPOSIT.get();
        boolean registered = depositBlock.getStateDefinition().getPossibleStates().stream()
                .allMatch(state -> Block.BLOCK_STATE_REGISTRY.getId(state) >= 0);
        cachedDepositStatesRegistered = registered;
        return registered;
    }

    @Override
    public boolean place(FeaturePlaceContext<Configuration> context) {
        boolean placed = false;
        try {
            placed = placeAttempt(context);
            return placed;
        } finally {
            // A forced datapack slot reserved by this attempt is kept only if the deposit really exists;
            // otherwise another chunk of the same biome instance may take it.
            DataDrivenDepositLedger.Reservation reservation = PENDING_RESERVATION.get();
            if (reservation != null) {
                PENDING_RESERVATION.remove();
                if (placed) {
                    reservation.commit();
                } else {
                    reservation.release();
                }
            }
        }
    }

    private boolean placeAttempt(FeaturePlaceContext<Configuration> context) {
        if (!context.config().targets().isEmpty()
                && OreDimensionOverrides.isDisabled(
                        context.config().targets().getFirst().state.getBlock(),
                        context.level().getLevel().dimension().location())) {
            return false;
        }
        OreVein vanillaVein = context.config().vein().filter(OreVein::vanillaPlacement).orElse(null);
        if (vanillaVein != null) {
            return depositStatesRegistered() && placeVanillaVein(context, vanillaVein);
        }
        if (context.config().sizeTier() < TIER_MEDIUM && context.config().dataDriven().isEmpty()) {
            // Automatic TINY/SMALL: thinned original attempts. Datapack rules keep their own planner below.
            OreVein sourceVein = context.config().vein().orElse(null);
            return sourceVein != null && depositStatesRegistered() && placeSourceAttempts(context, sourceVein);
        }
        if(context.config().sizeTier()>=TIER_MEDIUM && context.config().dataDriven().map(DataDrivenSettings::forced).orElse(false))
            return placeForcedCompact(context);
        if (context.config().sizeTier() >= TIER_MEDIUM
                && !context.config().dataDriven().map(DataDrivenSettings::forced).orElse(false)) {
            // The existing decoration tail runs this category once per receiving chunk. A source
            // feature must not also write the complete deposit into its neighbours.
            return false;
        }
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Configuration config = context.config();

        // A local palette serializes each state through Block.BLOCK_STATE_REGISTRY. An id of -1 is
        // written verbatim and disconnects the client while it reads the chunk. This should never be
        // possible after normal registry setup, but checking the deposit state keeps a broken optional
        // compatibility mixin (notably a chunk-container replacement) from poisoning a save.
        if (!depositStatesRegistered()) {
            if (INVALID_DEPOSIT_STATE_LOGGED.compareAndSet(false, true)) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposit generation disabled: {} contains a block state without a network registry id",
                        ModBlocks.ORE_DEPOSIT.getId()
                );
            }
            return false;
        }

        BlockPos origin = context.origin();
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;

        int anchorTier = DepositTier.byIndex(config.sizeTier()).ordinal();
        ResourceLocation sourceBiomeId = config.sourceBiome().orElse(null);
        DataDrivenSettings dataSettings = config.dataDriven().orElse(null);
        DepositCandidate plannedCandidate = null;
        AttemptClaim claim;
        if (dataSettings != null) {
            if (sourceBiomeId == null
                    || !dataSettings.matchesDimension(level.getLevel().dimension().location())) {
                return false;
            }
            ResourceLocation oreId = config.targets().isEmpty()
                    ? null
                    : BuiltInRegistries.BLOCK.getKey(config.targets().getFirst().state.getBlock());
            if (oreId == null) {
                return false;
            }
            DepositTierLayout layout = DepositLayouts.forTier(
                    OreDepositTier.fromIndex(anchorTier).orElseThrow()
            );
            if (dataSettings.forced()) {
                plannedCandidate = reserveForcedDeposit(level, chunkX, chunkZ, anchorTier, oreId,
                        sourceBiomeId, dataSettings, layout);
                if (plannedCandidate == null) {
                    return false;
                }
                origin = plannedCandidate.center();
                claim = new AttemptClaim(plannedCandidate.depositId(), false, null);
            } else {
                int cellX = Math.floorDiv(chunkX, layout.cellSizeChunks());
                int cellZ = Math.floorDiv(chunkZ, layout.cellSizeChunks());
                plannedCandidate = createDataDrivenCandidate(
                        level.getLevel(), cellX, cellZ, sourceBiomeId, oreId, anchorTier, dataSettings
                );
                if (plannedCandidate.sourceChunkX() != chunkX || plannedCandidate.sourceChunkZ() != chunkZ) {
                    return false;
                }
                if (!passesDataDrivenFrequency(level.getLevel(), cellX, cellZ, sourceBiomeId, oreId, anchorTier, dataSettings)) {
                    return false;
                }
                ResourceKey<Biome> sourceBiomeKey = ResourceKey.create(Registries.BIOME, sourceBiomeId);
                Holder<Biome> plannedBiome = level.getBiome(plannedCandidate.center());
                if (!plannedBiome.is(sourceBiomeKey)) {
                    // Remote planning cannot always see biomes that mods install while filling a chunk
                    // (Alex's Caves). The surrounding chunks' real biome data is authoritative here.
                    BlockPos realBiomeCenter = nearestRealBiomePosition(
                            level, plannedCandidate.center(), sourceBiomeKey, dataSettings
                    );
                    if (realBiomeCenter == null) {
                        OresAndDrillsMod.LOGGER.debug(
                                "Data-driven ore-deposit candidate {} rejected: no {} near planned center {} (biome {})",
                                Long.toUnsignedString(plannedCandidate.depositId()), sourceBiomeId,
                                plannedCandidate.center(),
                                plannedBiome.unwrapKey().map(key -> key.location().toString()).orElse("<direct>")
                        );
                        return false;
                    }
                    plannedCandidate = plannedCandidate.withUndergroundCenter(realBiomeCenter);
                }
                origin = plannedCandidate.center();
                chunkX = plannedCandidate.sourceChunkX();
                chunkZ = plannedCandidate.sourceChunkZ();
                claim = new AttemptClaim(plannedCandidate.depositId(), false, null);
            }
        } else {
            claim = findEligibleAttempt(
                    level.getLevel(), origin, config.targets(), anchorTier, sourceBiomeId,
                    config.frequencyMultiplier()
            );
        }
        if (claim == null) {
            return false;
        }
        boolean rareAttemptPreclaimed = dataSettings == null
                && anchorTier >= TIER_MEDIUM && claim.requiresSharedClaim();
        if (rareAttemptPreclaimed && !CLAIMED_DEPOSIT_ATTEMPTS.add(claim.claimKey())) {
            return rejectAttempt(claim, "attempt_slot_already_claimed");
        }
        if (rareAttemptPreclaimed) {
            trimIfOversized(CLAIMED_DEPOSIT_ATTEMPTS);
        }
        List<OreConfiguration.TargetBlockState> targets = claim.selectedOreId() == null
                ? config.targets()
                : targetsForSelectedMaterial(config.targets(), claim.selectedOreId());
        if (targets.isEmpty()) {
            return rejectAttempt(claim, "selected_material_missing");
        }

        int tier = anchorTier;

        ResourceLocation plannedOreId = BuiltInRegistries.BLOCK.getKey(targets.getFirst().state.getBlock());
        if (plannedOreId == null) {
            return rejectAttempt(claim, "unregistered_ore_block");
        }
        float sizeMultiplier = dataSettings == null
                ? Mth.clamp(liveSizeMultiplier(targets.getFirst().state.getBlock()), 0.1F, 6.0F)
                : Mth.clamp(dataSettings.sizeMultiplier(), 0.1F, 6.0F);
        // Every normal tier consumes the immutable candidate plan. The surrounding placed-feature stream
        // may trigger the feature, but it no longer changes the candidate center, shape or deposit id.
        String plannedMaterialKey = OreUnifier.materialKeyFor(targets.getFirst().state.getBlock());
        if (plannedCandidate == null) {
            plannedCandidate = createPlannedCandidate(
                    level.getLevel(), chunkX, chunkZ, tier, plannedOreId,
                    plannedMaterialKey, sizeMultiplier, sourceBiomeId
            );
        }
        if (dataSettings == null && tier < TIER_MEDIUM && sourceBiomeId != null) {
            Holder<Biome> plannedBiome = level.getLevel().getUncachedNoiseBiome(
                    QuartPos.fromBlock(plannedCandidate.center().getX()),
                    QuartPos.fromBlock(plannedCandidate.center().getY()),
                    QuartPos.fromBlock(plannedCandidate.center().getZ())
            );
            if (!plannedBiome.is(net.minecraft.resources.ResourceKey.create(Registries.BIOME, sourceBiomeId))) {
                return rejectAttempt(claim, "planned_center_outside_source_biome");
            }
        }
        origin = plannedCandidate.center();
        RandomSource planRandom = RandomSource.create(plannedCandidate.shapeSeed());

        // MEDIUM/LARGE no longer retain a random HeightRange+BiomeFilter draw: that made their chance
        // proportional to the vertical volume of a 3D biome. Resolve one deterministic host position in
        // the source biome instead, after the rarity lottery has already selected this single candidate.
        if (dataSettings == null && tier >= TIER_MEDIUM && sourceBiomeId != null) {
            BlockPos biomeHost = findSourceBiomeTarget(
                    level, origin, sourceBiomeId, targets, planRandom
            );
            if (biomeHost == null) {
                return rejectAttempt(claim, "no_source_biome_target_in_anchor");
            }
            origin = biomeHost;
            plannedCandidate = plannedCandidate.withUndergroundCenter(origin);
        }

        DepositTierMath.TierProfile tierProfile = tierProfile(tier);
        int maximumSafeBlocks = tierParameters().maximumSafeDepositBlocks();
        int minBlocks = Mth.clamp(Math.round(tierProfile.minimumBlockCount() * sizeMultiplier), 1, maximumSafeBlocks);
        int maxBlocks = Mth.clamp(Math.round(tierProfile.maximumBlockCount() * sizeMultiplier), minBlocks, maximumSafeBlocks);
        double scaledCharacteristicBlocks = Mth.clamp(
                tierProfile.characteristicBlockCount() * sizeMultiplier,
                minBlocks,
                maxBlocks
        );
        int targetBlocks = (int) DepositTierMath.sampleAroundCharacteristicValue(
                minBlocks, maxBlocks, scaledCharacteristicBlocks, planRandom::nextDouble
        );
        int requiredMinBlocks = minBlocks;
        ShapeDimensions shape = shapeForBlockCount(targetBlocks, tier, tierProfile.tierPosition(), planRandom);
        int radiusX = shape.radiusX();
        int radiusZ = shape.radiusZ();
        int maxDepth = shape.depth();
        CompactDepositGeometry compactShape = tier >= TIER_MEDIUM
                ? compactGeometry(plannedCandidate.shapeSeed(), tier, sizeMultiplier) : null;
        if (compactShape != null) {
            targetBlocks = compactShape.cells().size();
            requiredMinBlocks = Math.max(4, (int) Math.ceil(targetBlocks * 0.60));
            radiusX = compactShape.radiusX();
            radiusZ = compactShape.radiusZ();
            maxDepth = compactShape.radiusY();
        }

        BlockPos undergroundSearchOrigin = origin;
        CaveWallAnchor wallAnchor = null;
        PlacementMode placementMode = dataSettings == null ? PlacementMode.DEFAULT : dataSettings.placement();
        if (tier < TIER_MEDIUM && placementMode != PlacementMode.UNDERGROUND) {
            int minimumWallDepth = tier == TIER_TINY ? 2 : 3;
            int maximumWallDepth = tier == TIER_TINY ? 2 : Math.max(3, maxDepth + 1);
            int wallSearchRadius = Mth.clamp(Math.max(radiusX, radiusZ) * 2 + 8, 8, 24);
            int wallSearchVerticalRadius = Math.max(8, maxDepth + 3);
            GenerationWriteBounds writeBounds = generationWriteBounds(level);
            Direction preferredExposedFace = plannedCandidate.wallDirection().getOpposite();
            wallAnchor = CaveWallDetector.findNearest(
                    level,
                    origin,
                    wallSearchRadius,
                    wallSearchVerticalRadius,
                    MAX_TAGGED_STONE_SEARCH_CHECKS,
                    minimumWallDepth,
                    maximumWallDepth,
                    preferredExposedFace,
                    pos -> (writeBounds == null
                            || writeBounds.contains(pos.getX(), pos.getZ()))
                            && level.ensureCanWrite(pos),
                    dataSettings == null ? null : state -> matchingTarget(targets, state, planRandom, false) != null
            ).orElse(null);
            if (wallAnchor != null) {
                origin = wallAnchor.position();
                plannedCandidate = plannedCandidate.withWallAnchor(wallAnchor);
            } else {
                if (placementMode == PlacementMode.CAVE_WALL) {
                    return rejectAttempt(claim, "required_cave_wall_missing");
                }
                BlockPos taggedStoneOrigin = findNearbyHost(
                        level, undergroundSearchOrigin, radiusX, radiusZ, maxDepth, targets, dataSettings != null, planRandom
                );
                if (taggedStoneOrigin == null) {
                    return rejectAttempt(claim, "no_cave_wall_or_c_stone_near_spawn");
                }
                origin = taggedStoneOrigin;
                plannedCandidate = plannedCandidate.withUndergroundCenter(origin);
            }
        } else {
            // MEDIUM/LARGE retain their volume-based host correction and do not require cave exposure.
            BlockPos taggedStoneOrigin = findNearbyHost(
                    level, origin, radiusX, radiusZ, maxDepth, targets, dataSettings != null, planRandom
            );
            if (taggedStoneOrigin == null) {
                return rejectAttempt(claim, "no_c_stone_near_spawn");
            }
            // Caves are carved before features and structures may still be built over this area, so a
            // lens placed blindly is cut by them or by the write bounds. Move it to an uncut spot instead.
            origin = compactShape == null ? uncutLensOrigin(
                    level, taggedStoneOrigin, targets, radiusX, radiusZ, maxDepth, dataSettings == null,
                    plannedCandidate.shapeSeed()
            ) : uncutCompactOrigin(level, taggedStoneOrigin, targets, compactShape, plannedCandidate.shapeSeed());
            if (origin == null) return rejectAttempt(claim, "no_uncut_compact_host");
            plannedCandidate = plannedCandidate.withUndergroundCenter(origin);
        }

        if (tier >= TIER_MEDIUM && (dataSettings == null || !dataSettings.forced())
                && !LargeDepositSpatialIndex.get(level.getLevel())
                .canPossiblyFit(origin, tier, 0.0D)) {
            CLAIMED_DEPOSIT_ATTEMPTS.add(claim.claimKey());
            trimIfOversized(CLAIMED_DEPOSIT_ATTEMPTS);
            return rejectAttempt(claim, "large_spacing_precheck");
        }

        // Only datapack rules reach this point: automatic TINY/SMALL returned from placeSourceAttempts.
        List<DepositPosition> positions = compactShape != null
                ? collectCompactPositions(level, origin, targets, compactShape, plannedCandidate.shapeSeed())
                : wallAnchor == null
                ? collectDepositPositions(
                        level, planRandom, origin, targets, radiusX, radiusZ, maxDepth, targetBlocks,
                        dataSettings == null
                )
                : collectWallDepositPositions(
                        level, planRandom, wallAnchor, targets, radiusX, radiusZ,
                        maxDepth, targetBlocks, tier, dataSettings == null
                );
        if (wallAnchor != null && SmallDepositPlacementPriority.shouldTryUnderground(
                positions.size() >= requiredMinBlocks && isValidWallDepositShape(
                        positions.stream().map(DepositPosition::pos).toList(), wallAnchor, tier
                )
        )) {
            // A detected surface is only preferred while it can produce a valid connected wall shape.
            // Otherwise use the same candidate seed and continue with the ordinary underground lens.
            BlockPos taggedStoneOrigin = findNearbyHost(
                    level, undergroundSearchOrigin, radiusX, radiusZ, maxDepth, targets, dataSettings != null, planRandom
            );
            if (taggedStoneOrigin == null) {
                return rejectAttempt(claim, "invalid_wall_shape_and_no_c_stone_near_spawn");
            }
            wallAnchor = null;
            origin = taggedStoneOrigin;
            plannedCandidate = plannedCandidate.withUndergroundCenter(origin);
            positions = collectDepositPositions(
                    level, planRandom, origin, targets, radiusX, radiusZ, maxDepth, targetBlocks,
                    dataSettings == null
            );
        }
        int untrimmedBlocks = positions.size();
        positions = limitByChunkCompatibilityBudget(level, positions);
        if (compactShape != null && positions.size() != untrimmedBlocks) {
            // A forced slot can be retried elsewhere. Arbitrarily trimming it would cut its core.
            return rejectAttempt(claim, "compact_chunk_capacity");
        }
        if (positions.size() > targetBlocks) {
            positions = positions.subList(0, targetBlocks);
        }
        if (wallAnchor != null && SmallDepositPlacementPriority.shouldTryUnderground(
                positions.size() >= requiredMinBlocks && isValidWallDepositShape(
                        positions.stream().map(DepositPosition::pos).toList(), wallAnchor, tier
                )
        )) {
            // Chunk compatibility trimming can remove a critical surface block. Preserve the requested
            // wall -> underground priority here as well instead of cancelling an otherwise viable seed.
            BlockPos taggedStoneOrigin = findNearbyHost(
                    level, undergroundSearchOrigin, radiusX, radiusZ, maxDepth, targets, dataSettings != null, planRandom
            );
            if (taggedStoneOrigin == null) {
                return rejectAttempt(claim, "trimmed_wall_shape_and_no_c_stone_near_spawn");
            }
            wallAnchor = null;
            origin = taggedStoneOrigin;
            plannedCandidate = plannedCandidate.withUndergroundCenter(origin);
            positions = collectDepositPositions(
                    level, planRandom, origin, targets, radiusX, radiusZ, maxDepth, targetBlocks,
                    dataSettings == null
            );
            positions = limitByChunkCompatibilityBudget(level, positions);
            if (positions.size() > targetBlocks) {
                positions = positions.subList(0, targetBlocks);
            }
        }
        // Neither placement mode may leave behind a partial vein below the configured tier minimum.
        SmallDepositPlacementPriority.Placement smallPlacement = SmallDepositPlacementPriority.resolve(
                wallAnchor != null,
                wallAnchor == null && positions.size() >= requiredMinBlocks
        );
        if ((tier < TIER_MEDIUM && smallPlacement == SmallDepositPlacementPriority.Placement.CANCELLED)
                || (tier >= TIER_MEDIUM && positions.size() < requiredMinBlocks)) {
            return rejectAttempt(claim, "insufficient_shape_blocks");
        }
        // Treat a connected component as one vein from the player's point of view. Without this guard,
        // independently generated TINY/SMALL deposits could touch and look like a single deposit whose
        // block count exceeded its tier range.
        if ((dataSettings == null || !dataSettings.forced()) && touchesExistingDeposit(level, positions)) {
            return rejectAttempt(claim, "touches_existing_deposit");
        }

        ResourceLocation firstOreBlockId = BuiltInRegistries.BLOCK.getKey(positions.getFirst().target().state.getBlock());
        if (firstOreBlockId == null) {
            return rejectAttempt(claim, "unregistered_ore_block");
        }
        OreDepositPaletteData palette = OreDepositPaletteData.get(level.getLevel());
        // A lens normally contains one ore type, so do the synchronized persistent-palette lookup once per
        // distinct block instead of once per physical position. Identity semantics match the block registry
        // and the same cache is reused by the placement loop below.
        Map<Block, Integer> oreIndices = new IdentityHashMap<>();
        for (DepositPosition position : positions) {
            Block oreBlock = position.target().state.getBlock();
            Integer oreIndex = oreIndices.get(oreBlock);
            if (oreIndex == null) {
                oreIndex = palette.oreIndex(oreBlock);
                if (oreIndex < 0) {
                    return rejectAttempt(claim, "ore_palette_full");
                }
                oreIndices.put(oreBlock, oreIndex);
            }
        }

        float richnessMultiplier = Mth.clamp(
                dataSettings == null ? config.richnessMultiplier() : dataSettings.richnessMultiplier(),
                0.1F, 6.0F
        );
        DepositTierMath.OreRange oreRange = DepositTierMath.oreRange(positions.size(), tierProfile);
        long sampledBaseTotalOre = DepositTierMath.sampleTotalOre(oreRange, planRandom::nextDouble);
        // Richness changes only stored units. Physical block count was finalized above from the size curve.
        int totalOre = (int) Math.min(
                Integer.MAX_VALUE,
                Math.max(positions.size(), Math.round(sampledBaseTotalOre * richnessMultiplier))
        );
        if (compactShape != null) {
            totalOre = compactTotalOre(plannedCandidate.shapeSeed(), tier, positions.size(), richnessMultiplier);
        }

        // Only burn this attempt slot once we know a vein will actually be placed. Claiming it up front
        // (before knowing whether a nearby cave wall / enough valid positions exist) would mean a single
        // unlucky attempt permanently wastes the slot, even though the same material's own placement often
        // gets several random origins per chunk (inherited from the original vanilla feature's count) —
        // later origins would never get a chance because the slot was already gone.
        String materialKey = OreUnifier.materialKeyFor(positions.getFirst().target().state.getBlock());
        boolean sharedAttemptClaimed = rareAttemptPreclaimed;
        boolean materialSlotClaimed = false;
        if (claim.requiresSharedClaim() && !rareAttemptPreclaimed) {
            if (!CLAIMED_DEPOSIT_ATTEMPTS.add(claim.claimKey())) {
                return rejectAttempt(claim, "attempt_slot_already_claimed");
            }
            sharedAttemptClaimed = true;
            trimIfOversized(CLAIMED_DEPOSIT_ATTEMPTS);
        }
        // Committed alongside the attempt-slot claim: from this point on, no other original ore feature
        // that unifies to this material can win a slot for this chunk+tier (see findEligibleAttempt).
        if (claim.requiresSharedClaim()) {
            long materialClaimKey = computeMaterialClaimKey(level.getLevel(), chunkX, chunkZ, tier, materialKey);
            if (!CLAIMED_MATERIAL_SLOTS.add(materialClaimKey)) {
                if (!rareAttemptPreclaimed) {
                    CLAIMED_DEPOSIT_ATTEMPTS.remove(claim.claimKey());
                }
                return rejectAttempt(claim, "material_slot_already_claimed");
            }
            materialSlotClaimed = true;
            trimIfOversized(CLAIMED_MATERIAL_SLOTS);
        }

        // Persist the tagged-stone-corrected center used by the physical lens. /locate searches around the
        // immutable planned center with the same bounded relocation allowance, then verifies this real point.
        BlockPos recordedCenter = origin;
        // Same-material clustering makes independently-budgeted deposits from neighbouring chunks read as
        // one oversized deposit even though each individually respects its own tier's block-count range.
        // MEDIUM/LARGE already keep hundreds of blocks apart via tryAdd below, but that check only ever
        // compares against other MEDIUM/LARGE records; TINY/SMALL have no spacing check at all otherwise.
        // Gating every tier here against any other deposit of the same material within its own search
        // radius keeps a material's deposits visually distinct regardless of which chunk/tier they came from.
        if (tier >= TIER_MEDIUM && (dataSettings == null || !dataSettings.forced())) {
            LargeDepositSpatialIndex spatialIndex = LargeDepositSpatialIndex.get(level.getLevel());
            // Spacing is a property of the MEDIUM/LARGE world layout, not of a deposit's player
            // controlled Size or Richness.  Feeding the physical block count / reserve into this value
            // made a larger or richer deposit push every following deposit farther away, cancelling the
            // slider that made it larger.  Keep a deterministic-in-practice per-deposit spread value so
            // the configured tier-pair distance bands still vary, while the sliders stay independent.
            double normalizedScale = DepositSpacingMath.independentDepositScale(planRandom.nextDouble());
            if (!spatialIndex.tryAdd(
                    recordedCenter, tier, normalizedScale, materialKey, positions, planRandom
            )) {
                // The spatial check is the final fallible step before block placement. Release both
                // reservations so another valid source attempt for this deterministic slot can retry.
                if (materialSlotClaimed) {
                    CLAIMED_MATERIAL_SLOTS.remove(
                            computeMaterialClaimKey(level.getLevel(), chunkX, chunkZ, tier, materialKey)
                    );
                }
                if (sharedAttemptClaimed && !rareAttemptPreclaimed) {
                    CLAIMED_DEPOSIT_ATTEMPTS.remove(claim.claimKey());
                }
                return rejectAttempt(claim, "dynamic_large_spacing");
            }
        }

        int[] amounts;
        if (compactShape == null) {
            amounts = distributeOre(positions, origin, radiusX, radiusZ, totalOre);
        } else {
            Map<CompactDepositGeometry.Key, Double> weights = new java.util.HashMap<>();
            for (CompactDepositGeometry.Cell cell : compactShape.cells()) weights.put(cell.key(), cell.weight());
            BlockPos weightOrigin = origin;
            amounts = DepositTierMath.distributeOre(totalOre, positions.stream().mapToDouble(position -> weights.getOrDefault(
                    new CompactDepositGeometry.Key(position.pos().getX() - weightOrigin.getX(), position.pos().getY() - weightOrigin.getY(),
                            position.pos().getZ() - weightOrigin.getZ()), 0.25)).toArray(), CompactDepositGeometry.maximumBlockReserve(totalOre,positions.size()));
        }
        List<OreDepositData.GeneratedDeposit> generatedDeposits = new ArrayList<>(positions.size());
        // A deposit normally has one ore block and crosses only a handful of host stones. Resolve their
        // synchronized persistent-palette lookups once per block type instead of twice for every position.
        Map<Block, Integer> baseIndices = new IdentityHashMap<>();
        BlockState depositState = ModBlocks.ORE_DEPOSIT.get().defaultBlockState();
        int attemptedBlocks = positions.size();
        int placedBlocks = 0;
        List<BlockPos> placedPositions = new ArrayList<>(positions.size());
        LongOpenHashSet occupiedSections = new LongOpenHashSet();
        for (int index = 0; index < positions.size(); index++) {
            DepositPosition depositPosition = positions.get(index);
            BlockPos pos = depositPosition.pos();
            OreConfiguration.TargetBlockState target = depositPosition.target();

            int amount = amounts[index];
            BlockState localStone = level.getBlockState(pos);
            if ((dataSettings == null
                    ? !localStone.is(ModBlockTags.STONES)
                    : matchingTarget(targets, localStone, planRandom, false) == null)
                    || !localStone.getFluidState().isEmpty()) {
                continue;
            }
            int oreIndex = oreIndices.get(target.state.getBlock());
            Integer cachedBase = baseIndices.get(localStone.getBlock());
            int baseIndex;
            if (cachedBase != null) {
                baseIndex = cachedBase;
            } else {
                baseIndex = palette.baseIndex(localStone.getBlock());
                if (baseIndex >= 0) {
                    baseIndices.put(localStone.getBlock(), baseIndex);
                } else {
                    int fallbackColor = target.state.getMapColor(level, pos).col;
                    baseIndex = OreDepositStonePalette.nearestByMapColor(level.getLevel(), fallbackColor);
                }
            }
            // Decoration runs before the completed chunk is sent to clients. Suppress per-block client
            // updates here; sending intermediate states while an extreme preset is still filling the
            // chunk can race optimized palette serializers (Sodium/Flywheel) and produce an invalid id.
            if (!level.setBlock(pos, depositState, Block.UPDATE_NONE)) {
                continue;
            }
            placedBlocks++;
            placedPositions.add(pos.immutable());
            occupiedSections.add(physicalSectionKey(pos));
            generatedDeposits.add(new OreDepositData.GeneratedDeposit(
                    pos, baseIndex, oreIndex, amount, tier
            ));
        }

        OreDepositData.setGeneratedBatch(level, generatedDeposits);

        DepositCandidate confirmationCandidate = plannedCandidate;
        boolean exposedToCave = wallAnchor != null
                && CaveWallDetector.isExposedToCave(level, placedPositions)
                && isValidWallDepositShape(placedPositions, wallAnchor, tier);
        boolean confirmed = ConfirmedDepositIndex.get(level.getLevel()).confirm(
                confirmationCandidate, recordedCenter, placedBlocks, occupiedSections.size(), exposedToCave
        );

        double placementRatio = attemptedBlocks == 0 ? 0.0D : placedBlocks / (double) attemptedBlocks;
        if (!confirmed) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposit {} tier {} was not confirmed after placing {}/{} blocks (ratio {}, sections={}, cave_exposed={})",
                    firstOreBlockId,
                    confirmationCandidate.tier().serializedName(),
                    placedBlocks,
                    attemptedBlocks,
                    String.format(java.util.Locale.ROOT, "%.3f", placementRatio),
                    occupiedSections.size(),
                    exposedToCave
            );
            return rejectAttempt(claim, "insufficient_placed_blocks");
        }

        if (dataSettings != null) {
            OresAndDrillsMod.LOGGER.debug(
                    "Data-driven ore deposit {} tier {} placed at {} ({} blocks) in biome {}",
                    firstOreBlockId, confirmationCandidate.tier().serializedName(), recordedCenter,
                    placedBlocks, sourceBiomeId
            );
        }
        return completeAttempt(claim);
    }

    /**
     * Limits the number of tracked deposits in each affected chunk. This is deliberately based on the
     * actual attachment size rather than on a particular ore/tier, so all menu presets remain valid and
     * only the pathological case is trimmed.
     */
    private static List<DepositPosition> limitByChunkCompatibilityBudget(
            WorldGenLevel level,
            List<DepositPosition> positions
    ) {
        return limitByChunkCompatibilityBudget(level, positions, MAX_DEPOSIT_ENTRIES_PER_CHUNK);
    }

    private static List<DepositPosition> limitByChunkCompatibilityBudget(
            WorldGenLevel level,
            List<DepositPosition> positions,
            int maximumEntriesPerChunk
    ) {
        if (positions.isEmpty()) {
            return positions;
        }

        Long2IntOpenHashMap usedByChunk = new Long2IntOpenHashMap();
        usedByChunk.defaultReturnValue(-1);
        List<DepositPosition> limited = new ArrayList<>(positions.size());
        int skipped = 0;
        for (DepositPosition position : positions) {
            BlockPos pos = position.pos();
            long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            int used = usedByChunk.get(chunkKey);
            if (used < 0) {
                used = existingDepositCount(level, pos);
                usedByChunk.put(chunkKey, used);
            }
            if (used >= maximumEntriesPerChunk) {
                skipped++;
                continue;
            }
            usedByChunk.put(chunkKey, used + 1);
            limited.add(position);
        }

        if (skipped > 0) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposits: trimmed {} blocks from a chunk-safe placement budget of {} entries",
                    skipped, maximumEntriesPerChunk
            );
        }
        return limited;
    }

    private static int existingDepositCount(WorldGenLevel level, BlockPos pos) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        return data == null ? 0 : data.size();
    }

    private static boolean touchesExistingDeposit(WorldGenLevel level, List<DepositPosition> positions) {
        Block depositBlock = ModBlocks.ORE_DEPOSIT.get();
        LongOpenHashSet plannedPositions = new LongOpenHashSet(positions.size());
        for (DepositPosition position : positions) {
            plannedPositions.add(position.pos().asLong());
        }
        BlockPos.MutableBlockPos neighbor = new BlockPos.MutableBlockPos();
        for (DepositPosition position : positions) {
            BlockPos pos = position.pos();
            for (Direction direction : HORIZONTAL_DIRECTIONS) {
                neighbor.setWithOffset(pos, direction);
                if (!plannedPositions.contains(neighbor.asLong()) && level.getBlockState(neighbor).is(depositBlock)) {
                    return true;
                }
            }
            for (Direction direction : VERTICAL_DIRECTIONS) {
                neighbor.setWithOffset(pos, direction);
                if (!plannedPositions.contains(neighbor.asLong()) && level.getBlockState(neighbor).is(depositBlock)) {
                    return true;
                }
            }
        }
        return false;
    }

    static boolean isValidWallDepositShape(
            List<BlockPos> positions,
            CaveWallAnchor anchor,
            int tier
    ) {
        if (anchor == null) {
            return false;
        }
        Direction inward = anchor.inwardDirection();
        return WallDepositGeometry.isValid(
                positions.stream()
                        .map(pos -> new WallDepositGeometry.Point(pos.getX(), pos.getY(), pos.getZ()))
                        .toList(),
                new WallDepositGeometry.Point(
                        anchor.position().getX(), anchor.position().getY(), anchor.position().getZ()
                ),
                inward.getStepX(),
                inward.getStepZ(),
                tier == TIER_SMALL
        );
    }

    static List<DepositPosition> limitUndergroundPositions(
            List<DepositPosition> positions,
            BlockPos origin,
            int radiusX,
            int radiusZ,
            int maxDepth,
            int minBlocks,
            int maxBlocks
    ) {
        if (positions.size() < minBlocks) {
            return List.of();
        }

        if (positions.size() <= maxBlocks) {
            return positions;
        }

        // Keep only the nearest maxBlocks entries while scanning. LARGE lenses may expose thousands of
        // writable candidates but can retain at most 360; sorting the entire lens made that common path
        // O(n log n). The original list index is the final key so ties retain Java's previous stable-sort
        // order exactly.
        StableTopK<DepositPosition> nearest = new StableTopK<>(maxBlocks);
        for (int index = 0; index < positions.size(); index++) {
            DepositPosition position = positions.get(index);
            nearest.offer(
                    position,
                    normalizedVolumeDistance(position.pos(), origin, radiusX, radiusZ, maxDepth),
                    origin.getY() - position.pos().getY()
            );
        }
        return nearest.toSortedList();
    }

    private static boolean mutableStateIsDepositStone(WorldGenLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return state.is(ModBlockTags.STONES) && state.getFluidState().isEmpty();
    }

    /**
     * Finds the nearest usable #c:stones block by Manhattan distance. Bounds scale with the already selected
     * lens but use their own fixed safety ceiling, so an unrelated cave-wall setting cannot change deposit
     * eligibility and repeated attempts stay safe in dimensions that expose no compatible stone.
     */
    private static BlockPos findNearbyTaggedStone(
            WorldGenLevel level,
            BlockPos origin,
            int radiusX,
            int radiusZ,
            int maxDepth
    ) {
        int horizontalRadius = taggedStoneHorizontalRadius(radiusX, radiusZ);
        int verticalRadius = taggedStoneVerticalRadius(maxDepth);
        GenerationWriteBounds writeBounds = generationWriteBounds(level);
        return TaggedStoneRelocator.findNearest(
                origin,
                horizontalRadius,
                verticalRadius,
                MAX_TAGGED_STONE_SEARCH_CHECKS,
                level.getMinBuildHeight() + BEDROCK_CLEARANCE,
                level.getMaxBuildHeight(),
                pos -> (writeBounds == null || writeBounds.contains(pos.getX(), pos.getZ()))
                        && (writeBounds != null || level.ensureCanWrite(pos)),
                pos -> mutableStateIsDepositStone(level, pos)
        );
    }

    private static BlockPos findNearbyHost(
            WorldGenLevel level,
            BlockPos origin,
            int radiusX,
            int radiusZ,
            int maxDepth,
            List<OreConfiguration.TargetBlockState> targets,
            boolean strictTargets,
            RandomSource random
    ) {
        if (!strictTargets) {
            return findNearbyTaggedStone(level, origin, radiusX, radiusZ, maxDepth);
        }
        int horizontalRadius = taggedStoneHorizontalRadius(radiusX, radiusZ);
        int verticalRadius = taggedStoneVerticalRadius(maxDepth);
        GenerationWriteBounds writeBounds = generationWriteBounds(level);
        return TaggedStoneRelocator.findNearest(
                origin,
                horizontalRadius,
                verticalRadius,
                MAX_TAGGED_STONE_SEARCH_CHECKS,
                level.getMinBuildHeight() + BEDROCK_CLEARANCE,
                level.getMaxBuildHeight(),
                pos -> (writeBounds == null || writeBounds.contains(pos.getX(), pos.getZ()))
                        && (writeBounds != null || level.ensureCanWrite(pos)),
                pos -> {
                    BlockState state = level.getBlockState(pos);
                    return state.getFluidState().isEmpty() && matchingTarget(targets, state, random, false) != null;
                }
        );
    }

    static int taggedStoneHorizontalRadius(int radiusX, int radiusZ) {
        int shapeRadius = Math.max(radiusX, radiusZ);
        return Mth.clamp(shapeRadius * 2 + 8, 8, MAX_TAGGED_STONE_RELOCATION_RADIUS);
    }

    static int taggedStoneVerticalRadius(int maxDepth) {
        return Math.max(MAX_TAGGED_STONE_VERTICAL_RELOCATION_RADIUS, maxDepth + 3);
    }

    private static boolean isUsableTaggedStone(WorldGenLevel level, BlockPos pos) {
        return isUsableTaggedStone(level, pos, false);
    }

    private static boolean isUsableTaggedStone(WorldGenLevel level, BlockPos pos, boolean writePermissionKnown) {
        if (level.isOutsideBuildHeight(pos)
                || pos.getY() < level.getMinBuildHeight() + BEDROCK_CLEARANCE
                || (!writePermissionKnown && !level.ensureCanWrite(pos))) {
            return false;
        }
        return mutableStateIsDepositStone(level, pos);
    }

    private static OreConfiguration.TargetBlockState matchingTarget(
            List<OreConfiguration.TargetBlockState> oreTargets,
            BlockState currentState,
            RandomSource random,
            boolean allowStoneFallback
    ) {
        // Every physical deposit block may replace only a #c:stones host. The original target predicate
        // remains relevant solely for selecting variants such as stone/deepslate ore states.
        if (allowStoneFallback && !currentState.is(ModBlockTags.STONES)) {
            return null;
        }

        OreConfiguration.TargetBlockState stoneFallback = null;
        for (OreConfiguration.TargetBlockState target : oreTargets) {
            if (target.target.test(currentState, random)) {
                return target;
            }

            if (allowStoneFallback && stoneFallback == null) {
                stoneFallback = target;
            }
        }

        return stoneFallback;
    }

    /** Builds a compact connected shape from a visible wall block into the host rock. */
    private static List<DepositPosition> collectWallDepositPositions(
            WorldGenLevel level,
            RandomSource random,
            CaveWallAnchor anchor,
            List<OreConfiguration.TargetBlockState> targets,
            int radiusX,
            int radiusZ,
            int shapeDepth,
            int maxBlocks,
            int tier,
            boolean allowStoneFallback
    ) {
        List<OreConfiguration.TargetBlockState> oreTargets = new ArrayList<>(targets.size());
        for (OreConfiguration.TargetBlockState target : targets) {
            if (OreTags.isOre(target.state)) {
                oreTargets.add(target);
            }
        }
        if (oreTargets.isEmpty()) {
            return List.of();
        }

        Direction inward = anchor.inwardDirection();
        int requestedDepth = tier == TIER_TINY ? 2 : Math.max(3, shapeDepth + 1);
        int availableDepth = allowStoneFallback
                ? CaveWallDetector.measureWallDepth(level, anchor.position(), inward, requestedDepth)
                : CaveWallDetector.measureWallDepth(
                        level, anchor.position(), inward, requestedDepth,
                        state -> matchingTarget(oreTargets, state, random, false) != null
                );
        if (availableDepth < (tier == TIER_TINY ? 2 : 3)) {
            return List.of();
        }

        int tangentRadius = tier == TIER_TINY
                ? 1
                : Math.max(1, Math.min(2, Math.max(radiusX, radiusZ)));
        int verticalRadius = 1;
        int tangentX = inward.getAxis() == Direction.Axis.Z ? 1 : 0;
        int tangentZ = inward.getAxis() == Direction.Axis.X ? 1 : 0;
        int originX = anchor.position().getX();
        int originY = anchor.position().getY();
        int originZ = anchor.position().getZ();
        GenerationWriteBounds writeBounds = generationWriteBounds(level);
        List<WallDepositPosition> candidates = new ArrayList<>();
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int tangent = -tangentRadius; tangent <= tangentRadius; tangent++) {
            for (int vertical = -verticalRadius; vertical <= verticalRadius; vertical++) {
                double tangentScale = tangent / (double) Math.max(1, tangentRadius);
                double verticalScale = vertical / (double) verticalRadius;
                if (tangentScale * tangentScale + verticalScale * verticalScale > 1.25D) {
                    continue;
                }

                // Each lateral ray starts on the cave surface and stops at its first gap. Consequently no
                // selected block can jump through water, a second cavity, or an incompatible host block.
                for (int depth = 0; depth < availableDepth; depth++) {
                    mutable.set(
                            originX + inward.getStepX() * depth + tangentX * tangent,
                            originY + vertical,
                            originZ + inward.getStepZ() * depth + tangentZ * tangent
                    );
                    if (mutable.getY() < level.getMinBuildHeight() + BEDROCK_CLEARANCE
                            || mutable.getY() >= level.getMaxBuildHeight()
                            || writeBounds != null && !writeBounds.contains(mutable.getX(), mutable.getZ())
                            || !level.ensureCanWrite(mutable)) {
                        break;
                    }
                    BlockState currentState = level.getBlockState(mutable);
                    OreConfiguration.TargetBlockState target = matchingTarget(
                            oreTargets, currentState, random, allowStoneFallback
                    );
                    if (target == null || !currentState.getFluidState().isEmpty()) {
                        break;
                    }

                    boolean isAnchor = tangent == 0 && vertical == 0 && depth == 0;
                    double priority = isAnchor
                            ? -100.0D
                            : (depth == 0 ? 20.0D : Math.abs(depth - 1) * 0.5D)
                                    + Math.abs(tangent)
                                    + Math.abs(vertical) * 1.1D
                                    + random.nextDouble() * 0.05D;
                    candidates.add(new WallDepositPosition(
                            new DepositPosition(mutable.immutable(), target), priority
                    ));
                }
            }
        }

        candidates.sort(java.util.Comparator.comparingDouble(WallDepositPosition::priority));
        int resultSize = Math.min(maxBlocks, candidates.size());
        List<DepositPosition> result = new ArrayList<>(resultSize);
        for (int index = 0; index < resultSize; index++) {
            result.add(candidates.get(index).position());
        }
        return result;
    }

    private static List<DepositPosition> collectDepositPositions(
            WorldGenLevel level,
            RandomSource random,
            BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets,
            int radiusX,
            int radiusZ,
            int maxDepth,
            int maxBlocks,
            boolean allowStoneFallback
    ) {
        BlockPos.MutableBlockPos mutablePos = new BlockPos.MutableBlockPos();
        // Tags and target order are immutable during decoration. Filter once per deposit instead of doing
        // the same registry/tag test for every candidate block in the lens.
        List<OreConfiguration.TargetBlockState> oreTargets = new ArrayList<>(targets.size());
        for (OreConfiguration.TargetBlockState target : targets) {
            if (OreTags.isOre(target.state)) {
                oreTargets.add(target);
            }
        }
        if (oreTargets.isEmpty()) {
            return List.of();
        }

        // Keep only the positions that can reach the finalized lens. Packing the coordinates as a primitive
        // long delays BlockPos/DepositPosition creation until after the scan, bounding hot-path allocations
        // by maxBlocks even when terrain exposes far more compatible hosts.
        StableLongTopK<OreConfiguration.TargetBlockState> nearest = new StableLongTopK<>(maxBlocks);
        double radiusXSquared = (double) radiusX * radiusX;
        double radiusZSquared = (double) radiusZ * radiusZ;
        int safeRadiusX = Math.max(1, radiusX);
        int safeRadiusZ = Math.max(1, radiusZ);
        int safeDepth = Math.max(1, maxDepth);
        int originX = origin.getX();
        int originY = origin.getY();
        int originZ = origin.getZ();
        int minimumY = level.getMinBuildHeight() + BEDROCK_CLEARANCE;
        int maximumY = level.getMaxBuildHeight();
        GenerationWriteBounds writeBounds = generationWriteBounds(level);
        int minimumDx = writeBounds == null ? -radiusX : Math.max(-radiusX, writeBounds.minimumX() - originX);
        int maximumDx = writeBounds == null ? radiusX : Math.min(radiusX, writeBounds.maximumX() - originX);
        int minimumDz = writeBounds == null ? -radiusZ : Math.max(-radiusZ, writeBounds.minimumZ() - originZ);
        int maximumDz = writeBounds == null ? radiusZ : Math.min(radiusZ, writeBounds.maximumZ() - originZ);

        for (int dx = minimumDx; dx <= maximumDx; dx++) {
            double horizontalX = dx / (double) safeRadiusX;
            for (int dz = minimumDz; dz <= maximumDz; dz++) {
                double normalized = (dx * dx) / radiusXSquared + (dz * dz) / radiusZSquared;
                double edgeNoise = 0.78D + random.nextDouble() * 0.34D;
                if (normalized > edgeNoise) {
                    continue;
                }

                double edgeDistance = Mth.clamp(Math.sqrt(normalized), 0.0D, 1.0D);
                double lensProfile = Math.sqrt(Math.max(0.0D, 1.0D - edgeDistance * edgeDistance));
                int depth = 1 + Mth.floor((maxDepth - 1) * lensProfile);
                depth = Mth.clamp(depth, 1, maxDepth);
                double horizontalZ = dz / (double) safeRadiusZ;
                double horizontalDistance = horizontalX * horizontalX + horizontalZ * horizontalZ;

                for (int dy = 0; dy < depth; dy++) {
                    int y = originY - dy;
                    if (y < minimumY || y >= maximumY) {
                        continue;
                    }
                    mutablePos.set(originX + dx, y, originZ + dz);
                    // WorldGenRegion bounds were intersected with the loop above. Keep the generic check
                    // for alternate WorldGenLevel implementations used by dimensions and test harnesses.
                    if (writeBounds == null && !level.ensureCanWrite(mutablePos)) {
                        continue;
                    }

                    BlockState currentState = level.getBlockState(mutablePos);
                    OreConfiguration.TargetBlockState target = matchingTarget(
                            oreTargets, currentState, random, allowStoneFallback
                    );
                    if (target != null && currentState.getFluidState().isEmpty()) {
                        // Keep the exact arithmetic of normalizedVolumeDistance so world seeds retain the
                        // same stable tie ordering after moving selection into this scan.
                        double normalizedDepth = dy / (double) safeDepth;
                        double volumeDistance = horizontalDistance + normalizedDepth * normalizedDepth;
                        nearest.offer(mutablePos.asLong(), target, volumeDistance, dy);
                    }
                }
            }
        }

        return nearest.mapToList((packedPos, target) -> new DepositPosition(BlockPos.of(packedPos), target));
    }

    /**
     * Nearest origin (searched outward in rings, the original spot first) whose full MEDIUM/LARGE patch lies
     * inside the write bounds, in host rock without fluids or cave air, and outside every structure that is
     * already planned around it. A forced slot is released when no completely clear spot exists.
     */
    static BlockPos uncutCompactOrigin(WorldGenLevel level, BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets, CompactDepositGeometry geometry, long seed) {
        List<BoundingBox> structures = plannedStructureBoxes(level);
        // Forced rules retain their existing per-biome-instance ledger. Their compact bodies fit in
        // one chunk, so find an uncut center inside this chunk instead of writing its neighbours.
        ChunkPos sourceChunk = level instanceof WorldGenRegion region ? region.getCenter() : new ChunkPos(origin);
        GenerationWriteBounds bounds = GenerationWriteBounds.around(sourceChunk.x, sourceChunk.z, 0);
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int evaluations = 0;
        for (int ring = 0; ring <= MAX_LENS_SHIFT; ring += LENS_SHIFT_STEP) {
            for (int dx = -ring; dx <= ring; dx += LENS_SHIFT_STEP) {
                for (int dz = -ring; dz <= ring; dz += LENS_SHIFT_STEP) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) continue;
                    for (int dy : LENS_SHIFT_HEIGHTS) {
                        BlockPos center = origin.offset(dx, dy, dz);
                        boolean valid = true;
                        for (CompactDepositGeometry.Cell cell : geometry.cells()) {
                            pos.set(center.getX() + cell.x(), center.getY() + cell.y(), center.getZ() + cell.z());
                            if (!canHoldLensCell(level, pos, targets, false, structures,
                                    RandomSource.create(seed ^ pos.asLong()), bounds,
                                    level.getMinBuildHeight() + BEDROCK_CLEARANCE, level.getMaxBuildHeight())) {
                                valid = false;
                                break;
                            }
                        }
                        if (valid) return center;
                        if (++evaluations >= MAX_LENS_SHIFT_EVALUATIONS) return null;
                    }
                }
            }
        }
        return null;
    }

    private static List<DepositPosition> collectCompactPositions(WorldGenLevel level, BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets, CompactDepositGeometry geometry, long seed) {
        Map<CompactDepositGeometry.Key, OreConfiguration.TargetBlockState> selected = new java.util.HashMap<>();
        List<CompactDepositGeometry.Cell> eligible = new ArrayList<>();
        for (CompactDepositGeometry.Cell cell : geometry.cells()) {
            BlockPos pos = origin.offset(cell.x(), cell.y(), cell.z());
            BlockState state = level.getBlockState(pos);
            OreConfiguration.TargetBlockState target = matchingTarget(targets, state, RandomSource.create(seed ^ pos.asLong()), false);
            if (target != null && state.getFluidState().isEmpty() && level.ensureCanWrite(pos)) {
                eligible.add(cell);
                selected.put(cell.key(), target);
            }
        }
        return CompactDepositGeometry.connected(eligible, geometry.cells().size()).stream().map(cell -> new DepositPosition(
                origin.offset(cell.x(), cell.y(), cell.z()), selected.get(cell.key()))).toList();
    }

    private static BlockPos uncutLensOrigin(
            WorldGenLevel level,
            BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets,
            int radiusX,
            int radiusZ,
            int maxDepth,
            boolean allowStoneFallback,
            long shapeSeed
    ) {
        List<OreConfiguration.TargetBlockState> oreTargets = new ArrayList<>(targets.size());
        for (OreConfiguration.TargetBlockState target : targets) {
            if (OreTags.isOre(target.state)) {
                oreTargets.add(target);
            }
        }
        if (oreTargets.isEmpty()) {
            return origin;
        }
        List<BoundingBox> structures = plannedStructureBoxes(level);
        RandomSource hostRandom = RandomSource.create(shapeSeed ^ LENS_SHIFT_SALT);
        BlockPos best = origin;
        int bestBlocked = Integer.MAX_VALUE;
        int evaluations = 0;
        for (int ring = 0; ring <= MAX_LENS_SHIFT; ring += LENS_SHIFT_STEP) {
            for (int dx = -ring; dx <= ring; dx += LENS_SHIFT_STEP) {
                for (int dz = -ring; dz <= ring; dz += LENS_SHIFT_STEP) {
                    if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                        continue;
                    }
                    for (int dy : LENS_SHIFT_HEIGHTS) {
                        BlockPos candidate = origin.offset(dx, dy, dz);
                        int blocked = blockedLensCells(
                                level, candidate, oreTargets, radiusX, radiusZ, maxDepth, allowStoneFallback,
                                structures, hostRandom, bestBlocked
                        );
                        if (blocked < bestBlocked) {
                            best = candidate;
                            bestBlocked = blocked;
                            if (blocked == 0) {
                                return best;
                            }
                        }
                        if (++evaluations >= MAX_LENS_SHIFT_EVALUATIONS) {
                            return best;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Cells of the ideal lens at this origin that cannot hold a deposit block; stops counting at the limit. */
    private static int blockedLensCells(
            WorldGenLevel level,
            BlockPos origin,
            List<OreConfiguration.TargetBlockState> oreTargets,
            int radiusX,
            int radiusZ,
            int maxDepth,
            boolean allowStoneFallback,
            List<BoundingBox> structures,
            RandomSource random,
            int limit
    ) {
        GenerationWriteBounds writeBounds = generationWriteBounds(level);
        int minimumY = level.getMinBuildHeight() + BEDROCK_CLEARANCE;
        int maximumY = level.getMaxBuildHeight();
        double radiusXSquared = Math.max(1.0D, (double) radiusX * radiusX);
        double radiusZSquared = Math.max(1.0D, (double) radiusZ * radiusZ);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        int blocked = 0;
        for (int dx = -radiusX; dx <= radiusX; dx++) {
            for (int dz = -radiusZ; dz <= radiusZ; dz++) {
                double normalized = (dx * dx) / radiusXSquared + (dz * dz) / radiusZSquared;
                if (normalized > 1.0D) {
                    continue;
                }
                double lensProfile = Math.sqrt(Math.max(0.0D, 1.0D - normalized));
                int depth = Mth.clamp(1 + Mth.floor((maxDepth - 1) * lensProfile), 1, maxDepth);
                for (int dy = 0; dy < depth; dy++) {
                    mutable.set(origin.getX() + dx, origin.getY() - dy, origin.getZ() + dz);
                    if (!canHoldLensCell(level, mutable, oreTargets, allowStoneFallback, structures, random,
                            writeBounds, minimumY, maximumY) && ++blocked >= limit) {
                        return blocked;
                    }
                }
            }
        }
        return blocked;
    }

    private static boolean canHoldLensCell(
            WorldGenLevel level,
            BlockPos pos,
            List<OreConfiguration.TargetBlockState> oreTargets,
            boolean allowStoneFallback,
            List<BoundingBox> structures,
            RandomSource random,
            GenerationWriteBounds writeBounds,
            int minimumY,
            int maximumY
    ) {
        if (pos.getY() < minimumY || pos.getY() >= maximumY
                || writeBounds != null && !writeBounds.contains(pos.getX(), pos.getZ())
                || writeBounds == null && !level.ensureCanWrite(pos)) {
            return false;
        }
        for (BoundingBox structure : structures) {
            if (structure.isInside(pos)) {
                return false;
            }
        }
        BlockState state = level.getBlockState(pos);
        return state.getFluidState().isEmpty() && matchingTarget(oreTargets, state, random, allowStoneFallback) != null;
    }

    /**
     * Piece boxes of every structure referenced by the chunks this feature may write to. Structures are
     * built chunk by chunk during decoration, so pieces in neighbouring chunks may not exist yet; their
     * starts are already known and would later overwrite the deposit.
     */
    private static List<BoundingBox> plannedStructureBoxes(WorldGenLevel level) {
        if (!(level instanceof WorldGenRegion region)) {
            return List.of();
        }
        ChunkPos center = region.getCenter();
        List<BoundingBox> boxes = new ArrayList<>();
        Set<StructureStart> seen = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        for (int chunkX = center.x - FEATURE_WRITE_RADIUS_CHUNKS; chunkX <= center.x + FEATURE_WRITE_RADIUS_CHUNKS; chunkX++) {
            for (int chunkZ = center.z - FEATURE_WRITE_RADIUS_CHUNKS; chunkZ <= center.z + FEATURE_WRITE_RADIUS_CHUNKS; chunkZ++) {
                ChunkAccess chunk = region.getChunk(chunkX, chunkZ, ChunkStatus.STRUCTURE_REFERENCES);
                for (Map.Entry<Structure, LongSet> references : chunk.getAllReferences().entrySet()) {
                    for (long reference : references.getValue()) {
                        int startX = ChunkPos.getX(reference);
                        int startZ = ChunkPos.getZ(reference);
                        // A start beyond the region's structure-start radius cannot be read safely.
                        if (!region.hasChunk(startX, startZ)) {
                            continue;
                        }
                        StructureStart start = region.getChunk(startX, startZ, ChunkStatus.STRUCTURE_STARTS)
                                .getStartForStructure(references.getKey());
                        if (start == null || !start.isValid() || !seen.add(start)) {
                            continue;
                        }
                        for (StructurePiece piece : start.getPieces()) {
                            boxes.add(piece.getBoundingBox().inflatedBy(1));
                        }
                    }
                }
            }
        }
        return boxes;
    }

    private static GenerationWriteBounds generationWriteBounds(WorldGenLevel level) {
        return WorldgenReadArea.of(level);
    }

    private static double normalizedDistance(BlockPos pos, BlockPos origin, int radiusX, int radiusZ) {
        double dx = (pos.getX() - origin.getX()) / (double)Math.max(1, radiusX);
        double dz = (pos.getZ() - origin.getZ()) / (double)Math.max(1, radiusZ);
        return Mth.clamp(Math.sqrt(dx * dx + dz * dz), 0.0D, 1.0D);
    }

    private static long physicalSectionKey(BlockPos pos) {
        long sectionX = (pos.getX() >> 4) & 0x3FFFFFL;
        long sectionZ = (pos.getZ() >> 4) & 0x3FFFFFL;
        long sectionY = (pos.getY() >> 4) & 0xFFFFFL;
        return (sectionX << 42) ^ (sectionZ << 20) ^ sectionY;
    }

    /** Balances horizontal footprint and downward depth when limiting a lens to its configured block count. */
    private static double normalizedVolumeDistance(BlockPos pos, BlockPos origin, int radiusX, int radiusZ, int maxDepth) {
        double horizontalX = (pos.getX() - origin.getX()) / (double)Math.max(1, radiusX);
        double horizontalZ = (pos.getZ() - origin.getZ()) / (double)Math.max(1, radiusZ);
        double depth = (origin.getY() - pos.getY()) / (double)Math.max(1, maxDepth);
        return horizontalX * horizontalX + horizontalZ * horizontalZ + depth * depth;
    }

    private static int[] distributeOre(List<DepositPosition> positions, BlockPos origin, int radiusX, int radiusZ, int totalOre) {
        int size = positions.size();
        double[] weights = new double[size];
        for (int index = 0; index < size; index++) {
            double distance = normalizedDistance(positions.get(index).pos(), origin, radiusX, radiusZ);
            weights[index] = Mth.lerp(distance, 1.0D, EDGE_ORE_FACTOR);
        }
        return DepositTierMath.distributeOre(totalOre, weights);
    }

    private static ShapeDimensions shapeForBlockCount(
            int blockCount,
            int tier,
            double tierPosition,
            RandomSource random
    ) {
        int depth = DepositTierMath.verticalLayersForTier(tier, tierPosition, MAX_UNDERGROUND_DEPTH);
        double baseRadius = Math.sqrt(Math.max(1, blockCount) / (Math.PI * depth * 0.55D)) + 1.0D;
        double anisotropy = 0.85D + random.nextDouble() * 0.30D;
        int tierMaximumRadius = DepositLayouts.forTier(
                OreDepositTier.fromIndex(tier).orElse(OreDepositTier.TINY)
        ).maximumRadius();
        int radiusX = Mth.clamp(Mth.ceil(baseRadius * anisotropy), 1, tierMaximumRadius);
        int radiusZ = Mth.clamp(Mth.ceil(baseRadius / anisotropy), 1, tierMaximumRadius);
        return new ShapeDimensions(radiusX, radiusZ, depth);
    }


    /**
     * Resolves an eligible attempt for the configured tier. TINY/SMALL thin this material's own original
     * placement stream with the dynamically calculated retention gate and never enter the shared lottery.
     * MEDIUM/LARGE use independent rare-event slots and select a material only after the tier is known.
     */
    private static AttemptClaim findEligibleAttempt(
            ServerLevel level,
            BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets,
            int tier,
            ResourceLocation sourceBiomeId,
            float frequencyMultiplier
    ) {
        if (targets.isEmpty()) {
            return null;
        }

        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;
        ResourceLocation candidateOreId = BuiltInRegistries.BLOCK.getKey(targets.getFirst().state.getBlock());
        if (candidateOreId == null) {
            return null;
        }

        String candidateMaterialKey = OreUnifier.materialKeyFor(targets.getFirst().state.getBlock());
        if (CLAIMED_MATERIAL_SLOTS.contains(computeMaterialClaimKey(level, chunkX, chunkZ, tier, candidateMaterialKey))) {
            // A different original ore feature that unifies to this same material (a stone/deepslate
            // variant, or the same ore registered by another mod in this biome) already committed this
            // chunk+tier's deposit; skip the slot search instead of letting this chain win its own extra slot.
            return null;
        }

        ResourceLocation biomeId = sourceBiomeId;
        if (biomeId == null) {
            Holder<Biome> biome = chunkSampleBiome(level, chunkX, chunkZ);
            biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);
        } else if (!matchesAutomaticSourceBiome(level, chunkX, chunkZ, biomeId)) {
            return null;
        }

        TierShares shares = computeTierShares();
        logTierTableOnce(level, biomeId, shares);

        if (!isTierAnchorChunk(chunkX, chunkZ, tier)) {
            return null;
        }

        if (tier < TIER_MEDIUM) {
            // Automatic TINY/SMALL never enter the shared lottery; see placeSourceAttempts.
            return null;
        }

        int attemptIndex = findWinningAttemptSlot(level, chunkX, chunkZ, candidateOreId, tier, biomeId, shares);
        if (attemptIndex < 0) {
            return null;
        }

        double selectedDraw = attemptSlotSeed(
                level, chunkX, chunkZ, tier, attemptIndex, biomeId
        ).nextDouble();
        String selectedMaterial = OreGenerationWeights.selectedMaterialForRegion(
                level.dimension().location(), biomeId, tier, selectedDraw
        );
        ResourceLocation selectedOreId = OreGenerationWeights.oreIdForMaterial(
                level.dimension().location(), biomeId, selectedMaterial
        );
        if (selectedOreId == null) {
            return null;
        }
        return new AttemptClaim(
                computeAttemptClaimKey(level, chunkX, chunkZ, tier, attemptIndex),
                true,
                selectedOreId
        );
    }

    /** Reuses the source feature's replacement predicates while substituting the lottery-selected material. */
    private static List<OreConfiguration.TargetBlockState> targetsForSelectedMaterial(
            List<OreConfiguration.TargetBlockState> sourceTargets,
            ResourceLocation selectedOreId
    ) {
        Block selectedBlock = BuiltInRegistries.BLOCK.get(selectedOreId);
        if (selectedBlock == null) {
            return List.of();
        }
        return sourceTargets.stream()
                .map(target -> OreConfiguration.target(target.target, selectedBlock.defaultBlockState()))
                .toList();
    }

    /**
     * Finds one host block in the source feature's actual 3D biome. The traversal is attempted once only
     * after the rarity lottery has selected a MEDIUM/LARGE candidate, so biome volume and host density do
     * not multiply the number of candidates.
     */
    private static BlockPos findSourceBiomeTarget(
            WorldGenLevel level,
            BlockPos placementOrigin,
            ResourceLocation sourceBiomeId,
            List<OreConfiguration.TargetBlockState> targets,
            RandomSource random
    ) {
        int chunkX = placementOrigin.getX() >> 4;
        int chunkZ = placementOrigin.getZ() >> 4;
        int minimumOriginY = level.getMinBuildHeight() + BEDROCK_CLEARANCE;
        int maximumOriginY = level.getMaxBuildHeight() - 1;
        int minimumQuartY = Math.floorDiv(minimumOriginY, 4);
        int maximumQuartY = Math.floorDiv(maximumOriginY, 4);
        int quartCount = Math.max(0, maximumQuartY - minimumQuartY + 1);
        if (quartCount == 0) {
            return null;
        }

        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        int firstQuartY = random.nextInt(quartCount);
        int firstHorizontalQuart = random.nextInt(16);
        int firstBlockInQuart = random.nextInt(64);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int quartYOffset = 0; quartYOffset < quartCount; quartYOffset++) {
            int quartYIndex = Math.floorMod(firstQuartY + quartYOffset, quartCount);
            int quartY = minimumQuartY + quartYIndex;
            int quartBlockY = quartY << 2;
            int sampleY = Mth.clamp(quartBlockY + 2, minimumOriginY, maximumOriginY);

            for (int horizontalOffset = 0; horizontalOffset < 16; horizontalOffset++) {
                int horizontalQuart = (firstHorizontalQuart + horizontalOffset) & 15;
                int quartX = horizontalQuart & 3;
                int quartZ = horizontalQuart >> 2;
                mutable.set(startX + (quartX << 2) + 2, sampleY, startZ + (quartZ << 2) + 2);
                ResourceLocation biomeId = level.getBiome(mutable).unwrapKey()
                        .map(key -> key.location()).orElse(null);
                if (!sourceBiomeId.equals(biomeId)) {
                    continue;
                }

                for (int blockOffset = 0; blockOffset < 64; blockOffset++) {
                    int packedLocal = (firstBlockInQuart + blockOffset) & 63;
                    int localX = packedLocal & 3;
                    int localZ = (packedLocal >> 2) & 3;
                    int localY = packedLocal >> 4;
                    int y = quartBlockY + localY;
                    if (y < minimumOriginY || y > maximumOriginY) {
                        continue;
                    }
                    mutable.set(
                            startX + (quartX << 2) + localX,
                            y,
                            startZ + (quartZ << 2) + localZ
                    );
                    BlockState state = level.getBlockState(mutable);
                    if (!state.getFluidState().isEmpty() || !state.is(ModBlockTags.STONES)) {
                        continue;
                    }
                    for (OreConfiguration.TargetBlockState target : targets) {
                        if (target.target.test(state, random)) {
                            return mutable.immutable();
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Non-loading counterpart of {@link #findSourceBiomeTarget}. It follows the same deterministic scan over
     * the source biome, but reads generator base columns so {@code /locate} can predict an unexplored chunk.
     * A target predicate is preferred when it already matches base terrain; tagged stone in the exact source
     * biome is retained as a conservative fallback because some mods create their host rock in an earlier
     * decoration feature that cannot be reproduced without generating the chunk.
     */
    static DepositCandidate relocatePredictedCandidateToSourceBiome(
            ServerLevel level,
            DepositCandidate candidate,
            ResourceLocation sourceBiomeId,
            List<RuleTest> targets
    ) {
        int chunkX = candidate.sourceChunkX();
        int chunkZ = candidate.sourceChunkZ();
        int minimumOriginY = level.getMinBuildHeight() + BEDROCK_CLEARANCE;
        int maximumOriginY = level.getMaxBuildHeight() - 1;
        int minimumQuartY = Math.floorDiv(minimumOriginY, 4);
        int maximumQuartY = Math.floorDiv(maximumOriginY, 4);
        int quartCount = Math.max(0, maximumQuartY - minimumQuartY + 1);
        if (quartCount == 0) {
            return null;
        }

        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        Map<Long, NoiseColumn> columns = new java.util.HashMap<>();
        RandomSource random = RandomSource.create(candidate.shapeSeed());
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        int firstQuartY = random.nextInt(quartCount);
        int firstHorizontalQuart = random.nextInt(16);
        int firstBlockInQuart = random.nextInt(64);
        BlockPos fallback = null;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        try {
            for (int quartYOffset = 0; quartYOffset < quartCount; quartYOffset++) {
                int quartYIndex = Math.floorMod(firstQuartY + quartYOffset, quartCount);
                int quartY = minimumQuartY + quartYIndex;
                int quartBlockY = quartY << 2;
                int sampleY = Mth.clamp(quartBlockY + 2, minimumOriginY, maximumOriginY);

                for (int horizontalOffset = 0; horizontalOffset < 16; horizontalOffset++) {
                    int horizontalQuart = (firstHorizontalQuart + horizontalOffset) & 15;
                    int quartX = horizontalQuart & 3;
                    int quartZ = horizontalQuart >> 2;
                    int sampleX = startX + (quartX << 2) + 2;
                    int sampleZ = startZ + (quartZ << 2) + 2;
                    ResourceLocation biomeId = BiomeNoiseCache.get(level,
                                    QuartPos.fromBlock(sampleX),
                                    QuartPos.fromBlock(sampleY),
                                    QuartPos.fromBlock(sampleZ)
                            ).unwrapKey().map(key -> key.location()).orElse(null);
                    if (!sourceBiomeId.equals(biomeId)) {
                        continue;
                    }

                    for (int blockOffset = 0; blockOffset < 64; blockOffset++) {
                        int packedLocal = (firstBlockInQuart + blockOffset) & 63;
                        int localX = packedLocal & 3;
                        int localZ = (packedLocal >> 2) & 3;
                        int localY = packedLocal >> 4;
                        int x = startX + (quartX << 2) + localX;
                        int y = quartBlockY + localY;
                        int z = startZ + (quartZ << 2) + localZ;
                        if (y < minimumOriginY || y > maximumOriginY) {
                            continue;
                        }
                        long columnKey = ((long) x << 32) ^ (z & 0xFFFFFFFFL);
                        NoiseColumn column = columns.computeIfAbsent(
                                columnKey,
                                ignored -> generator.getBaseColumn(x, z, level, randomState)
                        );
                        BlockState state = column.getBlock(y);
                        if (!state.getFluidState().isEmpty() || !state.is(ModBlockTags.STONES)) {
                            continue;
                        }
                        mutable.set(x, y, z);
                        if (fallback == null) {
                            fallback = mutable.immutable();
                        }
                        for (RuleTest target : targets) {
                            if (target.test(state, random)) {
                                return candidate.withUndergroundCenter(mutable);
                            }
                        }
                    }
                }
            }
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Could not predict source-biome host for ore-deposit candidate {} in {}",
                    candidate.depositId(), level.dimension().location(), exception
            );
            return null;
        }
        return fallback == null ? null : candidate.withUndergroundCenter(fallback);
    }

    /**
     * Section 9 diagnostic. Logs once (until new worldgen data invalidates the ore-frequency scan) rather
     * than every chunk: the tier table (capacity/scale/weight/share/expected attempts) plus, for each tier,
     * the resulting per-material weight table via {@link OreGenerationWeights#logTierOreTable}.
     */
    private static void logTierTableOnce(ServerLevel level, ResourceLocation biomeId, TierShares shares) {
        if (loggedTierTable || !OresAndDrillsMod.LOGGER.isDebugEnabled()) {
            return;
        }
        synchronized (OreDepositFeature.class) {
            if (loggedTierTable) {
                return;
            }

            double totalAttempts = totalDepositAttempts(level, biomeId);
            OresAndDrillsMod.LOGGER.debug("Ore deposits: spawn-calibration tier table (MEDIUM/LARGE physical shapes and reserves use compact patches; total_deposit_attempts_per_chunk={})",
                    String.format(java.util.Locale.ROOT, "%.6f", totalAttempts));
            ResourceLocation dimension = level.dimension().location();
            for (int tier = 0; tier < TIER_COUNT; tier++) {
                DepositTierMath.TierProfile profile = shares.profiles()[tier];
                DepositTier depositTier = profile.tier();
                double frequencyBudgetMultiplier = tier < TIER_MEDIUM
                        ? 0.0D
                        : OreGenerationWeights.tierFrequencyBudgetMultiplier(
                                dimension, biomeId, tier);
                String expectedRareAttempts = tier < TIER_MEDIUM
                        ? "per-material deterministic cell gate"
                        : String.format(java.util.Locale.ROOT, "%.6f",
                                totalAttempts * shares.share()[tier] * frequencyBudgetMultiplier
                                        * tierFrequencyMultiplier(tier));
                OresAndDrillsMod.LOGGER.debug(
                        "  Tier: {} | Normalized tier position: {} | Block scale position: {} | Characteristic block count: {} | Calculated minimum block count: {} | Calculated maximum block count: {} | Characteristic ore density: {} | Calculated minimum total ore: {} | Calculated maximum total ore: {} | Tier spawn weight: {} | Tier share: {} | Expected rare-event attempts per chunk: {}",
                        depositTier.serializedName(),
                        String.format(java.util.Locale.ROOT, "%.4f", profile.tierPosition()),
                        String.format(java.util.Locale.ROOT, "%.4f", profile.blockScalePosition()),
                        String.format(java.util.Locale.ROOT, "%.4f", profile.characteristicBlockCount()),
                        profile.minimumBlockCount(),
                        profile.maximumBlockCount(),
                        String.format(java.util.Locale.ROOT, "%.4f", profile.characteristicOreDensity()),
                        profile.minimumTotalOre(),
                        profile.maximumTotalOre(),
                        String.format(java.util.Locale.ROOT, "%.6f", shares.spawnWeight()[tier]),
                        String.format(java.util.Locale.ROOT, "%.6f", shares.share()[tier]),
                        expectedRareAttempts
                );
                OreGenerationWeights.logTierOreTable(
                        dimension,
                        biomeId,
                        depositTier.serializedName(),
                        tier
                );
            }
            loggedTierTable = true;
        }
    }

    /** Called whenever new worldgen observations arrive, so the debug table re-logs with fresh data. */
    static void resetDebugLog() {
        loggedTierTable = false;
    }

    private static double expectedLargeTierMaterialAttempts(
            ServerLevel level,
            ResourceLocation biomeId,
            ResourceLocation oreId,
            int tier,
            TierShares shares
    ) {
        double materialShare = OreGenerationWeights.selectionShare(
                level.dimension().location(), biomeId, oreId, tier
        );
        double frequencyBudgetMultiplier = OreGenerationWeights.tierFrequencyBudgetMultiplier(
                level.dimension().location(), biomeId, tier
        );
        return totalDepositAttempts(level, biomeId)
                * shares.share()[tier]
                * frequencyBudgetMultiplier
                * tierFrequencyMultiplier(tier)
                * materialShare;
    }

    private static boolean rejectAttempt(AttemptClaim claim, String reason) {
        if (claim != null && claim.claimKey() != 0L && !claim.requiresSharedClaim()
                && claim.selectedOreId() == null) {
            OresAndDrillsMod.LOGGER.debug(
                    "Data-driven ore-deposit candidate {} rejected: {}",
                    Long.toUnsignedString(claim.claimKey()), reason
            );
        }
        return false;
    }

    private static boolean completeAttempt(AttemptClaim claim) {
        return true;
    }

    private static String formatDiagnostic(double value) {
        return String.format(java.util.Locale.ROOT, "%.6f", value);
    }

    record DepositPosition(BlockPos pos, OreConfiguration.TargetBlockState target) {
    }

    private record WallDepositPosition(DepositPosition position, double priority) {
    }

    private record AttemptClaim(
            long claimKey,
            boolean requiresSharedClaim,
            ResourceLocation selectedOreId
    ) {
    }

    private record ShapeDimensions(int radiusX, int radiusZ, int depth) {
    }

    private record TierShares(
            DepositTierMath.TierProfile[] profiles,
            double[] normalizedScale,
            double[] spawnWeight,
            double[] share
    ) {
    }

    public record Configuration(
            List<OreConfiguration.TargetBlockState> targets,
            int sizeTier,
            float sourceProbability,
            float frequencyMultiplier,
            float sizeMultiplier,
            float richnessMultiplier,
            Optional<ResourceLocation> sourceBiome,
            Optional<DataDrivenSettings> dataDriven,
            Optional<OreVein> vein
    ) implements FeatureConfiguration {
        public Configuration {
            targets = List.copyOf(targets);
            sourceBiome = sourceBiome == null ? Optional.empty() : sourceBiome;
            dataDriven = dataDriven == null ? Optional.empty() : dataDriven;
            vein = vein == null ? Optional.empty() : vein;
        }

        public Configuration(
                List<OreConfiguration.TargetBlockState> targets,
                int sizeTier,
                float sourceProbability,
                float frequencyMultiplier,
                float sizeMultiplier,
                float richnessMultiplier,
                Optional<ResourceLocation> sourceBiome,
                Optional<DataDrivenSettings> dataDriven
        ) {
            this(targets, sizeTier, sourceProbability, frequencyMultiplier, sizeMultiplier,
                    richnessMultiplier, sourceBiome, dataDriven, Optional.empty());
        }

        public Configuration(
                List<OreConfiguration.TargetBlockState> targets,
                int sizeTier,
                float sourceProbability,
                float frequencyMultiplier,
                float sizeMultiplier,
                float richnessMultiplier,
                Optional<ResourceLocation> sourceBiome
        ) {
            this(targets, sizeTier, sourceProbability, frequencyMultiplier, sizeMultiplier,
                    richnessMultiplier, sourceBiome, Optional.empty(), Optional.empty());
        }

        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.list(OreConfiguration.TargetBlockState.CODEC).fieldOf("targets").forGetter(Configuration::targets),
                Codec.INT.optionalFieldOf("size_tier", TIER_TINY).forGetter(Configuration::sizeTier),
                Codec.FLOAT.optionalFieldOf("source_probability", 1.0F).forGetter(Configuration::sourceProbability),
                Codec.FLOAT.optionalFieldOf("frequency_multiplier", 1.0F).forGetter(Configuration::frequencyMultiplier),
                Codec.FLOAT.optionalFieldOf("size_multiplier", 1.0F).forGetter(Configuration::sizeMultiplier),
                Codec.FLOAT.optionalFieldOf("richness_multiplier", 1.0F).forGetter(Configuration::richnessMultiplier),
                ResourceLocation.CODEC.optionalFieldOf("source_biome").forGetter(Configuration::sourceBiome),
                DataDrivenSettings.CODEC.optionalFieldOf("data_driven").forGetter(Configuration::dataDriven),
                OreVein.CODEC.optionalFieldOf("vein").forGetter(Configuration::vein)
        ).apply(instance, Configuration::new));
    }

    /**
     * The original ore feature's own vein parameters; TINY/SMALL build their bodies with {@code Feature.ORE}
     * from them. With {@code vanillaPlacement} Minecraft runs the feature under that source's placement
     * modifiers ("vanilla-like" mode). Otherwise {@code sourcePlacement} holds those modifiers and the feature
     * evaluates them itself with a stable seed, thinning the resulting attempts into TINY/SMALL candidates.
     * {@code sourceSignature} keeps two different source features distinct after replacement.
     */
    public record OreVein(
            int size,
            float airExposureDiscard,
            boolean vanillaPlacement,
            String sourceSignature,
            List<PlacementModifier> sourcePlacement
    ) {
        public static final Codec<OreVein> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.INT.fieldOf("size").forGetter(OreVein::size),
                Codec.FLOAT.fieldOf("air_exposure_discard").forGetter(OreVein::airExposureDiscard),
                Codec.BOOL.optionalFieldOf("vanilla_placement", false).forGetter(OreVein::vanillaPlacement),
                Codec.STRING.optionalFieldOf("source", "").forGetter(OreVein::sourceSignature),
                PlacementModifier.CODEC.listOf().optionalFieldOf("source_placement", List.of())
                        .forGetter(OreVein::sourcePlacement)
        ).apply(instance, OreVein::new));

        public OreVein {
            sourceSignature = sourceSignature == null ? "" : sourceSignature;
            sourcePlacement = sourcePlacement == null ? List.of() : List.copyOf(sourcePlacement);
        }

        public OreVein(int size, float airExposureDiscard, boolean vanillaPlacement, String sourceSignature) {
            this(size, airExposureDiscard, vanillaPlacement, sourceSignature, List.of());
        }
    }

    public enum PlacementMode {
        DEFAULT("default"),
        PREFER_CAVE_WALL("prefer_cave_wall"),
        CAVE_WALL("cave_wall"),
        UNDERGROUND("underground");

        public static final Codec<PlacementMode> CODEC = Codec.STRING.xmap(
                value -> {
                    for (PlacementMode mode : values()) {
                        if (mode.serializedName.equals(value)) {
                            return mode;
                        }
                    }
                    throw new IllegalArgumentException("Unknown placement mode: " + value);
                },
                mode -> mode.serializedName
        );

        private final String serializedName;

        PlacementMode(String serializedName) {
            this.serializedName = serializedName;
        }
    }

    public record DataDrivenSettings(
            List<String> dimensions,
            int minY,
            int maxY,
            PlacementMode placement,
            boolean forced,
            double chance,
            int slot,
            long ruleSalt,
            float sizeMultiplier,
            float richnessMultiplier,
            int count
    ) {
        public static final Codec<DataDrivenSettings> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.STRING.listOf().fieldOf("dimensions").forGetter(DataDrivenSettings::dimensions),
                Codec.INT.fieldOf("min_y").forGetter(DataDrivenSettings::minY),
                Codec.INT.fieldOf("max_y").forGetter(DataDrivenSettings::maxY),
                PlacementMode.CODEC.fieldOf("placement").forGetter(DataDrivenSettings::placement),
                Codec.BOOL.optionalFieldOf("forced", false).forGetter(DataDrivenSettings::forced),
                Codec.DOUBLE.fieldOf("chance").forGetter(DataDrivenSettings::chance),
                Codec.INT.fieldOf("slot").forGetter(DataDrivenSettings::slot),
                Codec.LONG.fieldOf("rule_salt").forGetter(DataDrivenSettings::ruleSalt),
                Codec.FLOAT.fieldOf("size_multiplier").forGetter(DataDrivenSettings::sizeMultiplier),
                Codec.FLOAT.fieldOf("richness_multiplier").forGetter(DataDrivenSettings::richnessMultiplier),
                Codec.INT.optionalFieldOf("count", 1).forGetter(DataDrivenSettings::count)
        ).apply(instance, DataDrivenSettings::new));

        public DataDrivenSettings {
            dimensions = List.copyOf(dimensions);
        }

        boolean matchesDimension(ResourceLocation dimension) {
            return dimensions.stream().anyMatch(selector -> selector.equals(dimension.toString()));
        }
    }
}

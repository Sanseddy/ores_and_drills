package dev.world.level.levelgen;

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
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
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
    public static final int MAX_DEPOSIT_FOOTPRINT_DIAMETER = MAX_UNDERGROUND_RADIUS * 2;
    private static final int MAX_UNDERGROUND_BLOCKS = 720;
    /**
     * Compatibility guard for extreme per-ore sliders. A single chunk can receive blocks from several
     * neighbouring deposit origins; keeping the attachment payload bounded prevents a maxed-out preset
     * from producing pathological chunk packets (especially with optimized palette implementations).
     */
    private static final int MAX_DEPOSIT_ENTRIES_PER_CHUNK = 360;
    /**
     * TINY/SMALL deposits inherit arbitrary third-party placed-feature counts. Keep the expensive
     * cave-wall search bounded when every ore is set to its maximum frequency. The configurable
     * rare-tier ceiling is honoured up to this hard runtime ceiling, so ordinary settings retain their
     * configured budget while pathological presets cannot monopolize worldgen workers.
     */
    private static final int MAX_SMALL_DEPOSIT_ATTEMPTS_PER_CHUNK = 12;
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

    /** Compatibility indices for persisted data and commands; all numeric behavior is ordinal-derived. */
    public static final int TIER_TINY = DepositTier.TINY.ordinal();
    public static final int TIER_SMALL = DepositTier.SMALL.ordinal();
    public static final int TIER_MEDIUM = DepositTier.MEDIUM.ordinal();
    public static final int TIER_LARGE = DepositTier.LARGE.ordinal();
    public static final int TIER_COUNT = DepositTier.values().length;
    private static final long ANCHOR_SEED_X = 341873128712L;
    private static final long ANCHOR_SEED_Z = 132897987541L;
    /** Salt for the immutable per-deposit plan (shape, block count and reserve). */
    private static final long DEPOSIT_PLAN_SALT = 0x504C414E4F52454CL;
    /** Salt for the TINY/SMALL retention decision shared by generation and /locate. */
    private static final long SMALL_TIER_GATE_SALT = 0x534D414C4C474154L;
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
    /** Per-chunk reservations made before a small deposit starts its cave-wall search. */
    private static final ConcurrentHashMap<Long, AtomicInteger> CLAIMED_SMALL_ATTEMPTS = new ConcurrentHashMap<>();
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
    private static final Map<SmallDiagnosticKey, SmallAttemptStats> SMALL_ATTEMPT_DIAGNOSTICS = new ConcurrentHashMap<>();
    private static final Set<String> LOGGED_SMALL_BUDGETS = ConcurrentHashMap.newKeySet();

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
        return level.getUncachedNoiseBiome(
                QuartPos.fromBlock(sample.getX()),
                QuartPos.fromBlock(sample.getY()),
                QuartPos.fromBlock(sample.getZ())
        );
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

    private static DepositTierMath.SmallTierParameters smallTierParameters() {
        return new DepositTierMath.SmallTierParameters(
                OreDepositConfig.SMALL_DEPOSIT_FREQUENCY_SHARE,
                OreDepositConfig.MIN_SMALL_DEPOSIT_FREQUENCY_SHARE,
                OreDepositConfig.SMALL_TO_TINY_FREQUENCY_RATIO
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

    /**
     * Decides the TINY/SMALL retention gate from immutable world data.  Keeping this separate from the
     * placed-feature random stream lets /locate reject chunks that cannot retain this material before it
     * has to generate and inspect them.
     */
    private static boolean passesSmallTierRetentionGate(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            int tier,
            String materialKey,
            ResourceLocation biomeId,
            double probability
    ) {
        if (probability <= 0.0D) {
            return false;
        }
        if (probability >= 1.0D) {
            return true;
        }
        long seed = DepositSeedMath.cellSeed(
                level.getSeed(),
                level.dimension().location().toString(),
                biomeId == null ? "" : biomeId.toString(),
                materialKey,
                DepositLayouts.tierSalt(tier),
                chunkX,
                chunkZ,
                0,
                SMALL_TIER_GATE_SALT,
                ANCHOR_SEED_X,
                ANCHOR_SEED_Z
        );
        return SmallTierSeedMath.unitDouble(seed) < probability;
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
        long shapeSeed = depositPlanSeedValue(level, center, tier, oreId);
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
        if (settings.chance() >= 1.0D) {
            return true;
        }
        long seed = dataDrivenSeed(level, cellX, cellZ, biomeId, oreId, tier, settings)
                ^ 0x4652455155454E43L;
        return RandomSource.create(SeedMixer.mix(seed)).nextDouble() < settings.chance();
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

    /** Per-tier frequency tuning on top of the shared tier curves: SMALL rarer, LARGE more common. */
    private static double tierFrequencyMultiplier(int tier) {
        if (tier == TIER_SMALL) {
            return OreDepositConfig.SMALL_DEPOSIT_FREQUENCY_MULTIPLIER;
        }
        if (tier == TIER_LARGE) {
            return OreDepositConfig.LARGE_DEPOSIT_FREQUENCY_MULTIPLIER;
        }
        return 1.0D;
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

    private static int smallAttemptLimitPerMaterial() {
        return DepositTierMath.smallAttemptBudget(
                OreDepositConfig.MAX_GENERATION_ATTEMPTS_PER_CHUNK,
                MAX_SMALL_DEPOSIT_ATTEMPTS_PER_CHUNK
        );
    }

    /**
     * Reserves one bounded expensive attempt before cave-wall discovery. Failed searches deliberately
     * keep their reservation: otherwise a dense max-frequency preset can repeatedly spend one
     * material's search budget on failures.
     */
    private static boolean claimSmallAttempt(ServerLevel level, int chunkX, int chunkZ, int tier, String materialKey) {
        // Keep the safety limit per material. A shared per-chunk cap made a high Frequency setting for
        // coal consume the entire TINY/SMALL budget and silently suppress iron, copper, etc.
        long key = computeMaterialClaimKey(level, chunkX, chunkZ, tier, materialKey);
        AtomicInteger attempts = CLAIMED_SMALL_ATTEMPTS.computeIfAbsent(key, ignored -> new AtomicInteger());
        int limit = smallAttemptLimitPerMaterial();
        while (true) {
            int current = attempts.get();
            if (current >= limit) {
                return false;
            }
            if (attempts.compareAndSet(current, current + 1)) {
                if (CLAIMED_SMALL_ATTEMPTS.size() > MAX_CLAIM_CACHE_ENTRIES) {
                    CLAIMED_SMALL_ATTEMPTS.clear();
                }
                return true;
            }
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
        if (tier < 0 || tier >= TIER_COUNT) {
            return false;
        }
        TierShares shares = computeTierShares();
        if (!isTierAnchorChunk(chunkX, chunkZ, tier)) {
            return false;
        }
        if (tier < TIER_MEDIUM) {
            List<OreGenerationWeights.OreGenerationData> entries = OreGenerationWeights.eligibleEntries(
                    level.dimension().location(), biomeId
            );
            if (entries.isEmpty()) {
                // Real TINY/SMALL placement also fails open when bootstrap scanning produced no weight
                // data at all. Predict conservatively so /locate verifies actual chunks in that state.
                return !OreGenerationWeights.hasRecordedData(level.dimension().location(), biomeId);
            }
            double maximum = OreGenerationWeights.maxOriginalFrequency(entries);
            for (OreGenerationWeights.OreGenerationData entry : entries) {
                if (!entry.materialKey().equals(materialKey)) {
                    continue;
                }
                DepositTierMath.SmallTierPlan plan = DepositTierMath.smallTierPlan(
                        entry.originalFrequency(), maximum,
                        new DepositTierMath.TierDistribution(
                                shares.profiles(), new double[TIER_COUNT], shares.normalizedScale(),
                                shares.spawnWeight(), shares.share()
                        ),
                        smallTierParameters(),
                        OreGenerationWeights.rarityParameters()
                );
                if (tier >= plan.gateProbabilities().length) {
                    return false;
                }
                Block block = BuiltInRegistries.BLOCK.get(entry.oreId());
                if (block == null) {
                    return false;
                }
                DepositTierLayout layout = DepositLayouts.forTier(
                        OreDepositTier.fromIndex(tier).orElseThrow()
                );
                double gateProbability = DepositTierMath.cellCandidateProbability(
                        plan.expectedAttempts()[tier] * tierFrequencyMultiplier(tier),
                        layout.cellSizeChunks(),
                        liveFrequencyMultiplier(block)
                );
                return passesSmallTierRetentionGate(
                        level, chunkX, chunkZ, tier, materialKey, biomeId, gateProbability
                );
            }
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
                if (tier < TIER_MEDIUM) {
                    return true;
                }
                // Match the real feature's conservative spacing pre-check before /locate generates the
                // candidate chunk.
                // The predictor samples the chunk center while the placed feature may start anywhere inside
                // it, hence the additional center-to-corner allowance.
                BlockPos predictedOrigin = chunkSamplePos(chunkX, chunkZ, chunkSampleY(level));
                return LargeDepositSpatialIndex.get(level).canPossiblyFit(
                        predictedOrigin, tier,
                        CHUNK_CENTER_TO_CORNER_DISTANCE + MAX_TAGGED_STONE_RELOCATION_RADIUS
                );
            }
        }
        return false;
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
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Configuration config = context.config();

        // A local palette serializes each state through Block.BLOCK_STATE_REGISTRY. An id of -1 is
        // written verbatim and disconnects the client while it reads the chunk. This should never be
        // possible after normal registry setup, but checking both refresh variants keeps a broken
        // optional compatibility mixin (notably a chunk-container replacement) from poisoning a save.
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
                claim = new AttemptClaim(plannedCandidate.depositId(), false, null, null);
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
                claim = new AttemptClaim(plannedCandidate.depositId(), false, null, null);
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
            origin = uncutLensOrigin(
                    level, taggedStoneOrigin, targets, radiusX, radiusZ, maxDepth, dataSettings == null,
                    plannedCandidate.shapeSeed()
            );
            plannedCandidate = plannedCandidate.withUndergroundCenter(origin);
        }

        if (tier >= TIER_MEDIUM && (dataSettings == null || !dataSettings.forced())
                && !LargeDepositSpatialIndex.get(level.getLevel())
                .canPossiblyFit(origin, tier, 0.0D)) {
            CLAIMED_DEPOSIT_ATTEMPTS.add(claim.claimKey());
            trimIfOversized(CLAIMED_DEPOSIT_ATTEMPTS);
            return rejectAttempt(claim, "large_spacing_precheck");
        }

        List<DepositPosition> positions = wallAnchor == null
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
        positions = limitByChunkCompatibilityBudget(level, positions);
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

        int[] amounts = distributeOre(positions, origin, radiusX, radiusZ, totalOre);
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
            if (used >= MAX_DEPOSIT_ENTRIES_PER_CHUNK) {
                skipped++;
                continue;
            }
            usedByChunk.put(chunkKey, used + 1);
            limited.add(position);
        }

        if (skipped > 0) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposits: trimmed {} blocks from a chunk-safe placement budget of {} entries",
                    skipped, MAX_DEPOSIT_ENTRIES_PER_CHUNK
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
     * Nearest origin (searched outward in rings, the original spot first) whose full MEDIUM/LARGE lens lies
     * inside the write bounds, in host rock without fluids or cave air, and outside every structure that is
     * already planned around it. When no fully clear spot exists the least-cut one is kept.
     */
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
        if (!(level instanceof WorldGenRegion region)) {
            return null;
        }
        ChunkPos center = region.getCenter();
        return GenerationWriteBounds.around(center.x, center.z, FEATURE_WRITE_RADIUS_CHUNKS);
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
            // RandomFeatureConfiguration and placement modifiers were already included while the source
            // feature's expected frequency was scanned. Candidate existence now comes only from the cell seed.
            OreGenerationWeights.MaterialFrequency frequency = OreGenerationWeights.materialFrequency(
                    level.dimension().location(), biomeId, candidateOreId
            );
            double originalFrequency = frequency.originalFrequency() > 0.0D
                    ? frequency.originalFrequency()
                    : 1.0D;
            double maximumOriginalFrequency = frequency.maximumOriginalFrequency() > 0.0D
                    ? frequency.maximumOriginalFrequency()
                    : originalFrequency;
            DepositTierMath.SmallTierPlan plan = DepositTierMath.smallTierPlan(
                    originalFrequency,
                    maximumOriginalFrequency,
                    new DepositTierMath.TierDistribution(
                            shares.profiles(), new double[TIER_COUNT], shares.normalizedScale(),
                            shares.spawnWeight(), shares.share()
                    ),
                    smallTierParameters(),
                    OreGenerationWeights.rarityParameters()
            );
            logSmallMaterialBudget(
                    level, biomeId, candidateOreId, frequency, plan, shares, frequencyMultiplier
            );
            SmallDiagnosticKey diagnosticKey = new SmallDiagnosticKey(
                    level.dimension().location(),
                    biomeId == null ? "<direct>" : biomeId.toString(),
                    frequency.materialKey().isEmpty() ? candidateMaterialKey : frequency.materialKey(),
                    candidateOreId,
                    tier
            );
            SmallAttemptStats stats = SMALL_ATTEMPT_DIAGNOSTICS.computeIfAbsent(
                    diagnosticKey, ignored -> new SmallAttemptStats()
            );
            stats.sourceAttempts.increment();
            // The observed source stream has already been reduced to an expected frequency. The immutable
            // per-cell gate below is now the sole owner of candidate existence for both generation and locate.
            if (!predictsMaterialForChunk(level, chunkX, chunkZ, tier, candidateMaterialKey, biomeId)) {
                return null;
            }
            // TINY/SMALL retain the source material identity. Selecting from the global weighted table here
            // would apply rarity twice and let one ore's slider move every other ore's small deposits.
            if (!claimSmallAttempt(level, chunkX, chunkZ, tier, candidateMaterialKey)) {
                return null;
            }
            stats.launchedAttempts.increment();
            return new AttemptClaim(
                    0L,
                    false,
                    diagnosticKey,
                    candidateOreId
            );
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
                null,
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
                    ResourceLocation biomeId = level.getUncachedNoiseBiome(
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
            OresAndDrillsMod.LOGGER.debug("Ore deposits: tier table (total_deposit_attempts_per_chunk={})",
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
        LOGGED_SMALL_BUDGETS.clear();
    }

    private static void logSmallMaterialBudget(
            ServerLevel level,
            ResourceLocation biomeId,
            ResourceLocation oreId,
            OreGenerationWeights.MaterialFrequency frequency,
            DepositTierMath.SmallTierPlan plan,
            TierShares shares,
            float frequencyMultiplier
    ) {
        if (!OresAndDrillsMod.LOGGER.isDebugEnabled() || frequency.originalFrequency() <= 0.0D) {
            return;
        }
        String biomeName = biomeId == null ? "<direct>" : biomeId.toString();
        String logKey = level.dimension().location() + "|" + biomeName + "|" + frequency.materialKey();
        if (!LOGGED_SMALL_BUDGETS.add(logKey)) {
            return;
        }

        double mediumExpected = expectedLargeTierMaterialAttempts(
                level, biomeId, oreId, TIER_MEDIUM, shares
        );
        double largeExpected = expectedLargeTierMaterialAttempts(
                level, biomeId, oreId, TIER_LARGE, shares
        );
        double tinyExpected = plan.expectedAttempts().length > TIER_TINY
                ? plan.expectedAttempts()[TIER_TINY] * frequencyMultiplier
                : 0.0D;
        double smallExpected = plan.expectedAttempts().length > TIER_SMALL
                ? plan.expectedAttempts()[TIER_SMALL] * tierFrequencyMultiplier(TIER_SMALL) * frequencyMultiplier
                : 0.0D;
        OresAndDrillsMod.LOGGER.debug(
                "Ore deposit frequency: Ore ID={} | Original expected frequency={} | Small-deposit budget={} | TINY expected attempts={} | SMALL expected attempts={} | TINY + SMALL total={} | Percentage of original frequency retained={}% | MEDIUM expected attempts={} | LARGE expected attempts={} | Biome={}",
                oreId,
                formatDiagnostic(frequency.originalFrequency()),
                formatDiagnostic(frequency.originalFrequency()
                * OreDepositConfig.SMALL_DEPOSIT_FREQUENCY_SHARE * frequencyMultiplier),
                formatDiagnostic(tinyExpected),
                formatDiagnostic(smallExpected),
                formatDiagnostic(plan.combinedExpectedAttempts() * frequencyMultiplier),
                formatDiagnostic(plan.retainedFraction() * frequencyMultiplier * 100.0D),
                formatDiagnostic(mediumExpected),
                formatDiagnostic(largeExpected),
                biomeName
        );

        double minimumRetention = OreDepositConfig.MIN_SMALL_DEPOSIT_FREQUENCY_SHARE;
        if (plan.retainedFraction() + 1.0E-9D < minimumRetention) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposits: TINY+SMALL retain only {}% of original frequency for {} in {} (configured minimum {}%)",
                    formatDiagnostic(plan.retainedFraction() * 100.0D), oreId, biomeName,
                    formatDiagnostic(minimumRetention * 100.0D)
            );
        }
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
                && claim.smallDiagnosticKey() == null && claim.selectedOreId() == null) {
            OresAndDrillsMod.LOGGER.debug(
                    "Data-driven ore-deposit candidate {} rejected: {}",
                    Long.toUnsignedString(claim.claimKey()), reason
            );
        }
        if (claim != null && claim.smallDiagnosticKey() != null) {
            SmallAttemptStats stats = SMALL_ATTEMPT_DIAGNOSTICS.get(claim.smallDiagnosticKey());
            if (stats != null) {
                stats.rejections.computeIfAbsent(reason, ignored -> new LongAdder()).increment();
                maybeLogSmallAttemptStats(claim.smallDiagnosticKey(), stats);
            }
        }
        return false;
    }

    private static boolean completeAttempt(AttemptClaim claim) {
        if (claim != null && claim.smallDiagnosticKey() != null) {
            SmallAttemptStats stats = SMALL_ATTEMPT_DIAGNOSTICS.get(claim.smallDiagnosticKey());
            if (stats != null) {
                stats.successfulDeposits.increment();
                maybeLogSmallAttemptStats(claim.smallDiagnosticKey(), stats);
            }
        }
        return true;
    }

    private static void maybeLogSmallAttemptStats(SmallDiagnosticKey key, SmallAttemptStats stats) {
        long launched = stats.launchedAttempts.sum();
        if (launched <= 0L || (launched & (launched - 1L)) != 0L) {
            return;
        }
        long successful = stats.successfulDeposits.sum();
        double successRate = successful / (double) launched;
        String reasons = stats.rejections.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + "=" + entry.getValue().sum())
                .collect(java.util.stream.Collectors.joining(", "));
        OresAndDrillsMod.LOGGER.debug(
                "Ore deposit placement: Tier={} | Ore ID={} | Source attempts={} | Launched attempts={} | Successful deposits={} | Success rate={}% | Rejections=[{}]",
                DepositTier.byIndex(key.tier()).serializedName(), key.oreId(), stats.sourceAttempts.sum(),
                launched, successful, formatDiagnostic(successRate * 100.0D), reasons
        );
        if (launched >= 16L && successRate < 0.25D && stats.lowSuccessWarning.compareAndSet(false, true)) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposits: most {} attempts for {} are rejected by placement conditions (success rate {}%, reasons: [{}])",
                    DepositTier.byIndex(key.tier()).serializedName(), key.oreId(),
                    formatDiagnostic(successRate * 100.0D), reasons
            );
        }
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
            SmallDiagnosticKey smallDiagnosticKey,
            ResourceLocation selectedOreId
    ) {
    }

    private record SmallDiagnosticKey(
            ResourceLocation dimension,
            String biome,
            String materialKey,
            ResourceLocation oreId,
            int tier
    ) {
    }

    private static final class SmallAttemptStats {
        private final LongAdder sourceAttempts = new LongAdder();
        private final LongAdder launchedAttempts = new LongAdder();
        private final LongAdder successfulDeposits = new LongAdder();
        private final Map<String, LongAdder> rejections = new ConcurrentHashMap<>();
        private final AtomicBoolean lowSuccessWarning = new AtomicBoolean();
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
            Optional<DataDrivenSettings> dataDriven
    ) implements FeatureConfiguration {
        public Configuration {
            targets = List.copyOf(targets);
            sourceBiome = sourceBiome == null ? Optional.empty() : sourceBiome;
            dataDriven = dataDriven == null ? Optional.empty() : dataDriven;
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
                    richnessMultiplier, sourceBiome, Optional.empty());
        }

        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.list(OreConfiguration.TargetBlockState.CODEC).fieldOf("targets").forGetter(Configuration::targets),
                Codec.INT.optionalFieldOf("size_tier", TIER_TINY).forGetter(Configuration::sizeTier),
                Codec.FLOAT.optionalFieldOf("source_probability", 1.0F).forGetter(Configuration::sourceProbability),
                Codec.FLOAT.optionalFieldOf("frequency_multiplier", 1.0F).forGetter(Configuration::frequencyMultiplier),
                Codec.FLOAT.optionalFieldOf("size_multiplier", 1.0F).forGetter(Configuration::sizeMultiplier),
                Codec.FLOAT.optionalFieldOf("richness_multiplier", 1.0F).forGetter(Configuration::richnessMultiplier),
                ResourceLocation.CODEC.optionalFieldOf("source_biome").forGetter(Configuration::sourceBiome),
                DataDrivenSettings.CODEC.optionalFieldOf("data_driven").forGetter(Configuration::dataDriven)
        ).apply(instance, Configuration::new));
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

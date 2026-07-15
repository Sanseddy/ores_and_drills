package dev.world.level.levelgen;

import dev.FactoryExpansionMod;
import dev.config.OreDepositConfig;
import dev.config.OreOverrides;
import dev.config.OreSettingsPresetManager;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
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
    private static final int MAX_TAGGED_STONE_VERTICAL_RELOCATION_RADIUS = 64;
    /**
     * A failed relocation used to exhaustively inspect a radius-64 three-dimensional diamond. With many
     * retained modded ore attempts that can mean millions of block-state reads for one chunk even though
     * the FEATURES step may only write to its 3x3 chunk window. Nearby stone is overwhelmingly found in
     * the first few shells; cap the exceptional no-stone path so one incompatible placement cannot stall
     * the worldgen worker.
     */
    private static final int MAX_TAGGED_STONE_SEARCH_CHECKS = 4_096;
    /** Vanilla 1.21.1 ChunkPyramid configures FEATURES with blockStateWriteRadius(1). */
    private static final int FEATURE_WRITE_RADIUS_CHUNKS = 1;
    /** A persisted point may lie at either edge of a lens, so its complete footprint can span two radii. */
    public static final int MAX_DEPOSIT_FOOTPRINT_DIAMETER = MAX_UNDERGROUND_RADIUS * 2;
    private static final int MAX_UNDERGROUND_BLOCKS = 360;
    /**
     * Compatibility guard for extreme per-ore sliders. A single chunk can receive blocks from several
     * neighbouring deposit origins; keeping the attachment payload bounded prevents a maxed-out preset
     * from producing pathological chunk packets (especially with optimized palette implementations).
     */
    private static final int MAX_DEPOSIT_ENTRIES_PER_CHUNK = 256;
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
    /**
     * Minimum clearance (in blocks) an ore position must keep above the dimension's actual floor
     * ({@link net.minecraft.world.level.LevelHeightAccessor#getMinBuildHeight()}, read per-dimension rather
     * than assuming a fixed world floor) so veins don't spawn into the bedrock layer.
     */
    private static final int BEDROCK_CLEARANCE = 5;

    /** Compatibility indices for persisted data and commands; all numeric behavior is ordinal-derived. */
    public static final int TIER_TINY = DepositTier.TINY.ordinal();
    public static final int TIER_SMALL = DepositTier.SMALL.ordinal();
    public static final int TIER_MEDIUM = DepositTier.MEDIUM.ordinal();
    public static final int TIER_LARGE = DepositTier.LARGE.ordinal();
    public static final int TIER_COUNT = DepositTier.values().length;
    private static final long ANCHOR_SEED_X = 341873128712L;
    private static final long ANCHOR_SEED_Z = 132897987541L;
    private static final long TIER_SALT_BASE = 0x4F52454445504F53L;
    /** Salt for the immutable per-deposit plan (shape, block count and reserve). */
    private static final long DEPOSIT_PLAN_SALT = 0x504C414E4F52454CL;
    /** Salt for the TINY/SMALL retention decision shared by generation and /locate. */
    private static final long SMALL_TIER_GATE_SALT = 0x534D414C4C474154L;
    /** One forced attempt in every chunk containing Toxic Caves: no frequency/region lottery. */
    private static final int FORCED_URANIUM_REGION_SPACING_CHUNKS = 1;
    /** Independent salt for deterministic full-height Toxic Caves traversal. */
    private static final long FORCED_URANIUM_HEIGHT_SALT = 0x5552414E48454947L;
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
    /** Vertical toxic-caves probing is shared by generation and /locate; cache it per live level/chunk. */
    private static final Map<ServerLevel, Map<Long, Boolean>> TOXIC_CAVES_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final int MAX_TOXIC_CAVES_CACHE_ENTRIES = 131_072;
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

    private static RandomSource chunkTierSeed(ServerLevel level, int chunkX, int chunkZ, int tier) {
        long salt = TIER_SALT_BASE + tier;
        long base = level.getSeed() ^ salt ^ (((long) chunkX & 0xFFFFFFFFL) << 32) ^ (chunkZ & 0xFFFFFFFFL);
        return RandomSource.create(base);
    }

    private static RandomSource attemptSlotSeed(ServerLevel level, int chunkX, int chunkZ, int tier, int attemptIndex) {
        RandomSource chunkSeed = chunkTierSeed(level, chunkX, chunkZ, tier);
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
        long dimensionSalt = level.dimension().location().toString().hashCode();
        long materialSalt = oreId.toString().hashCode();
        long seed = level.getSeed()
                ^ DEPOSIT_PLAN_SALT
                ^ (dimensionSalt * 132897987541L)
                ^ (materialSalt * 2654435761L)
                ^ (origin.asLong() * 341873128712L)
                ^ ((long) tier * 999999937L);
        return RandomSource.create(seed);
    }

    private static RandomSource depositCenterSeed(ServerLevel level, int chunkX, int chunkZ, int tier, String materialKey) {
        long dimensionSalt = level.dimension().location().toString().hashCode();
        long materialSalt = materialKey.hashCode();
        long seed = level.getSeed()
                ^ (DEPOSIT_PLAN_SALT * 31L)
                ^ (dimensionSalt * 132897987541L)
                ^ (materialSalt * 2654435761L)
                ^ (((long) chunkX & 0xFFFFFFFFL) << 32)
                ^ (chunkZ & 0xFFFFFFFFL)
                ^ ((long) tier * 999999937L);
        return RandomSource.create(seed);
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
            double probability
    ) {
        if (probability <= 0.0D) {
            return false;
        }
        if (probability >= 1.0D) {
            return true;
        }
        long dimensionSalt = level.dimension().location().toString().hashCode();
        long materialSalt = materialKey.hashCode();
        long seed = level.getSeed()
                ^ SMALL_TIER_GATE_SALT
                ^ (dimensionSalt * 132897987541L)
                ^ (materialSalt * 2654435761L)
                ^ (((long) chunkX & 0xFFFFFFFFL) << 32)
                ^ (chunkZ & 0xFFFFFFFFL)
                ^ ((long) tier * 999999937L);
        return SmallTierSeedMath.unitDouble(seed) < probability;
    }

    private static float liveFrequencyMultiplier(Block block) {
        return OreOverrides.lookupConfigured(block)
                .map(OreOverrides.OreOverride::frequency)
                .orElseGet(() -> OreSettingsPresetManager.resolve(block).multipliers().frequency());
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
        RandomSource random = depositCenterSeed(level, chunkX, chunkZ, tier, materialKey);
        Holder<Biome> biome = level.getBiome(chunkSamplePos(chunkX, chunkZ, chunkSampleY(level)));
        ResourceLocation biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);
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
                totalDepositAttempts(level, biomeId) * shares.share()[tier] * frequencyBudgetMultiplier,
                0.0D,
                OreDepositConfig.MAX_GENERATION_ATTEMPTS_PER_CHUNK
        );
        int guaranteedAttempts = (int) Math.floor(tierAttempts);
        double fractionalChance = tierAttempts - guaranteedAttempts;

        boolean extraAttempt = fractionalChance > 0.0D && chunkTierSeed(level, chunkX, chunkZ, tier).nextDouble() < fractionalChance;
        return guaranteedAttempts + (extraAttempt ? 1 : 0);
    }

    /** One fixed center per maximum-spacing cell makes MEDIUM/LARGE ownership independent of chunk order. */
    public static int largeDepositRegionSpacingChunks() {
        int maximumSpacing = Mth.clamp(
                OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING.get(),
                OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING_LIMIT,
                OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING_LIMIT
        );
        return Math.max(1, (maximumSpacing + 15) / 16);
    }

    public static long largeDepositRegionAnchorChunkKey(int regionX, int regionZ) {
        int spacing = largeDepositRegionSpacingChunks();
        return ChunkPos.asLong(regionX * spacing + spacing / 2, regionZ * spacing + spacing / 2);
    }

    private static boolean isLargeDepositRegionAnchor(int chunkX, int chunkZ) {
        int spacing = largeDepositRegionSpacingChunks();
        long anchor = largeDepositRegionAnchorChunkKey(Math.floorDiv(chunkX, spacing), Math.floorDiv(chunkZ, spacing));
        return chunkX == ChunkPos.getX(anchor) && chunkZ == ChunkPos.getZ(anchor);
    }

    private static int largeTierForRegion(ServerLevel level, int chunkX, int chunkZ, TierShares shares) {
        int spacing = largeDepositRegionSpacingChunks();
        int regionX = Math.floorDiv(chunkX, spacing);
        int regionZ = Math.floorDiv(chunkZ, spacing);
        long seed = level.getSeed() ^ 0x4C41524745544952L
                ^ (regionX * ANCHOR_SEED_X) ^ (regionZ * ANCHOR_SEED_Z);
        double mediumShare = shares.share()[TIER_MEDIUM];
        double largeShare = shares.share()[TIER_LARGE];
        double largeChance = largeShare / Math.max(1.0E-9D, mediumShare + largeShare);
        return RandomSource.create(seed).nextDouble() < largeChance ? TIER_LARGE : TIER_MEDIUM;
    }

    public static boolean usesLocateAnchorGrid(String materialKey, int tier) {
        return usesForcedAlexUraniumAnchors(materialKey, tier)
                || (tier >= TIER_MEDIUM && tier < TIER_COUNT);
    }

    public static long locateAnchorChunkKey(ServerLevel level, int regionX, int regionZ, String materialKey, int tier) {
        return usesForcedAlexUraniumAnchors(materialKey, tier)
                ? forcedUraniumRegionAnchorChunkKey(level, regionX, regionZ)
                : largeDepositRegionAnchorChunkKey(regionX, regionZ);
    }

    private static long computeAttemptClaimKey(ServerLevel level, int chunkX, int chunkZ, int tier, int attemptIndex) {
        long salt = TIER_SALT_BASE + tier;
        long dimensionSalt = level.dimension().location().toString().hashCode();
        return level.getSeed() ^ (salt * 341873128712L) ^ (dimensionSalt * 132897987541L)
                ^ (((long) chunkX & 0xFFFFFFFFL) << 32) ^ (chunkZ & 0xFFFFFFFFL) ^ tier
                ^ ((long) attemptIndex * 999999937L);
    }

    private static long computeMaterialClaimKey(ServerLevel level, int chunkX, int chunkZ, int tier, String materialKey) {
        long salt = TIER_SALT_BASE + tier;
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
        if (tier >= TIER_MEDIUM && isLargeDepositRegionAnchor(chunkX, chunkZ)) {
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

            double randomValue = attemptSlotSeed(level, chunkX, chunkZ, tier, attemptIndex).nextDouble();
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
        if (tier < 0 || tier >= TIER_COUNT) {
            return false;
        }

        if (usesForcedAlexUraniumAnchors(materialKey, tier)) {
            return materialKey.equals("uranium")
                    && isForcedUraniumRegionAnchor(level, chunkX, chunkZ)
                    && forcedAlexUraniumTier(level, chunkX, chunkZ) == tier
                    && hasAlexToxicCavesBiome(level, chunkX, chunkZ);
        }

        Holder<Biome> biome = level.getBiome(chunkSamplePos(chunkX, chunkZ, chunkSampleY(level)));
        ResourceLocation biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);
        TierShares shares = computeTierShares();
        if (tier >= TIER_MEDIUM
                && (!isLargeDepositRegionAnchor(chunkX, chunkZ) || largeTierForRegion(level, chunkX, chunkZ, shares) != tier)) {
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
                double gateProbability = DepositTierMath.smallTierGateProbability(
                        1.0D, plan.gateProbabilities()[tier], liveFrequencyMultiplier(block)
                );
                return passesSmallTierRetentionGate(level, chunkX, chunkZ, tier, materialKey, gateProbability);
            }
            return false;
        }
        int attempts = attemptsForChunkTier(level, chunkX, chunkZ, biomeId, tier, shares);
        if (tier >= TIER_MEDIUM && isLargeDepositRegionAnchor(chunkX, chunkZ)) {
            attempts = Math.min(1, attempts);
        }
        if (attempts <= 0) {
            return false;
        }

        ResourceLocation dimension = level.dimension().location();
        for (int attemptIndex = 0; attemptIndex < attempts; attemptIndex++) {
            double randomValue = attemptSlotSeed(level, chunkX, chunkZ, tier, attemptIndex).nextDouble();
            if (OreGenerationWeights.isMaterialSelectedForRegion(
                    dimension, biomeId, materialKey, tier, randomValue
            )) {
                if (tier < TIER_MEDIUM) {
                    return true;
                }
                // Match the real feature's conservative spacing pre-check before /locate generates the
                // candidate chunk. Toxic-cave exclusion is deliberately deferred to real chunk generation:
                // probing a complete vertical noise-biome column for every predicted normal-material anchor
                // can stall the server thread, while a conservative false positive is safely rejected by
                // findEligibleAttempt after the candidate chunk is generated.
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

    @Override
    public boolean place(FeaturePlaceContext<Configuration> context) {
        WorldGenLevel level = context.level();
        RandomSource random = context.random();
        Configuration config = context.config();

        BlockPos origin = context.origin();
        int chunkX = origin.getX() >> 4;
        int chunkZ = origin.getZ() >> 4;

        int anchorTier = DepositTier.byIndex(config.sizeTier()).ordinal();
        AttemptClaim claim = findEligibleAttempt(
                level.getLevel(), origin, config.targets(), anchorTier, config.forcedAlexUranium(),
                config.sourceProbability(), config.frequencyMultiplier(), random
        );
        if (claim == null) {
            return false;
        }
        List<OreConfiguration.TargetBlockState> targets = claim.selectedOreId() == null
                ? config.targets()
                : targetsForSelectedMaterial(config.targets(), claim.selectedOreId());
        if (targets.isEmpty()) {
            return rejectAttempt(claim, "selected_material_missing");
        }

        // Toxic Caves is a three-dimensional biome. The placement modifier's origin can be in a
        // neighbouring biome column, so select an actual radrock position in the toxic cave before
        // constructing the deposit plan. This is also the predicate /locate uses for this anchor.
        if (config.forcedAlexUranium()) {
            BlockPos toxicCavesOrigin = forcedAlexUraniumOrigin(level, origin);
            if (toxicCavesOrigin == null) {
                return rejectAttempt(claim, "no_toxic_caves_stone_in_anchor");
            }
            origin = toxicCavesOrigin;
        }

        int tier = resolvedTierForChunk(
                level.getLevel().getSeed(), chunkX, chunkZ, anchorTier, config.forcedAlexUranium()
        );

        ResourceLocation plannedOreId = BuiltInRegistries.BLOCK.getKey(targets.getFirst().state.getBlock());
        if (plannedOreId == null) {
            return rejectAttempt(claim, "unregistered_ore_block");
        }
        // TINY/SMALL intentionally retain vanilla placement origin and RNG. Their Count/Rarity,
        // InSquare and HeightRange modifiers have already selected this exact point. Medium/Large use
        // the deterministic region plan required by their spacing and pre-generation locate support.
        RandomSource planRandom = random;
        if (tier >= TIER_MEDIUM && !config.forcedAlexUranium()) {
            String plannedMaterialKey = OreUnifier.materialKeyFor(targets.getFirst().state.getBlock());
            origin = plannedCenterForChunk(level.getLevel(), chunkX, chunkZ, tier, plannedMaterialKey);
            planRandom = depositPlanSeed(level.getLevel(), origin, tier, plannedOreId);
        }

        float sizeMultiplier = Mth.clamp(config.sizeMultiplier(), 0.1F, 6.0F);
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
        int requiredMinBlocks = config.forcedAlexUranium() ? 1 : minBlocks;
        ShapeDimensions shape = shapeForBlockCount(targetBlocks, tierProfile.tierPosition(), planRandom);
        int radiusX = shape.radiusX();
        int radiusZ = shape.radiusZ();
        int maxDepth = shape.depth();

        // A placement modifier may select air, soil or another non-stone block. In that case retain the
        // attempt and move its center to the nearest writable #c:stones host instead of discarding it.
        // The bounded nearest-first search keeps pathological dimensions without tagged stones from
        // turning one failed attempt into an unbounded worldgen scan.
        BlockPos taggedStoneOrigin = findNearbyTaggedStone(level, origin, radiusX, radiusZ, maxDepth);
        if (taggedStoneOrigin == null) {
            return rejectAttempt(claim, "no_c_stone_near_spawn");
        }
        origin = taggedStoneOrigin;

        if (tier >= TIER_MEDIUM && !config.forcedAlexUranium() && !LargeDepositSpatialIndex.get(level.getLevel())
                .canPossiblyFit(origin, tier, 0.0D)) {
            CLAIMED_DEPOSIT_ATTEMPTS.add(claim.claimKey());
            trimIfOversized(CLAIMED_DEPOSIT_ATTEMPTS);
            return rejectAttempt(claim, "large_spacing_precheck");
        }

        // Apart from the nearest tagged-stone correction above, do not search for or move a deposit to a
        // cave wall. Cave-wall relocation made the final coordinate depend on exposure scans and prevented
        // /locate from predicting the plan before the deposit itself was generated.
        // Do not expand after inspecting terrain.  Whether an expansion was needed used to make the
        // eventual footprint depend on blocks that did not exist when the plan was calculated.
        List<DepositPosition> positions = collectDepositPositions(
                level, planRandom, origin, targets, radiusX, radiusZ, maxDepth, targetBlocks
        );
        if (positions.size() < requiredMinBlocks) {
            return rejectAttempt(claim, "insufficient_shape_blocks");
        }
        positions = limitByChunkCompatibilityBudget(level, positions);
        // Compatibility trimming must not leave behind a partial vein below the configured tier minimum.
        // The upper bound is repeated here as a final invariant immediately before placement.
        if (positions.size() < requiredMinBlocks) {
            return rejectAttempt(claim, "insufficient_shape_blocks");
        }
        if (positions.size() > targetBlocks) {
            positions = positions.subList(0, targetBlocks);
        }
        // Treat a connected component as one vein from the player's point of view. Without this guard,
        // independently generated TINY/SMALL deposits could touch and look like a single deposit whose
        // block count exceeded its tier range.
        if (!config.forcedAlexUranium() && touchesExistingDeposit(level, positions)) {
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

        float richnessMultiplier = Mth.clamp(config.richnessMultiplier(), 0.1F, 6.0F);
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
        boolean sharedAttemptClaimed = false;
        boolean materialSlotClaimed = false;
        if (claim.requiresSharedClaim()) {
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
                CLAIMED_DEPOSIT_ATTEMPTS.remove(claim.claimKey());
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
        if (tier >= TIER_MEDIUM && !config.forcedAlexUranium()) {
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
                if (sharedAttemptClaimed) {
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
        for (int index = 0; index < positions.size(); index++) {
            DepositPosition depositPosition = positions.get(index);
            BlockPos pos = depositPosition.pos();
            OreConfiguration.TargetBlockState target = depositPosition.target();

            int amount = amounts[index];
            BlockState localStone = level.getBlockState(pos);
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
            level.setBlock(pos, depositState, Block.UPDATE_NONE);
            generatedDeposits.add(new OreDepositData.GeneratedDeposit(
                    pos, baseIndex, oreIndex, amount, tier
            ));
        }

        OreDepositData.setGeneratedBatch(level, generatedDeposits);

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
            FactoryExpansionMod.LOGGER.trace(
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
        return state.is(OreDepositStonePalette.STONES) && state.getFluidState().isEmpty();
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
        if (isUsableTaggedStone(level, origin)) {
            return origin;
        }

        int shapeRadius = Math.max(radiusX, radiusZ);
        int horizontalRadius = Mth.clamp(
                shapeRadius * 2 + 8,
                8,
                MAX_TAGGED_STONE_RELOCATION_RADIUS
        );
        int verticalRadius = Math.max(MAX_TAGGED_STONE_VERTICAL_RELOCATION_RADIUS, maxDepth + 3);
        int maximumDistance = horizontalRadius + verticalRadius;
        GenerationWriteBounds writeBounds = generationWriteBounds(level);
        int checks = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        // Enumerating exact Manhattan shells guarantees that the first accepted block is one of the
        // closest compatible hosts without allocating and sorting every position in the search box.
        for (int distance = 1; distance <= maximumDistance; distance++) {
            int minDy = -Math.min(verticalRadius, distance);
            int maxDy = Math.min(verticalRadius, distance);
            for (int dy = minDy; dy <= maxDy; dy++) {
                int horizontalDistance = distance - Math.abs(dy);
                if (horizontalDistance > horizontalRadius) {
                    continue;
                }

                int minDx = -Math.min(horizontalRadius, horizontalDistance);
                int maxDx = Math.min(horizontalRadius, horizontalDistance);
                for (int dx = minDx; dx <= maxDx; dx++) {
                    int dz = horizontalDistance - Math.abs(dx);
                    if (dz > horizontalRadius) {
                        continue;
                    }

                    mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (writeBounds != null && !writeBounds.contains(mutable.getX(), mutable.getZ())) {
                        // Do not spend ensureCanWrite/getBlockState calls outside the FEATURES write window.
                    } else if (checks++ >= MAX_TAGGED_STONE_SEARCH_CHECKS) {
                        return null;
                    } else if (isUsableTaggedStone(level, mutable, writeBounds != null)) {
                        return mutable.immutable();
                    }
                    if (dz != 0) {
                        mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() - dz);
                        if (writeBounds != null && !writeBounds.contains(mutable.getX(), mutable.getZ())) {
                            continue;
                        }
                        if (checks++ >= MAX_TAGGED_STONE_SEARCH_CHECKS) {
                            return null;
                        }
                        if (isUsableTaggedStone(level, mutable, writeBounds != null)) {
                            return mutable.immutable();
                        }
                    }
                }
            }
        }
        return null;
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
            RandomSource random
    ) {
        // Every physical deposit block may replace only a #c:stones host. The original target predicate
        // remains relevant solely for selecting variants such as stone/deepslate ore states.
        if (!currentState.is(OreDepositStonePalette.STONES)) {
            return null;
        }

        OreConfiguration.TargetBlockState stoneFallback = null;
        for (OreConfiguration.TargetBlockState target : oreTargets) {
            if (target.target.test(currentState, random)) {
                return target;
            }

            if (stoneFallback == null) {
                stoneFallback = target;
            }
        }

        return stoneFallback;
    }

    private static List<DepositPosition> collectDepositPositions(
            WorldGenLevel level,
            RandomSource random,
            BlockPos origin,
            List<OreConfiguration.TargetBlockState> targets,
            int radiusX,
            int radiusZ,
            int maxDepth,
            int maxBlocks
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
                    OreConfiguration.TargetBlockState target = matchingTarget(oreTargets, currentState, random);
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

    private static ShapeDimensions shapeForBlockCount(int blockCount, double tierPosition, RandomSource random) {
        int depth = Mth.clamp(
                1 + (int) Math.round(Math.pow(tierPosition, 1.3D) * (MAX_UNDERGROUND_DEPTH - 1)),
                1,
                MAX_UNDERGROUND_DEPTH
        );
        double baseRadius = Math.sqrt(Math.max(1, blockCount) / (Math.PI * depth * 0.55D)) + 1.0D;
        double anisotropy = 0.85D + random.nextDouble() * 0.30D;
        int radiusX = Mth.clamp(Mth.ceil(baseRadius * anisotropy), 1, MAX_UNDERGROUND_RADIUS);
        int radiusZ = Mth.clamp(Mth.ceil(baseRadius / anisotropy), 1, MAX_UNDERGROUND_RADIUS);
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
            boolean forcedAlexUranium,
            float sourceProbability,
            float frequencyMultiplier,
            RandomSource random
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

        if (forcedAlexUranium) {
            // Forced Alex's Caves uranium is always a LARGE deposit.
            int resolvedTier = resolvedTierForChunk(level.getSeed(), chunkX, chunkZ, tier, true);
            return findForcedUraniumAttempt(level, chunkX, chunkZ, resolvedTier, targets);
        }

        // The predictor and the actual feature must resolve the exact same biome. In vertically layered
        // worlds, using the placement Y here while /locate samples Y=64 made TINY/SMALL candidates disagree
        // with their prediction and produced false "not found" results.
        Holder<Biome> biome = level.getBiome(chunkSamplePos(chunkX, chunkZ, chunkSampleY(level)));
        ResourceLocation biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);

        TierShares shares = computeTierShares();
        logTierTableOnce(level, biomeId, shares);

        if (tier >= TIER_MEDIUM && !forcedAlexUranium
                && (!isLargeDepositRegionAnchor(chunkX, chunkZ) || largeTierForRegion(level, chunkX, chunkZ, shares) != tier)) {
            return null;
        }

        if (tier < TIER_MEDIUM) {
            // RandomFeatureConfiguration is flattened while biome modifiers are rebuilt. Preserve its
            // source branch probability here; the ordinary placed-feature modifiers already supplied
            // the count, rarity, in-square position and height for this call.
            if (random.nextFloat() >= Mth.clamp(sourceProbability, 0.0F, 1.0F)) {
                return null;
            }
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
            double retentionGate = tier < plan.gateProbabilities().length
                    ? plan.gateProbabilities()[tier]
                    : 0.0D;
            // The original placed-feature stream determines how many small-deposit attempts exist. The
            // adaptive rarity formula selects a material inside a retained attempt; it never reduces the
            // shared attempt budget a second time.
            // Frequency is deliberately applied here instead of materializing N duplicate placed
            // features during biome setup. This preserves the usual multiplier behaviour until the
            // source stream is saturated and, together with claimSmallAttempt, makes every maximum
            // slider preset have a fixed upper bound on costly cave searches per chunk.
            // `frequencyMultiplier` (the method parameter) is baked into this feature's Configuration once,
            // when biome modifiers are applied - it never re-reads OreOverrides afterward, so changing the
            // override at runtime silently had no effect on TINY/SMALL until the next full registry reload.
            // MEDIUM/LARGE never had this problem: their weight table already re-resolves the override live
            // on every attempt (see OreGenerationWeights#frequencyMultiplier / #currentToken). Resolving it
            // live here too, with the exact same override-then-preset fallback chain, keeps every tier
            // consistent and makes frequency changes apply immediately without a restart.
            float liveFrequencyMultiplier = liveFrequencyMultiplier(targets.getFirst().state.getBlock());
            double gateProbability = DepositTierMath.smallTierGateProbability(
                    1.0D, retentionGate, liveFrequencyMultiplier
            );
            // Consume the historical draw so accepted attempts retain the same later random stream.
            random.nextDouble();
            if (!passesSmallTierRetentionGate(
                    level, chunkX, chunkZ, tier, candidateMaterialKey, gateProbability
            )) {
                return null;
            }
            // TINY/SMALL retain the source ore's own placement stream.  Selecting another material
            // from the global weighted table here made Frequency apply twice (at this gate and again in
            // the lottery), and allowed one ore's slider to change every other ore's small deposits.
            // The source stream already encodes the material's vanilla/modded rarity, so it is the only
            // correct owner of this attempt.
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

        // The vertical biome-column scan is expensive. Run it only for the material that won this slot;
        // losing source features can return after the cheap deterministic lottery above.
        if (tier >= TIER_MEDIUM) {
            boolean toxicCavesColumn = hasAlexToxicCavesBiome(level, chunkX, chunkZ);
            if (toxicCavesColumn) {
            // Toxic-caves rare-deposit slots are reserved entirely for the forced-uranium special case.
                return null;
            }
            if (isForcedAlexUraniumTarget(targets)) {
            // Alex's Caves uranium only spawns as LARGE through the dedicated toxic-caves path.
                return null;
            }
        }

        return new AttemptClaim(computeAttemptClaimKey(level, chunkX, chunkZ, tier, attemptIndex), true, null, null);
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

    /** The forced feature bypasses every frequency roll. With spacing one, every Toxic Caves chunk qualifies. */
    private static AttemptClaim findForcedUraniumAttempt(ServerLevel level, int chunkX, int chunkZ, int tier, List<OreConfiguration.TargetBlockState> targets) {
        if (tier != TIER_LARGE || !isForcedAlexUraniumTarget(targets)) {
            return null;
        }
        if (!isForcedUraniumRegionAnchor(level, chunkX, chunkZ)) {
            return null;
        }
        long claimKey = computeAttemptClaimKey(level, chunkX, chunkZ, TIER_LARGE, -1);
        if (CLAIMED_DEPOSIT_ATTEMPTS.contains(claimKey)) {
            return null;
        }
        return new AttemptClaim(claimKey, true, null, null);
    }

    private static boolean isForcedUraniumRegionAnchor(ServerLevel level, int chunkX, int chunkZ) {
        int spacing = FORCED_URANIUM_REGION_SPACING_CHUNKS;
        int regionX = Math.floorDiv(chunkX, spacing);
        int regionZ = Math.floorDiv(chunkZ, spacing);
        long anchor = forcedUraniumRegionAnchorChunkKey(level, regionX, regionZ);
        return chunkX == ChunkPos.getX(anchor) && chunkZ == ChunkPos.getZ(anchor);
    }

    /**
     * The single anchor chunk (as a packed {@link ChunkPos#asLong}) that
     * {@link #FORCED_URANIUM_REGION_SPACING_CHUNKS}-chunk region {@code (regionX, regionZ)} resolves to.
     * Exposed so /locate can jump straight from region to region instead of scanning all
     * {@code spacing * spacing} chunks in every region to find the one that happens to be the anchor.
     */
    public static long forcedUraniumRegionAnchorChunkKey(ServerLevel level, int regionX, int regionZ) {
        int spacing = FORCED_URANIUM_REGION_SPACING_CHUNKS;
        RandomSource regionRandom = RandomSource.create(level.getSeed() ^ 0x5552414E49554D4CL ^ regionX * ANCHOR_SEED_X ^ regionZ * ANCHOR_SEED_Z);
        int anchorChunkX = regionX * spacing + regionRandom.nextInt(spacing);
        int anchorChunkZ = regionZ * spacing + regionRandom.nextInt(spacing);
        return ChunkPos.asLong(anchorChunkX, anchorChunkZ);
    }

    public static int forcedUraniumRegionSpacingChunks() {
        return FORCED_URANIUM_REGION_SPACING_CHUNKS;
    }

    public static boolean usesForcedAlexUraniumAnchorGrid(String materialKey, int tier) {
        return usesForcedAlexUraniumAnchors(materialKey, tier);
    }

    // WorldGenLevel, not ServerLevel: this runs on a worldgen worker while the chunk is still in progress.
    // Traverse every biome quart from the safe floor to the dimension ceiling, then choose real tagged
    // stone inside the first deterministic Toxic Caves cell. There is deliberately no configured Y cap.
    private static BlockPos forcedAlexUraniumOrigin(WorldGenLevel level, BlockPos placementOrigin) {
        int chunkX = placementOrigin.getX() >> 4;
        int chunkZ = placementOrigin.getZ() >> 4;
        int minimumOriginY = ForcedUraniumPlacementMath.minimumOriginY(
                level.getMinBuildHeight(), BEDROCK_CLEARANCE, MAX_UNDERGROUND_DEPTH
        );
        int maximumOriginY = ForcedUraniumPlacementMath.maximumOriginY(level.getMaxBuildHeight());
        int quartCount = ForcedUraniumPlacementMath.quartCount(minimumOriginY, maximumOriginY);
        if (quartCount == 0) {
            return null;
        }

        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        int minimumQuartY = Math.floorDiv(minimumOriginY, 4);
        long seed = level.getLevel().getSeed() ^ FORCED_URANIUM_HEIGHT_SALT
                ^ ((long) chunkX * ANCHOR_SEED_X) ^ ((long) chunkZ * ANCHOR_SEED_Z);
        RandomSource traversal = RandomSource.create(seed);
        int firstQuartY = traversal.nextInt(quartCount);
        int firstHorizontalQuart = traversal.nextInt(16);
        int firstBlockInQuart = traversal.nextInt(64);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        for (int quartYOffset = 0; quartYOffset < quartCount; quartYOffset++) {
            int quartYIndex = ForcedUraniumPlacementMath.cyclicIndex(firstQuartY, quartYOffset, quartCount);
            int quartY = minimumQuartY + quartYIndex;
            int quartBlockY = quartY << 2;
            int sampleY = Mth.clamp(quartBlockY + 2, minimumOriginY, maximumOriginY);

            for (int horizontalOffset = 0; horizontalOffset < 16; horizontalOffset++) {
                int horizontalQuart = (firstHorizontalQuart + horizontalOffset) & 15;
                int quartX = horizontalQuart & 3;
                int quartZ = horizontalQuart >> 2;
                mutable.set(startX + (quartX << 2) + 2, sampleY, startZ + (quartZ << 2) + 2);
                if (!isAlexToxicCavesBiome(level.getBiome(mutable)
                        .unwrapKey().map(key -> key.location()).orElse(null))) {
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
                    if (isUsableTaggedStone(level, mutable)) {
                        return mutable.immutable();
                    }
                }
            }
        }

        return null;
    }

    private static boolean isForcedAlexUraniumTarget(List<OreConfiguration.TargetBlockState> targets) {
        if (!ModList.get().isLoaded("alexscaves") || targets.isEmpty()) {
            return false;
        }

        return targets.stream().anyMatch(target -> {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(target.state.getBlock());
            if (id == null) {
                return false;
            }

            String path = id.getPath();
            return path.contains("uranium") || path.contains("uraninite") || path.contains("uran");
        });
    }

    /**
     * A forced uranium anchor is claimed once on its own grid. The shape is then selected from its
     * immutable world seed, rather than by installing a second large feature at the same location.
     */
    static int resolvedTierForChunk(
            long worldSeed,
            int chunkX,
            int chunkZ,
            int configuredTier,
            boolean forcedAlexUranium
    ) {
        int forcedTier = forcedAlexUranium
                ? forcedAlexUraniumTier(worldSeed, chunkX, chunkZ)
                : configuredTier;
        return ForcedUraniumTierMath.resolvedTier(configuredTier, forcedAlexUranium, forcedTier);
    }

    private static int forcedAlexUraniumTier(ServerLevel level, int chunkX, int chunkZ) {
        return forcedAlexUraniumTier(level.getSeed(), chunkX, chunkZ);
    }

    private static int forcedAlexUraniumTier(long worldSeed, int chunkX, int chunkZ) {
        return TIER_LARGE;
    }

    /** Alex's Caves uranium always resolves through the dedicated forced-uranium grid as a LARGE deposit. */
    private static boolean usesForcedAlexUraniumAnchors(String materialKey, int tier) {
        return ModList.get().isLoaded("alexscaves")
                && materialKey.equals("uranium")
                && tier == TIER_LARGE;
    }

    private static boolean isAlexToxicCavesBiome(ResourceLocation biomeId) {
        if (!ModList.get().isLoaded("alexscaves") || biomeId == null || !biomeId.getNamespace().equals("alexscaves")) {
            return false;
        }

        String path = biomeId.getPath();
        return path.equals("toxic_caves") || path.contains("toxic_caves");
    }

    private static boolean hasAlexToxicCavesBiome(ServerLevel level, int chunkX, int chunkZ) {
        if (!ModList.get().isLoaded("alexscaves")) {
            return false;
        }

        long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
        synchronized (TOXIC_CAVES_CACHE) {
            Map<Long, Boolean> levelCache = TOXIC_CAVES_CACHE.get(level);
            if (levelCache != null) {
                Boolean cached = levelCache.get(chunkKey);
                if (cached != null) {
                    return cached;
                }
            }
        }

        int minimumOriginY = ForcedUraniumPlacementMath.minimumOriginY(
                level.getMinBuildHeight(), BEDROCK_CLEARANCE, MAX_UNDERGROUND_DEPTH
        );
        int maximumOriginY = ForcedUraniumPlacementMath.maximumOriginY(level.getMaxBuildHeight());
        int quartCount = ForcedUraniumPlacementMath.quartCount(minimumOriginY, maximumOriginY);
        if (quartCount == 0) {
            return false;
        }
        boolean result = false;
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        int minimumQuartY = Math.floorDiv(minimumOriginY, 4);
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();

        // Toxic Caves is three-dimensional: inspect all 4x4 horizontal biome quarts at every build height.
        for (int quartYOffset = 0; quartYOffset < quartCount && !result; quartYOffset++) {
            int sampleY = Mth.clamp(
                    ((minimumQuartY + quartYOffset) << 2) + 2,
                    minimumOriginY,
                    maximumOriginY
            );
            for (int quartX = 0; quartX < 4 && !result; quartX++) {
                for (int quartZ = 0; quartZ < 4; quartZ++) {
                    mutable.set(startX + quartX * 4 + 2, sampleY, startZ + quartZ * 4 + 2);
                    if (isAlexToxicCavesBiome(level.getBiome(mutable)
                            .unwrapKey()
                            .map(key -> key.location())
                            .orElse(null))) {
                        result = true;
                        break;
                    }
                }
            }
        }

        synchronized (TOXIC_CAVES_CACHE) {
            Map<Long, Boolean> levelCache = TOXIC_CAVES_CACHE.computeIfAbsent(level, ignored -> new HashMap<>());
            if (levelCache.size() >= MAX_TOXIC_CAVES_CACHE_ENTRIES) {
                levelCache.clear();
            }
            levelCache.put(chunkKey, result);
        }
        return result;
    }

    /**
     * Section 9 diagnostic. Logs once (until new worldgen data invalidates the ore-frequency scan) rather
     * than every chunk: the tier table (capacity/scale/weight/share/expected attempts) plus, for each tier,
     * the resulting per-material weight table via {@link OreGenerationWeights#logTierOreTable}.
     */
    private static void logTierTableOnce(ServerLevel level, ResourceLocation biomeId, TierShares shares) {
        if (loggedTierTable || !FactoryExpansionMod.LOGGER.isDebugEnabled()) {
            return;
        }
        synchronized (OreDepositFeature.class) {
            if (loggedTierTable) {
                return;
            }

            double totalAttempts = totalDepositAttempts(level, biomeId);
            FactoryExpansionMod.LOGGER.debug("Ore deposits: tier table (total_deposit_attempts_per_chunk={})",
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
                        ? "per-material retained source stream"
                        : String.format(java.util.Locale.ROOT, "%.6f",
                                totalAttempts * shares.share()[tier] * frequencyBudgetMultiplier);
                FactoryExpansionMod.LOGGER.debug(
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
        if (!FactoryExpansionMod.LOGGER.isDebugEnabled() || frequency.originalFrequency() <= 0.0D) {
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
                ? plan.expectedAttempts()[TIER_SMALL] * frequencyMultiplier
                : 0.0D;
        FactoryExpansionMod.LOGGER.debug(
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
            FactoryExpansionMod.LOGGER.warn(
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
                * materialShare;
    }

    private static boolean rejectAttempt(AttemptClaim claim, String reason) {
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
        FactoryExpansionMod.LOGGER.debug(
                "Ore deposit placement: Tier={} | Ore ID={} | Source attempts={} | Launched attempts={} | Successful deposits={} | Success rate={}% | Rejections=[{}]",
                DepositTier.byIndex(key.tier()).serializedName(), key.oreId(), stats.sourceAttempts.sum(),
                launched, successful, formatDiagnostic(successRate * 100.0D), reasons
        );
        if (launched >= 16L && successRate < 0.25D && stats.lowSuccessWarning.compareAndSet(false, true)) {
            FactoryExpansionMod.LOGGER.warn(
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
            boolean forcedAlexUranium
    ) implements FeatureConfiguration {
        public static final Codec<Configuration> CODEC = RecordCodecBuilder.create(instance -> instance.group(
                Codec.list(OreConfiguration.TargetBlockState.CODEC).fieldOf("targets").forGetter(Configuration::targets),
                Codec.INT.optionalFieldOf("size_tier", TIER_TINY).forGetter(Configuration::sizeTier),
                Codec.FLOAT.optionalFieldOf("source_probability", 1.0F).forGetter(Configuration::sourceProbability),
                Codec.FLOAT.optionalFieldOf("frequency_multiplier", 1.0F).forGetter(Configuration::frequencyMultiplier),
                Codec.FLOAT.optionalFieldOf("size_multiplier", 1.0F).forGetter(Configuration::sizeMultiplier),
                Codec.FLOAT.optionalFieldOf("richness_multiplier", 1.0F).forGetter(Configuration::richnessMultiplier),
                Codec.BOOL.optionalFieldOf("forced_alex_uranium", false).forGetter(Configuration::forcedAlexUranium)
        ).apply(instance, Configuration::new));
    }
}

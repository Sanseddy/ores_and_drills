package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.config.OreOverrides;
import dev.config.OreSettingsPresetManager;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.util.random.SimpleWeightedRandomList;
import net.minecraft.util.random.WeightedEntry;
import net.minecraft.util.valueproviders.BiasedToBottomInt;
import net.minecraft.util.valueproviders.ClampedInt;
import net.minecraft.util.valueproviders.ClampedNormalInt;
import net.minecraft.util.valueproviders.ConstantInt;
import net.minecraft.util.valueproviders.IntProvider;
import net.minecraft.util.valueproviders.UniformInt;
import net.minecraft.util.valueproviders.WeightedListInt;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.heightproviders.HeightProvider;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.DoubleSummaryStatistics;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks each material's original (vanilla/modded) worldgen frequency and turns it into weighted selection
 * chances for the deposit system. The stored frequency is the effective, tier-agnostic expected placement
 * frequency; the one adaptive tier formula is applied here, once, at selection time. Nothing here bakes in
 * a fixed value for any specific ore or tier.
 */
public final class OreGenerationWeights {
    private static final int DEFAULT_MIN_GEN_Y = -64;
    private static final int DEFAULT_MAX_GEN_Y = 320;
    private static final Set<String> OBSERVED_FEATURES = ConcurrentHashMap.newKeySet();
    private static final Set<String> OBSERVED_BIOME_FEATURES = ConcurrentHashMap.newKeySet();
    /** Keeps conservative-estimate diagnostics useful without repeating them for every biome scan. */
    private static final Set<String> CONSERVATIVE_ESTIMATE_WARNINGS = ConcurrentHashMap.newKeySet();
    private static final Map<OreSpawnDimensions.SpawnDimension, Map<String, MutableData>> DATA_BY_DIMENSION = new EnumMap<>(OreSpawnDimensions.SpawnDimension.class);
    /** Immutable, sorted views used by every placement attempt; rebuilt only while biome features are scanned. */
    private static final Map<OreSpawnDimensions.SpawnDimension, List<OreGenerationData>> DATA_SNAPSHOTS = new ConcurrentHashMap<>();
    /** Which material keys actually have an ore feature attached to a given biome — used to keep the weighted vein-slot pick scoped to ores that can really appear there, instead of competing against every ore in the whole dimension. */
    private static final Map<String, Set<String>> MATERIALS_BY_BIOME = new ConcurrentHashMap<>();
    /** Expected source attempts per material in each biome; unlike the dimension table this keeps biome variants separate. */
    private static final Map<String, Map<String, Double>> FREQUENCY_BY_BIOME = new ConcurrentHashMap<>();
    /** Original host predicates grouped by source biome/material. Locate uses these contexts to mirror the
     * source-biome-aware MEDIUM/LARGE placement without knowing anything about a particular mod or ore. */
    private static final Map<SourceContextKey, Map<String, RuleTest>> SOURCE_TARGETS = new ConcurrentHashMap<>();
    /** Classifies custom dimensions through the biome that is actually being decorated. */
    private static final Map<String, OreSpawnDimensions.SpawnDimension> DIMENSION_BY_BIOME = new ConcurrentHashMap<>();
    /** Per (dimension, biome) memoization of {@link #biomeScopedEntries}, invalidated by {@link #currentToken()}. */
    private static final Map<ScopedKey, ScopedEntries> SCOPED_CACHE = new ConcurrentHashMap<>();
    /** Per (dimension, biome, tier rarity exponent) memoization of the cumulative selection-weight table that
     * {@link #selectedMaterialForRegion} draws from — built once per chunk-decoration burst instead of once
     * per deposit-attempt slot (a chunk can ask this dozens of times across every material/tier combination). */
    private static final Map<TableKey, WeightTable> WEIGHT_TABLE_CACHE = new ConcurrentHashMap<>();
    /** Bumped whenever recorded worldgen observations change, so the caches above never serve stale data. */
    private static volatile int dataEpoch;

    private OreGenerationWeights() {
    }

    public static synchronized void clear() {
        OBSERVED_FEATURES.clear();
        OBSERVED_BIOME_FEATURES.clear();
        CONSERVATIVE_ESTIMATE_WARNINGS.clear();
        DATA_BY_DIMENSION.clear();
        DATA_SNAPSHOTS.clear();
        MATERIALS_BY_BIOME.clear();
        FREQUENCY_BY_BIOME.clear();
        SOURCE_TARGETS.clear();
        DIMENSION_BY_BIOME.clear();
        SCOPED_CACHE.clear();
        WEIGHT_TABLE_CACHE.clear();
        dataEpoch++;
    }

    /** Combines the worldgen-observation epoch with the override config's own generation counter, so cached
     * per-biome/per-tier tables invalidate whenever either the scanned data or the player's overrides change. */
    private static long currentToken() {
        long token = dataEpoch;
        token = token * 31L + OreOverrides.generation();
        return token * 31L + OreSettingsPresetManager.generation();
    }

    private record ScopedKey(OreSpawnDimensions.SpawnDimension dimension, String biomeId) {
    }

    private record ScopedEntries(long token, List<OreGenerationData> scoped) {
    }

    private record TableKey(OreSpawnDimensions.SpawnDimension dimension, String biomeId, int tier) {
    }

    private record WeightTable(long token, List<OreGenerationData> entries, double[] cumulative, double total, double baseTotal) {
    }

    public static synchronized boolean isEmpty() {
        return DATA_BY_DIMENSION.values().stream().allMatch(Map::isEmpty);
    }

    /**
     * Section 1: reads a vanilla/modded ore's original worldgen data (count placement, rarity filter, height
     * range, dimension, biome — see {@link #estimateEffectiveFrequency}) and accumulates it into that
     * material's running {@code originalFrequency}. Called once per (biome, configured feature, material)
     * combination discovered while scanning vanilla biome generation settings; multiple original generators
     * for the same unified material (e.g. stone + deepslate variants, or the same ore added by two mods) sum
     * their expected contributions instead of overwriting each other.
     */
    public static void recordObservation(
            Block ore,
            Holder<Biome> biome,
            String sourceSignature,
            ConfiguredFeature<?, ?> configured,
            List<PlacementModifier> placement,
            float chance,
            int sourceSize,
            RuleTest sourceTarget
    ) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (oreId == null) {
            return;
        }
        String materialKey = OreUnifier.materialKeyFor(ore);
        if (materialKey.isEmpty()) {
            return;
        }

        OreSpawnDimensions.SpawnDimension dimension = OreSpawnDimensions.dimensionFor(biome);
        String biomeId = biome.unwrapKey().map(key -> key.location().toString()).orElse("<direct>");
        DIMENSION_BY_BIOME.putIfAbsent(biomeId, dimension);
        if (sourceTarget != null && !"<direct>".equals(biomeId)) {
            SOURCE_TARGETS.compute(new SourceContextKey(biomeId, materialKey), (ignored, existing) -> {
                Map<String, RuleTest> targets = existing == null
                        ? new TreeMap<>()
                        : new TreeMap<>(existing);
                targets.putIfAbsent(
                        ReplaceOreFeaturesBiomeModifier.stableRuleTestKey(sourceTarget), sourceTarget
                );
                return Map.copyOf(targets);
            });
        }
        // The same placed/configured feature is attached to many biomes. Counting it once per biome inflated
        // a normal dimension-wide budget by the biome count (123k attempts/chunk in a representative pack).
        // Biome eligibility is tracked separately below, while frequency is recorded once per actual generator.
        String key = dimension + "|" + sourceSignature
                + "|" + Float.floatToIntBits(chance) + "|" + materialKey;
        MATERIALS_BY_BIOME.computeIfAbsent(biomeId, ignored -> ConcurrentHashMap.newKeySet()).add(materialKey);
        FrequencyEstimate frequencyEstimate = estimateEffectiveFrequency(placement, chance, configured);
        double originalFrequency = frequencyEstimate.effectiveFrequency();
        String biomeFeatureKey = biomeId + "|" + key;
        if (OBSERVED_BIOME_FEATURES.add(biomeFeatureKey)) {
            FREQUENCY_BY_BIOME
                    .computeIfAbsent(biomeId, ignored -> new ConcurrentHashMap<>())
                    .merge(materialKey, originalFrequency, Double::sum);
            SCOPED_CACHE.clear();
            WEIGHT_TABLE_CACHE.clear();
            dataEpoch++;
        }
        if (!OBSERVED_FEATURES.add(key)) {
            return;
        }

        int[] heightRange = heightRangeFromPlacement(placement);
        synchronized (OreGenerationWeights.class) {
            DATA_BY_DIMENSION
                    .computeIfAbsent(dimension, ignored -> new HashMap<>())
                    .computeIfAbsent(materialKey, ignored -> new MutableData(materialKey, oreId))
                    .add(frequencyEstimate, heightRange);
            DATA_SNAPSHOTS.remove(dimension);
            dataEpoch++;
        }
        OreDepositFeature.resetDebugLog();
    }

    /**
     * Section 5 + 6 step 9: is {@code candidateOreId} the material a weighted random draw of {@code randomValue}
     * selects for this tier, among the materials eligible in this dimension/biome? The adaptive rarity formula
     * is resolved inside the shared table and applied exactly once.
     */
    public static boolean isSelectedForRegion(
            ResourceLocation dimension,
            ResourceLocation biomeId,
            ResourceLocation candidateOreId,
            int tier,
            double randomValue
    ) {
        Block candidateBlock = BuiltInRegistries.BLOCK.get(candidateOreId);
        String candidateMaterial = candidateBlock == null ? "" : OreUnifier.materialKeyFor(candidateBlock);
        return isMaterialSelectedForRegion(dimension, biomeId, candidateMaterial, tier, randomValue);
    }

    /** Shared by real placement and /locate so bootstrap fail-open behavior cannot diverge. */
    public static boolean isMaterialSelectedForRegion(
            ResourceLocation dimension,
            ResourceLocation biomeId,
            String candidateMaterial,
            int tier,
            double randomValue
    ) {
        OreSpawnDimensions.SpawnDimension spawnDimension = dimensionFromLevel(dimension, biomeId);
        if (dataFor(spawnDimension).isEmpty()) {
            // Bootstrap/preview integrations may call placement before observations are available.
            // Preserve the established fail-open behavior in both real placement and /locate.
            return true;
        }
        if (candidateMaterial == null || candidateMaterial.isEmpty()) {
            return false;
        }
        String selectedMaterial = selectedMaterialForRegion(dimension, biomeId, tier, randomValue);
        return !selectedMaterial.isEmpty() && selectedMaterial.equals(candidateMaterial);
    }

    public static boolean hasRecordedData(ResourceLocation dimension, ResourceLocation biomeId) {
        return !dataFor(dimensionFromLevel(dimension, biomeId)).isEmpty();
    }

    public static String selectedMaterialForRegion(ResourceLocation dimension, ResourceLocation biomeId, int tier, double randomValue) {
        OreSpawnDimensions.SpawnDimension spawnDimension = dimensionFromLevel(dimension, biomeId);
        WeightTable table = weightTableFor(spawnDimension, biomeId, tier);
        if (table == null) {
            return "";
        }

        double selected = randomValue * table.total();
        double[] cumulative = table.cumulative();
        for (int index = 0; index < cumulative.length; index++) {
            if (selected < cumulative[index]) {
                return table.entries().get(index).materialKey();
            }
        }
        return table.entries().getLast().materialKey();
    }

    /** Builds (or reuses) the cumulative selection-weight table for one (dimension, biome, tier) combination.
     * A single chunk decoration pass calls {@link #selectedMaterialForRegion} once per attempt slot across
     * every material and every tier, so caching this avoids re-scanning every eligible ore's override on
     * each of those calls. */
    private static WeightTable weightTableFor(OreSpawnDimensions.SpawnDimension dimension, ResourceLocation biomeId, int tier) {
        String biomeKey = biomeId == null ? "<direct>" : biomeId.toString();
        List<OreGenerationData> entries = biomeScopedEntries(dimension, biomeId);
        if (entries.isEmpty()) {
            return null;
        }

        long token = currentToken();
        TableKey key = new TableKey(dimension, biomeKey, tier);
        WeightTable cached = WEIGHT_TABLE_CACHE.get(key);
        if (cached != null && cached.token() == token && cached.entries() == entries) {
            return cached.total() > 0.0D ? cached : null;
        }

        double maximumOriginalFrequency = maxOriginalFrequency(entries);
        WeightTable table;
        if (maximumOriginalFrequency <= 0.0D) {
            table = new WeightTable(token, entries, new double[0], 0.0D, 0.0D);
        } else {
            double[] cumulative = new double[entries.size()];
            double total = 0.0D;
            double baseTotal = 0.0D;
            for (int index = 0; index < entries.size(); index++) {
                OreGenerationData entry = entries.get(index);
                double base = tierOreWeight(entry.originalFrequency(), maximumOriginalFrequency, tier);
                baseTotal += base;
                total += base * Mth.clamp(frequencyMultiplier(entry.oreId()), 0.1F, 6.0F);
                cumulative[index] = total;
            }
            table = new WeightTable(token, entries, cumulative, total, baseTotal);
        }
        WEIGHT_TABLE_CACHE.put(key, table);
        return table.total() > 0.0D ? table : null;
    }

    /**
     * Section 2: total expected deposit-attempt budget for a dimension/biome is derived from the summed
     * original frequency of every material eligible there — not from a per-tier or per-ore fixed count.
     */
    public static double totalOriginalFrequency(ResourceLocation dimension, ResourceLocation biomeId) {
        List<OreGenerationData> entries = biomeScopedEntries(dimensionFromLevel(dimension, biomeId), biomeId);
        double total = 0.0D;
        for (OreGenerationData entry : entries) {
            total += entry.originalFrequency();
        }
        return total;
    }

    /**
     * Factorio-style budget compensation for the player's Frequency sliders. The selection lottery
     * normalizes weights, so a raised slider alone would only steal attempt slots from every other ore;
     * scaling the tier's attempt budget by {@code Σ(weight·multiplier) / Σ(weight)} — computed with the
     * exact same per-tier weights the lottery draws from — cancels that normalization. Net effect: each
     * ore's expected deposit count is linear in its own slider and unaffected by every other ore's slider,
     * matching how Frequency behaves in Factorio.
     */
    public static double tierFrequencyBudgetMultiplier(ResourceLocation dimension, ResourceLocation biomeId, int tier) {
        WeightTable table = weightTableFor(dimensionFromLevel(dimension, biomeId), biomeId, tier);
        if (table == null || table.baseTotal() <= 0.0D) {
            return 1.0D;
        }
        return table.total() / table.baseTotal();
    }

    /** Same eligible set {@link #selectedMaterialForRegion} draws from, exposed for the debug table (section 9). */
    public static List<OreGenerationData> eligibleEntries(ResourceLocation dimension, ResourceLocation biomeId) {
        return biomeScopedEntries(dimensionFromLevel(dimension, biomeId), biomeId);
    }

    /** All automatically observed biome/host contexts in which this material owns an original ore feature. */
    public static List<SourceContext> sourceContexts(ResourceLocation dimension, String materialKey) {
        if (materialKey == null || materialKey.isEmpty()) {
            return List.of();
        }
        List<SourceContext> contexts = new ArrayList<>();
        for (Map.Entry<SourceContextKey, Map<String, RuleTest>> entry : SOURCE_TARGETS.entrySet()) {
            SourceContextKey key = entry.getKey();
            if (!materialKey.equals(key.materialKey())) {
                continue;
            }
            ResourceLocation biomeId = ResourceLocation.tryParse(key.biomeId());
            if (biomeId == null
                    || dimensionFromLevel(dimension, biomeId) != DIMENSION_BY_BIOME.get(key.biomeId())) {
                continue;
            }
            List<RuleTest> targets = entry.getValue().entrySet().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .map(Map.Entry::getValue)
                    .toList();
            contexts.add(new SourceContext(biomeId, targets));
        }
        contexts.sort(Comparator.comparing(context -> context.biomeId().toString()));
        return List.copyOf(contexts);
    }

    public static MaterialFrequency materialFrequency(
            ResourceLocation dimension,
            ResourceLocation biomeId,
            ResourceLocation oreId
    ) {
        Block block = BuiltInRegistries.BLOCK.get(oreId);
        String materialKey = block == null ? "" : OreUnifier.materialKeyFor(block);
        List<OreGenerationData> entries = biomeScopedEntries(dimensionFromLevel(dimension, biomeId), biomeId);
        double maximum = maxOriginalFrequency(entries);
        for (OreGenerationData entry : entries) {
            if (entry.materialKey().equals(materialKey)) {
                return new MaterialFrequency(entry.originalFrequency(), maximum, entry.materialKey(), entry.oreId());
            }
        }
        return new MaterialFrequency(0.0D, maximum, materialKey, oreId);
    }

    /** Resolves the canonical block id recorded for a material in the current biome-scoped pool. */
    public static ResourceLocation oreIdForMaterial(
            ResourceLocation dimension,
            ResourceLocation biomeId,
            String materialKey
    ) {
        if (materialKey == null || materialKey.isEmpty()) {
            return null;
        }
        for (OreGenerationData entry : biomeScopedEntries(dimensionFromLevel(dimension, biomeId), biomeId)) {
            if (materialKey.equals(entry.materialKey())) {
                return entry.oreId();
            }
        }
        return null;
    }

    /**
     * Stable vertical interval for a material in one biome.  Deposit planning uses this instead of the
     * surrounding placed feature's random height stream, so /locate and worldgen can agree on a center
     * before any deposit blocks are written.
     */
    public static int[] heightRangeForMaterial(
            ResourceLocation dimension,
            ResourceLocation biomeId,
            String materialKey
    ) {
        for (OreGenerationData entry : biomeScopedEntries(dimensionFromLevel(dimension, biomeId), biomeId)) {
            if (materialKey.equals(entry.materialKey())) {
                return new int[] {entry.minY(), entry.maxY()};
            }
        }
        return null;
    }

    public static double selectionShare(
            ResourceLocation dimension,
            ResourceLocation biomeId,
            ResourceLocation oreId,
            int tier
    ) {
        WeightTable table = weightTableFor(dimensionFromLevel(dimension, biomeId), biomeId, tier);
        if (table == null || table.total() <= 0.0D) {
            return 0.0D;
        }
        Block block = BuiltInRegistries.BLOCK.get(oreId);
        String materialKey = block == null ? "" : OreUnifier.materialKeyFor(block);
        double previous = 0.0D;
        for (int index = 0; index < table.entries().size(); index++) {
            double cumulative = table.cumulative()[index];
            if (table.entries().get(index).materialKey().equals(materialKey)) {
                return Math.max(0.0D, cumulative - previous) / table.total();
            }
            previous = cumulative;
        }
        return 0.0D;
    }

    /**
     * Applies the one adaptive material-weight formula and caps it at the most common material's effective
     * frequency, so a rare ore cannot outrank that material. The result is never reshaped or multiplied again.
     */
    public static double tierOreWeight(double originalFrequency, double maximumOriginalFrequency, int tier) {
        return DepositTierMath.adaptiveTierOreWeight(
                DepositTier.byIndex(tier), originalFrequency, maximumOriginalFrequency, rarityParameters()
        ).weight();
    }

    public static double maxOriginalFrequency(List<OreGenerationData> entries) {
        return entries.stream().mapToDouble(OreGenerationData::originalFrequency).max().orElse(0.0D);
    }

    /**
     * Restricts a dimension-wide weight table down to the materials that actually have an ore feature
     * attached to this specific biome, so a deposit slot only ever competes among ores that can really
     * appear there. Memoized per (dimension, biome) since a chunk decoration pass re-asks this once per
     * material/tier combination it evaluates.
     */
    private static List<OreGenerationData> biomeScopedEntries(OreSpawnDimensions.SpawnDimension dimension, ResourceLocation biomeId) {
        List<OreGenerationData> entries = dataFor(dimension);
        if (entries.isEmpty()) {
            return entries;
        }

        String biomeKey = biomeId == null ? "<direct>" : biomeId.toString();
        long token = currentToken();
        ScopedKey key = new ScopedKey(dimension, biomeKey);
        ScopedEntries cached = SCOPED_CACHE.get(key);
        if (cached != null && cached.token() == token) {
            return cached.scoped();
        }

        Set<String> allowedMaterials = MATERIALS_BY_BIOME.get(biomeKey);
        Map<String, Double> biomeFrequencies = FREQUENCY_BY_BIOME.get(biomeKey);
        List<OreGenerationData> scoped;
        if (allowedMaterials == null || allowedMaterials.isEmpty()) {
            scoped = List.of();
        } else {
            List<OreGenerationData> result = new ArrayList<>();
            for (OreGenerationData entry : entries) {
                if (allowedMaterials.contains(entry.materialKey())) {
                    double frequency = biomeFrequencies == null
                            ? entry.originalFrequency()
                            : biomeFrequencies.getOrDefault(entry.materialKey(), 0.0D);
                    if (frequency > 0.0D) {
                        result.add(new OreGenerationData(
                            entry.materialKey(), entry.oreId(), frequency, entry.minY(), entry.maxY(),
                            entry.expectedAttempts(), entry.placementSuccessProbability(), entry.heightAvailability(),
                            entry.biomeAvailability(), entry.targetBlockAvailability(), entry.exposureSurvivalProbability()
                        ));
                    }
                }
            }
            scoped = List.copyOf(result);
        }
        SCOPED_CACHE.put(key, new ScopedEntries(token, scoped));
        return scoped;
    }

    /**
     * The tier-derived weight ({@link #tierOreWeight}), then the player's own opt-in per-ore override
     * multiplier (the current world's level.dat settings / active preset) is applied on top — a deliberate, separate,
     * player-driven customization, not part of the automatic rarity curve, so it isn't "reapplying" anything.
     */
    private static double selectionWeight(OreGenerationData entry, double maximumOriginalFrequency, int tier) {
        double base = tierOreWeight(entry.originalFrequency(), maximumOriginalFrequency, tier);
        return base * Mth.clamp(frequencyMultiplier(entry.oreId()), 0.1F, 6.0F);
    }

    private static float frequencyMultiplier(ResourceLocation oreId) {
        Block block = BuiltInRegistries.BLOCK.get(oreId);
        if (block == null) {
            return 1.0F;
        }

        OreOverrides.OreOverride override = OreOverrides.lookupConfigured(block)
                .orElseGet(() -> OreSettingsPresetManager.resolve(block).multipliers());
        return override.frequency();
    }

    static DepositTierMath.RarityParameters rarityParameters() {
        return new DepositTierMath.RarityParameters(
                OreDepositConfig.SMALL_DEPOSIT_RARITY_PENALTY,
                OreDepositConfig.VERY_RARE_ORE_PENALTY,
                OreDepositConfig.VERY_RARE_ORE_PENALTY_CURVE,
                OreDepositConfig.SMALL_DEPOSIT_PENALTY_CURVE,
                OreDepositConfig.LARGE_DEPOSIT_RARE_ORE_BOOST,
                OreDepositConfig.MIN_ORE_SELECTION_WEIGHT
        );
    }

    /** Raw, tier-agnostic data only — {@link #originalFrequency()} is the sole input the rarity curve reads. */
    public static List<OreGenerationData> dataFor(OreSpawnDimensions.SpawnDimension dimension) {
        List<OreGenerationData> snapshot = DATA_SNAPSHOTS.get(dimension);
        if (snapshot != null) {
            return snapshot;
        }

        synchronized (OreGenerationWeights.class) {
            snapshot = DATA_SNAPSHOTS.get(dimension);
            if (snapshot != null) {
                return snapshot;
            }

            Map<String, MutableData> data = DATA_BY_DIMENSION.get(dimension);
            if (data == null || data.isEmpty()) {
                snapshot = List.of();
            } else {
                List<OreGenerationData> result = new ArrayList<>(data.size());
                for (MutableData entry : data.values()) {
                    result.add(new OreGenerationData(
                            entry.materialKey(),
                            entry.oreId(),
                            entry.originalFrequency(), entry.averageMinY(), entry.averageMaxY(),
                            entry.averageExpectedAttempts(), entry.averagePlacementSuccessProbability(),
                            entry.averageHeightAvailability(), entry.averageBiomeAvailability(),
                            entry.averageTargetBlockAvailability(), entry.averageExposureSurvivalProbability()
                    ));
                }
                result.sort(Comparator.comparing(OreGenerationData::materialKey));
                snapshot = List.copyOf(result);
            }
            DATA_SNAPSHOTS.put(dimension, snapshot);
            return snapshot;
        }
    }

    /**
     * Section 1: for {@code CountPlacement}/{@code CountOnEveryLayerPlacement} uses the placement's
     * {@link IntProvider} expected value; for {@code NoiseBasedCount}/{@code NoiseThresholdCount} averages
     * its numeric fields; for {@code RarityFilter} divides by the "1 in N" denominator
     * ({@code expectedAttempts = 1/N}). {@code HeightRangePlacement} chooses the Y of an existing attempt,
     * so it is retained as eligibility metadata instead of reducing the attempt count. Ore name is never
     * consulted.
     */
    private static FrequencyEstimate estimateEffectiveFrequency(
            List<PlacementModifier> placement,
            float chance,
            ConfiguredFeature<?, ?> configured
    ) {
        double attempts = originalAttemptFrequency(placement, chance);
        double placementSuccessProbability = Mth.clamp(chance, 0.0F, 1.0F);

        // Height ranges and replacement predicates describe where an already-created attempt may land;
        // they do not create more independent attempts. Keep them as diagnostics, but never let a tall
        // biome, a broad #c:stones tag, or a configuration with several replacement targets change the
        // material's automatically detected rarity.
        int[] heightRange = heightRangeFromPlacement(placement);
        HeightProvider heightProvider = heightProviderFromPlacement(placement);
        double heightAvailability = heightRange == null
                ? 1.0D
                : Mth.clamp((heightRange[1] - heightRange[0] + 1) / (double)(DEFAULT_MAX_GEN_Y - DEFAULT_MIN_GEN_Y + 1),
                        0.001D, 1.0D) * heightDistributionShapeFactor(heightProvider);
        double biomeAvailability = 1.0D; // The actual selection pool is biome-scoped.
        double targetBlockAvailability = targetBlockAvailability(configured);
        double exposureSurvivalProbability = configured.config() instanceof OreConfiguration ore
                ? Mth.clamp(1.0D - ore.discardChanceOnAirExposure, 0.0D, 1.0D)
                : 1.0D;
        return new FrequencyEstimate(
                Math.max(0.000001D, attempts),
                placementSuccessProbability <= 0.0D ? 0.0D : attempts / placementSuccessProbability,
                placementSuccessProbability, heightAvailability, biomeAvailability,
                targetBlockAvailability, exposureSurvivalProbability
        );
    }

    /** Expected independent source attempts; spatial filters intentionally do not multiply this value. */
    static double originalAttemptFrequency(List<PlacementModifier> placement, float chance) {
        double attempts = 1.0D;
        for (PlacementModifier modifier : placement) {
            PlacementModifierType<?> type = modifier.type();
            if (type == PlacementModifierType.COUNT || type == PlacementModifierType.COUNT_ON_EVERY_LAYER) {
                attempts *= averageCount(modifier, 1.0D);
            } else if (type == PlacementModifierType.NOISE_BASED_COUNT || type == PlacementModifierType.NOISE_THRESHOLD_COUNT) {
                attempts *= averageNumericFields(modifier, 1.0D);
            } else if (type == PlacementModifierType.RARITY_FILTER) {
                attempts /= Math.max(1.0D, firstIntField(modifier, 1));
            }
        }
        return OreSourceFrequencyMath.originalFrequency(attempts, chance);
    }

    /**
     * Height providers can share the same bounds but concentrate rolls differently. This conservative
     * effective-support factor keeps triangular and bottom-biased providers from being treated as uniform.
     */
    private static double heightDistributionShapeFactor(HeightProvider heightProvider) {
        if (heightProvider == null) {
            return 1.0D;
        }
        String providerType = heightProvider.getClass().getSimpleName();
        return switch (providerType) {
            case "UniformHeight", "ConstantHeight" -> 1.0D;
            case "TrapezoidHeight" -> 0.75D;
            case "BiasedToBottomHeight" -> 0.70D;
            case "VeryBiasedToBottomHeight" -> 0.55D;
            default -> {
                warnConservativeEstimate("height-provider:" + providerType,
                        "unknown height-provider shape '" + providerType + "'; using neutral support factor");
                yield 1.0D;
            }
        };
    }

    /**
     * Vanilla exposes replacement predicates but not a terrain-wide substrate probability. Use a conservative
     * independent-target estimate; an opaque/missing configuration is reported and receives the same fallback.
     */
    private static double targetBlockAvailability(ConfiguredFeature<?, ?> configured) {
        if (!(configured.config() instanceof OreConfiguration ore)) {
            warnConservativeEstimate("target:" + configured.config().getClass().getName(),
                    "non-ore configured feature has no readable target-block predicate; using 0.5");
            return 0.5D;
        }
        List<?> targets = reflectedFieldValue(ore, List.class);
        if (targets == null || targets.isEmpty()) {
            warnConservativeEstimate("target:missing",
                    "ore target-block predicates could not be read; using 0.5");
            return 0.5D;
        }
        // Each configured replacement target is a valid geological path. Without chunk sampling, 0.5 per
        // independent path is the documented conservative estimate rather than pretending availability is 1.
        return Mth.clamp(1.0D - Math.pow(0.5D, targets.size()), 0.0D, 1.0D);
    }

    private static void warnConservativeEstimate(String key, String detail) {
        if (CONSERVATIVE_ESTIMATE_WARNINGS.add(key)) {
            OresAndDrillsMod.LOGGER.debug("Ore deposits: conservative effective-frequency estimate: {}", detail);
        }
    }

    /** Extracts {@code [minY, maxY]} from a {@code HEIGHT_RANGE} placement modifier's {@link HeightProvider}, or {@code null} if it can't be reflected out. */
    private static int[] heightRangeBounds(PlacementModifier modifier) {
        HeightProvider heightProvider = reflectedFieldValue(modifier, HeightProvider.class);
        if (heightProvider == null) {
            return null;
        }

        List<VerticalAnchor> anchors = reflectedFieldValues(heightProvider, VerticalAnchor.class);
        if (anchors.isEmpty()) {
            return null;
        }

        int minY;
        int maxY;
        if (anchors.size() == 1) {
            minY = maxY = approximateY(anchors.getFirst());
        } else {
            int first = approximateY(anchors.get(0));
            int second = approximateY(anchors.get(1));
            minY = Math.min(first, second);
            maxY = Math.max(first, second);
        }
        return new int[] {minY, maxY};
    }

    /** Used by {@link #recordObservation} to remember roughly where in the world this material actually generates, so {@code /locate} can predict a plausible Y for not-yet-generated deposits instead of an arbitrary placeholder. */
    private static int[] heightRangeFromPlacement(List<PlacementModifier> placement) {
        for (PlacementModifier modifier : placement) {
            if (modifier.type() == PlacementModifierType.HEIGHT_RANGE) {
                int[] bounds = heightRangeBounds(modifier);
                if (bounds != null) {
                    return bounds;
                }
            }
        }
        return null;
    }

    private static HeightProvider heightProviderFromPlacement(List<PlacementModifier> placement) {
        for (PlacementModifier modifier : placement) {
            if (modifier.type() == PlacementModifierType.HEIGHT_RANGE) {
                return reflectedFieldValue(modifier, HeightProvider.class);
            }
        }
        return null;
    }

    private static int approximateY(VerticalAnchor anchor) {
        if (anchor instanceof VerticalAnchor.Absolute absolute) {
            return absolute.y();
        }
        if (anchor instanceof VerticalAnchor.AboveBottom aboveBottom) {
            return DEFAULT_MIN_GEN_Y + aboveBottom.offset();
        }
        if (anchor instanceof VerticalAnchor.BelowTop belowTop) {
            return DEFAULT_MAX_GEN_Y - 1 - belowTop.offset();
        }
        return DEFAULT_MIN_GEN_Y;
    }

    private static double averageCount(Object owner, double fallback) {
        IntProvider provider = reflectedFieldValue(owner, IntProvider.class);
        if (provider == null) {
            return fallback;
        }
        return Math.max(0.0D, expectedIntProviderValue(provider));
    }

    /** Mathematical expectation for every vanilla IntProvider family used by CountPlacement. */
    private static double expectedIntProviderValue(IntProvider provider) {
        if (provider instanceof ConstantInt constant) {
            return constant.getValue();
        }
        if (provider instanceof UniformInt) {
            return (provider.getMinValue() + provider.getMaxValue()) / 2.0D;
        }
        if (provider instanceof BiasedToBottomInt) {
            return provider.getMinValue() + (provider.getMaxValue() - provider.getMinValue()) / 4.0D;
        }
        if (provider instanceof ClampedNormalInt) {
            List<Number> fields = reflectedNumberFields(provider);
            if (fields.size() >= 4) {
                double mean = fields.get(0).doubleValue();
                double deviation = Math.abs(fields.get(1).doubleValue());
                double minimum = fields.get(2).doubleValue();
                double maximum = fields.get(3).doubleValue();
                return expectedClampedNormal(mean, deviation, minimum, maximum);
            }
        }
        if (provider instanceof ClampedInt) {
            IntProvider source = reflectedFieldValue(provider, IntProvider.class);
            if (source != null) {
                return Mth.clamp(expectedIntProviderValue(source), provider.getMinValue(), provider.getMaxValue());
            }
        }
        if (provider instanceof WeightedListInt) {
            SimpleWeightedRandomList<?> distribution = reflectedFieldValue(provider, SimpleWeightedRandomList.class);
            if (distribution != null) {
                double weightedTotal = 0.0D;
                int totalWeight = 0;
                for (WeightedEntry.Wrapper<?> wrapper : distribution.unwrap()) {
                    if (wrapper.data() instanceof IntProvider child) {
                        int weight = wrapper.weight().asInt();
                        weightedTotal += expectedIntProviderValue(child) * weight;
                        totalWeight += weight;
                    }
                }
                if (totalWeight > 0) {
                    return weightedTotal / totalWeight;
                }
            }
        }
        return (provider.getMinValue() + provider.getMaxValue()) / 2.0D;
    }

    private static double expectedClampedNormal(double mean, double deviation, double minimum, double maximum) {
        if (deviation <= 0.0D) {
            return Mth.clamp(mean, minimum, maximum);
        }
        double alpha = (minimum - mean) / deviation;
        double beta = (maximum - mean) / deviation;
        double cdfAlpha = normalCdf(alpha);
        double cdfBeta = normalCdf(beta);
        double densityAlpha = normalDensity(alpha);
        double densityBeta = normalDensity(beta);
        return minimum * cdfAlpha
                + mean * (cdfBeta - cdfAlpha)
                - deviation * (densityBeta - densityAlpha)
                + maximum * (1.0D - cdfBeta);
    }

    private static double normalDensity(double value) {
        return Math.exp(-0.5D * value * value) / Math.sqrt(2.0D * Math.PI);
    }

    private static double normalCdf(double value) {
        // Abramowitz-Stegun 7.1.26; deterministic error < 1.5e-7 is more than sufficient for weights.
        double sign = value < 0.0D ? -1.0D : 1.0D;
        double absolute = Math.abs(value) / Math.sqrt(2.0D);
        double t = 1.0D / (1.0D + 0.3275911D * absolute);
        double erf = 1.0D - (((((1.061405429D * t - 1.453152027D) * t) + 1.421413741D) * t
                - 0.284496736D) * t + 0.254829592D) * t * Math.exp(-absolute * absolute);
        return 0.5D * (1.0D + sign * erf);
    }

    private static double averageNumericFields(Object owner, double fallback) {
        List<Number> values = reflectedNumberFields(owner);
        if (values.isEmpty()) {
            return fallback;
        }

        DoubleSummaryStatistics stats = values.stream()
                .mapToDouble(Number::doubleValue)
                .filter(value -> value > 0.0D)
                .summaryStatistics();
        return stats.getCount() == 0 ? fallback : stats.getAverage();
    }

    private static int firstIntField(Object owner, int fallback) {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (field.getType() != int.class && field.getType() != Integer.class) {
                continue;
            }

            try {
                field.setAccessible(true);
                return field.getInt(owner);
            } catch (IllegalAccessException exception) {
                return fallback;
            }
        }
        return fallback;
    }

    private static List<Number> reflectedNumberFields(Object owner) {
        List<Number> values = new ArrayList<>();
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (!Number.class.isAssignableFrom(field.getType())
                    && field.getType() != int.class
                    && field.getType() != long.class
                    && field.getType() != float.class
                    && field.getType() != double.class) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(owner);
                if (value instanceof Number number) {
                    values.add(number);
                }
            } catch (IllegalAccessException exception) {
                return List.of();
            }
        }
        return values;
    }

    private static <T> T reflectedFieldValue(Object owner, Class<T> type) {
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (!type.isAssignableFrom(field.getType())) {
                continue;
            }

            try {
                field.setAccessible(true);
                Object value = field.get(owner);
                return type.cast(value);
            } catch (IllegalAccessException exception) {
                return null;
            }
        }
        return null;
    }

    private static <T> List<T> reflectedFieldValues(Object owner, Class<T> type) {
        List<T> values = new ArrayList<>();
        for (Field field : owner.getClass().getDeclaredFields()) {
            if (!type.isAssignableFrom(field.getType())) {
                continue;
            }

            try {
                field.setAccessible(true);
                values.add(type.cast(field.get(owner)));
            } catch (IllegalAccessException exception) {
                return List.of();
            }
        }
        return values;
    }

    private static OreSpawnDimensions.SpawnDimension dimensionFromLevel(ResourceLocation dimension, ResourceLocation biomeId) {
        if (dimension.getNamespace().equals("minecraft")) {
            return switch (dimension.getPath()) {
                case "the_nether" -> OreSpawnDimensions.SpawnDimension.NETHER;
                case "the_end" -> OreSpawnDimensions.SpawnDimension.END;
                default -> OreSpawnDimensions.SpawnDimension.OVERWORLD;
            };
        }
        String biomeKey = biomeId == null ? "<direct>" : biomeId.toString();
        return DIMENSION_BY_BIOME.getOrDefault(biomeKey, OreSpawnDimensions.SpawnDimension.UNKNOWN);
    }

    /**
     * Section 9 diagnostic: per-material breakdown for one already-resolved tier (caller supplies the tier's
     * name and rarity exponent — see {@link OreDepositFeature}'s tier math). Uses the same biome-scoped pool
     * and maximum original frequency as the real selection table.
     */
    public static void logTierOreTable(
            ResourceLocation dimension,
            ResourceLocation biomeId,
            String tierName,
            int tier
    ) {
        OreSpawnDimensions.SpawnDimension spawnDimension = dimensionFromLevel(dimension, biomeId);
        List<OreGenerationData> entries = biomeScopedEntries(spawnDimension, biomeId);
        if (entries.isEmpty()) {
            return;
        }

        double maximumOriginalFrequency = maxOriginalFrequency(entries);
        if (maximumOriginalFrequency <= 0.0D) {
            return;
        }
        double totalWeight = 0.0D;
        double[] weights = new double[entries.size()];
        for (int index = 0; index < entries.size(); index++) {
            weights[index] = selectionWeight(entries.get(index), maximumOriginalFrequency, tier);
            totalWeight += weights[index];
        }

        OresAndDrillsMod.LOGGER.debug("Ore deposits: {} tier '{}' ore weight table", spawnDimension, tierName);
        for (int index = 0; index < entries.size(); index++) {
            OreGenerationData entry = entries.get(index);
            DepositTierMath.RarityWeight rarity = DepositTierMath.adaptiveTierOreWeight(
                    DepositTier.byIndex(tier), entry.originalFrequency(), maximumOriginalFrequency, rarityParameters()
            );
            double chancePercent = totalWeight > 0.0D ? weights[index] / totalWeight * 100.0D : 0.0D;
            OresAndDrillsMod.LOGGER.debug(
                    "  Ore ID: {} | Original expected attempts: {} | Placement success probability: {} | Height availability: {} | Biome availability: {} | Target-block availability: {} | Exposure survival probability: {} | Effective original frequency: {} | Maximum effective frequency: {} | Relative frequency: {} | Rare tail: {} | Base exponent: {} | Adaptive exponent: {} | Final selection weight: {} | Selection chance: {}% | Relative selection rarity: 1 in {} (biome-scoped, tier={})",
                    entry.oreId(),
                    String.format(Locale.ROOT, "%.6f", entry.expectedAttempts()),
                    String.format(Locale.ROOT, "%.6f", entry.placementSuccessProbability()),
                    String.format(Locale.ROOT, "%.6f", entry.heightAvailability()),
                    String.format(Locale.ROOT, "%.6f", entry.biomeAvailability()),
                    String.format(Locale.ROOT, "%.6f", entry.targetBlockAvailability()),
                    String.format(Locale.ROOT, "%.6f", entry.exposureSurvivalProbability()),
                    String.format(Locale.ROOT, "%.6f", entry.originalFrequency()),
                    String.format(Locale.ROOT, "%.6f", maximumOriginalFrequency),
                    String.format(Locale.ROOT, "%.6f", rarity.relativeFrequency()),
                    String.format(Locale.ROOT, "%.6f", rarity.rareTail()),
                    String.format(Locale.ROOT, "%.6f", rarity.baseExponent()),
                    String.format(Locale.ROOT, "%.6f", rarity.adaptiveExponent()),
                    String.format(Locale.ROOT, "%.6f", weights[index]),
                    String.format(Locale.ROOT, "%.4f", chancePercent),
                    String.format(Locale.ROOT, "%.2f", chancePercent <= 0.0D ? Double.POSITIVE_INFINITY : 100.0D / chancePercent),
                    tierName
            );
            if (tier < DepositTier.MEDIUM.ordinal()
                    && rarity.relativeFrequency() < 0.01D
                    && chancePercent > 5.0D) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: unusually high {} selection chance for very rare {} in {}: {}%",
                        tierName, entry.oreId(), biomeId, String.format(Locale.ROOT, "%.4f", chancePercent)
                );
            }
        }
    }

    private static final class MutableData {
        private final String materialKey;
        private final ResourceLocation oreId;
        private double originalFrequency;
        private double minYSum;
        private double maxYSum;
        private int heightSamples;
        private double weightedExpectedAttempts;
        private double weightedPlacementSuccess;
        private double weightedHeightAvailability;
        private double weightedBiomeAvailability;
        private double weightedTargetAvailability;
        private double weightedExposureSurvival;

        private MutableData(String materialKey, ResourceLocation oreId) {
            this.materialKey = materialKey;
            this.oreId = oreId;
        }

        private void add(FrequencyEstimate estimate, int[] heightRange) {
            double frequency = estimate.effectiveFrequency();
            originalFrequency += frequency;
            weightedExpectedAttempts += estimate.expectedAttempts() * frequency;
            weightedPlacementSuccess += estimate.placementSuccessProbability() * frequency;
            weightedHeightAvailability += estimate.heightAvailability() * frequency;
            weightedBiomeAvailability += estimate.biomeAvailability() * frequency;
            weightedTargetAvailability += estimate.targetBlockAvailability() * frequency;
            weightedExposureSurvival += estimate.exposureSurvivalProbability() * frequency;
            if (heightRange != null) {
                minYSum += heightRange[0];
                maxYSum += heightRange[1];
                heightSamples++;
            }
        }

        private String materialKey() {
            return materialKey;
        }

        private ResourceLocation oreId() {
            return oreId;
        }

        private double originalFrequency() {
            return originalFrequency;
        }

        private int averageMinY() {
            return heightSamples == 0 ? DEFAULT_MIN_GEN_Y : (int)Math.round(minYSum / heightSamples);
        }

        private int averageMaxY() {
            return heightSamples == 0 ? DEFAULT_MAX_GEN_Y : (int)Math.round(maxYSum / heightSamples);
        }

        private double averageExpectedAttempts() { return weightedAverage(weightedExpectedAttempts); }
        private double averagePlacementSuccessProbability() { return weightedAverage(weightedPlacementSuccess); }
        private double averageHeightAvailability() { return weightedAverage(weightedHeightAvailability); }
        private double averageBiomeAvailability() { return weightedAverage(weightedBiomeAvailability); }
        private double averageTargetBlockAvailability() { return weightedAverage(weightedTargetAvailability); }
        private double averageExposureSurvivalProbability() { return weightedAverage(weightedExposureSurvival); }
        private double weightedAverage(double weightedValue) { return originalFrequency <= 0.0D ? 1.0D : weightedValue / originalFrequency; }
    }

    public record OreGenerationData(
            String materialKey,
            ResourceLocation oreId,
            double originalFrequency,
            int minY,
            int maxY,
            double expectedAttempts,
            double placementSuccessProbability,
            double heightAvailability,
            double biomeAvailability,
            double targetBlockAvailability,
            double exposureSurvivalProbability
    ) {
    }

    private record FrequencyEstimate(
            double effectiveFrequency,
            double expectedAttempts,
            double placementSuccessProbability,
            double heightAvailability,
            double biomeAvailability,
            double targetBlockAvailability,
            double exposureSurvivalProbability
    ) {
    }

    private record SourceContextKey(String biomeId, String materialKey) {
    }

    public record SourceContext(ResourceLocation biomeId, List<RuleTest> targets) {
        public SourceContext {
            targets = List.copyOf(targets);
        }
    }

    public record MaterialFrequency(
            double originalFrequency,
            double maximumOriginalFrequency,
            String materialKey,
            ResourceLocation oreId
    ) {
    }
}

package dev.world.level.levelgen;

import com.mojang.serialization.MapCodec;
import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.config.OreGenerationMode;
import dev.config.OreOverrides;
import dev.config.OreSettingsPresetManager;
import dev.config.OreWorldDisabledOres;
import dev.registry.ModWorldgen;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.util.stream.Collectors;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.GenerationStep;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.WeightedPlacedFeature;
import net.minecraft.world.level.levelgen.feature.configurations.FeatureConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.feature.configurations.RandomFeatureConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.RarityFilter;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.neoforged.neoforge.common.world.BiomeGenerationSettingsBuilder;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

public enum ReplaceOreFeaturesBiomeModifier implements BiomeModifier {
    INSTANCE;

    private static final Set<String> LOGGED_REPLACEMENTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_REFLECTION_FALLBACKS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> LOGGED_DISABLED_ORES = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> LOGGED_PRESET_DISABLED_ORES =
            ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> LOGGED_UNREPRESENTABLE_ORES =
            ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> LOGGED_VANILLA_LIKE_FALLBACKS =
            ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_VANILLA_LIKE_SOURCES = ConcurrentHashMap.newKeySet();
    private static final Map<ResourceLocation, List<OreDepositFeature.Configuration>>
            COMPACT_CONFIGS = new ConcurrentHashMap<>();

    static List<OreDepositFeature.Configuration> compactConfigurations() {
        return COMPACT_CONFIGS.values().stream().flatMap(List::stream).toList();
    }

    static void decorateCompactDeposits(
            net.minecraft.world.level.WorldGenLevel level,
            net.minecraft.world.level.chunk.ChunkAccess chunk) {
        compactConfigurationsForChunk(level.getLevel(), chunk.getPos())
                .forEach(
                        config ->
                                OreDepositFeature.placeCompactDeposits(
                                        level, chunk.getPos(), config));
    }

    static List<OreDepositFeature.Configuration> compactConfigurationsForChunk(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.ChunkPos chunk) {
        return compactConfigurationsForChunk(
                level,
                chunk,
                CompactDepositGeometry.MAX_HORIZONTAL_REACH
                        + OreDepositFeature.MAX_TAGGED_STONE_RELOCATION_RADIUS);
    }

    static List<OreDepositFeature.Configuration> compactConfigurationsForChunk(
            net.minecraft.server.level.ServerLevel level,
            net.minecraft.world.level.ChunkPos chunk,
            int reach) {
        // Run in every chunk, including the far side of a biome boundary. Source-biome checks
        // belong to the immutable candidate, not to the biome of the chunk receiving its
        // continuation.
        Set<ResourceLocation> sources = new java.util.HashSet<>();
        for (int index = OreDepositFeature.TIER_MEDIUM;
                index <= OreDepositFeature.TIER_LARGE;
                index++) {
            OreDepositTier tier = OreDepositTier.fromIndex(index).orElseThrow();
            int size = DepositLayouts.forTier(tier).cellSizeBlocks();
            for (int x = Math.floorDiv(chunk.getMinBlockX() - reach, size);
                    x <= Math.floorDiv(chunk.getMaxBlockX() + reach, size);
                    x++) {
                for (int z = Math.floorDiv(chunk.getMinBlockZ() - reach, size);
                        z <= Math.floorDiv(chunk.getMaxBlockZ() + reach, size);
                        z++) {
                    long anchor = DepositLayouts.anchorChunkKey(tier, x, z);
                    level.getUncachedNoiseBiome(
                                    net.minecraft.core.QuartPos.fromBlock(
                                            DepositLayouts.chunkX(anchor) * 16 + 8),
                                    net.minecraft.core.QuartPos.fromBlock(
                                            Math.max(
                                                    level.getMinBuildHeight(),
                                                    Math.min(level.getMaxBuildHeight() - 1, 64))),
                                    net.minecraft.core.QuartPos.fromBlock(
                                            DepositLayouts.chunkZ(anchor) * 16 + 8))
                            .unwrapKey()
                            .ifPresent(key -> sources.add(key.location()));
                }
            }
        }
        return COMPACT_CONFIGS.entrySet().stream()
                .filter(entry -> sources.contains(entry.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream())
                .toList();
    }

    @Override
    public void modify(
            Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (!OreDepositConfig.SPEC.isLoaded()) {
            // The common config isn't loaded yet in some preview/bootstrap registry contexts
            // (before ModConfigEvent.Loading fires); nothing safe to do here yet.
            return;
        }

        if (phase != Phase.REMOVE && phase != Phase.AFTER_EVERYTHING) {
            return;
        }
        if (phase == Phase.REMOVE) {
            biome.unwrapKey()
                    .ifPresent(
                            key -> {
                                COMPACT_CONFIGS.remove(key.location());
                                SmallDepositAttemptPlanner.clearBiome(key.location());
                            });
        }

        BiomeGenerationSettingsBuilder generation = builder.getGenerationSettings();
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> features = generation.getFeatures(step);
            List<Holder<PlacedFeature>> replacements = new ArrayList<>();
            Map<Object, Replacement> evaluatedSources = new HashMap<>();
            int removed = 0;

            for (Holder<PlacedFeature> holder : List.copyOf(features)) {
                Object sourceKey =
                        holder.unwrapKey().<Object>map(key -> key).orElse(holder.value());
                Replacement replacement = evaluatedSources.get(sourceKey);
                if (replacement == null) {
                    replacement =
                            replacementFor(holder.value(), biome, stablePlacedFeatureKey(holder));
                    evaluatedSources.put(sourceKey, replacement);
                } else if (replacement != Replacement.KEEP) {
                    // A biome JSON and an AddFeatures biome modifier may both attach the exact same
                    // registered PlacedFeature. Remove the duplicate source without installing a
                    // second replacement chain or recording its frequency again.
                    features.remove(holder);
                    removed++;
                    continue;
                }
                if (replacement == Replacement.KEEP) {
                    continue;
                }
                features.remove(holder);
                removed++;
                for (PlacedFeature placedFeature : replacement.placedFeatures()) {
                    replacements.add(Holder.direct(placedFeature));
                }
            }

            coalesceIndependentTierReplacements(replacements);
            features.addAll(replacements);
            if (removed > 0) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: replaced {} ore feature(s) in biome {} step {}",
                        removed,
                        biome.unwrapKey(),
                        step);
            }
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return ModWorldgen.REPLACE_ORE_FEATURES.get();
    }

    private static Replacement replacementFor(
            PlacedFeature placedFeature, Holder<Biome> biome, String sourceSignature) {
        // A feature placing only ignored ores stays in the biome verbatim, under its own registry
        // key.
        if (Boolean.TRUE.equals(
                onlyIgnoredOres(
                        placedFeature.feature().value(),
                        Collections.newSetFromMap(new IdentityHashMap<>())))) {
            return Replacement.KEEP;
        }

        List<PlacedFeature> replacements = new ArrayList<>();
        boolean foundOreFeature =
                collectOreReplacements(
                        placedFeature.feature().value(),
                        placedFeature.placement(),
                        biome,
                        1.0F,
                        sourceSignature,
                        replacements,
                        Collections.newSetFromMap(new IdentityHashMap<>()));

        if (!foundOreFeature) {
            return Replacement.KEEP;
        }

        return replacements.isEmpty() ? Replacement.REMOVE : new Replacement(replacements);
    }

    /** Stable across JVM runs and shared by worldgen rarity scanning and /locate. */
    static String stablePlacedFeatureKey(Holder<PlacedFeature> holder) {
        return holder.unwrapKey()
                .map(key -> "placed:" + key.location())
                .orElseGet(() -> stablePlacedFeatureKey(holder.value()));
    }

    private static String stablePlacedFeatureKey(PlacedFeature placedFeature) {
        ConfiguredFeature<?, ?> configured = placedFeature.feature().value();
        ResourceLocation featureId = BuiltInRegistries.FEATURE.getKey(configured.feature());
        OreFeatureData oreData = oreFeatureData(configured.config());
        String configSignature;
        if (oreData == null) {
            configSignature = configured.config().getClass().getName() + ":" + configured.config();
        } else {
            configSignature =
                    "size="
                            + oreData.size()
                            + ";discard="
                            + oreData.discardChanceOnAirExposure()
                            + ";targets="
                            + oreData.targets().stream()
                                    .map(
                                            target ->
                                                    stableRuleTestKey(target.target)
                                                            + "->"
                                                            + BuiltInRegistries.BLOCK.getKey(
                                                                    target.state.getBlock()))
                                    .sorted()
                                    .toList();
        }
        String placementSignature =
                placedFeature.placement().stream()
                        .map(modifier -> modifier.getClass().getName() + ":" + modifier)
                        .toList()
                        .toString();
        return "direct:"
                + Long.toUnsignedString(
                        SeedMixer.hash64(
                                "feature="
                                        + featureId
                                        + ";config="
                                        + configSignature
                                        + ";placement="
                                        + placementSignature));
    }

    private static String targetSignature(OreConfiguration.TargetBlockState target) {
        return stableRuleTestKey(target.target)
                + "->"
                + BuiltInRegistries.BLOCK.getKey(target.state.getBlock());
    }

    static String stableRuleTestKey(RuleTest ruleTest) {
        TagKey<Block> tag = reflectedFieldValue(ruleTest, TagKey.class);
        if (tag != null) {
            return "tag:" + tag.location();
        }
        Block block = reflectedFieldValue(ruleTest, Block.class);
        if (block != null) {
            return "block:" + BuiltInRegistries.BLOCK.getKey(block);
        }
        return ruleTest.getClass().getName() + ":" + ruleTest;
    }

    private static boolean collectOreReplacements(
            ConfiguredFeature<?, ?> configured,
            List<PlacementModifier> placement,
            Holder<Biome> biome,
            float chance,
            String sourceSignature,
            List<PlacedFeature> replacements,
            Set<ConfiguredFeature<?, ?>> visitedConfiguredFeatures) {
        if (configured.feature() instanceof OreDepositFeature) {
            return false;
        }

        if (!visitedConfiguredFeatures.add(configured)) {
            return false;
        }

        OreFeatureData oreData = oreFeatureData(configured.config());
        if (oreData != null) {
            boolean result =
                    addOreReplacements(
                            configured,
                            oreData,
                            placement,
                            biome,
                            chance,
                            sourceSignature,
                            replacements);
            visitedConfiguredFeatures.remove(configured);
            return result;
        }

        boolean foundOreFeature = false;
        if (configured.config() instanceof RandomFeatureConfiguration randomFeatureConfiguration) {
            float remainingChance = 1.0F;
            for (int branch = 0; branch < randomFeatureConfiguration.features.size(); branch++) {
                WeightedPlacedFeature weightedFeature =
                        randomFeatureConfiguration.features.get(branch);
                float weightedChance = chance * remainingChance * weightedFeature.chance;
                remainingChance *= 1.0F - weightedFeature.chance;
                foundOreFeature |=
                        collectOreReplacements(
                                weightedFeature.feature.value().feature().value(),
                                mergedPlacement(
                                        placement, weightedFeature.feature.value().placement()),
                                biome,
                                weightedChance,
                                sourceSignature + "/weighted/" + branch,
                                replacements,
                                visitedConfiguredFeatures);
            }

            if (remainingChance > 0.0F) {
                foundOreFeature |=
                        collectOreReplacements(
                                randomFeatureConfiguration.defaultFeature.value().feature().value(),
                                mergedPlacement(
                                        placement,
                                        randomFeatureConfiguration
                                                .defaultFeature
                                                .value()
                                                .placement()),
                                biome,
                                chance * remainingChance,
                                sourceSignature + "/default",
                                replacements,
                                visitedConfiguredFeatures);
            }
            visitedConfiguredFeatures.remove(configured);
            return foundOreFeature;
        }

        List<ConfiguredFeature<?, ?>> children = configured.config().getFeatures().toList();
        for (int childIndex = 0; childIndex < children.size(); childIndex++) {
            foundOreFeature |=
                    collectOreReplacements(
                            children.get(childIndex),
                            placement,
                            biome,
                            chance,
                            sourceSignature + "/child/" + childIndex,
                            replacements,
                            visitedConfiguredFeatures);
        }
        visitedConfiguredFeatures.remove(configured);
        return foundOreFeature;
    }

    private static boolean addOreReplacements(
            ConfiguredFeature<?, ?> configured,
            OreFeatureData oreData,
            List<PlacementModifier> placement,
            Holder<Biome> biome,
            float chance,
            String sourceSignature,
            List<PlacedFeature> replacements) {
        // Targets that still become deposit tiers, and targets that keep their original vanilla
        // vein because the deposit palette cannot represent them.
        List<OreConfiguration.TargetBlockState> depositTargets = new ArrayList<>();
        List<OreConfiguration.TargetBlockState> normalVeinTargets = new ArrayList<>();
        // Ignored ores keep their own source block and vein; they are neither unified nor recorded
        // anywhere.
        List<OreConfiguration.TargetBlockState> ignoredTargets = new ArrayList<>();
        int oreTargets = 0;
        boolean hasOreTarget = false;
        boolean hasCanonicalTarget = false;
        Block firstCanonicalOre = null;

        for (OreConfiguration.TargetBlockState target : oreData.targets()) {
            if (!OreTags.isOre(target.state)) {
                continue;
            }

            hasOreTarget = true;
            oreTargets++;
            Block sourceOre = target.state.getBlock();
            if (isOreIgnored(sourceOre)) {
                ignoredTargets.add(target);
                continue;
            }
            var canonical = OreUnifier.canonicalFor(sourceOre, classifyTarget(target.target));
            if (canonical.isEmpty()) {
                continue;
            }
            OreSpawnDimensions.record(sourceOre, biome);
            OreSpawnDimensions.record(canonical.get(), biome);
            // Disabled ores stay out of the rarity tables, as in OreSpawnDimensions' scan.
            if (!isOreDisabled(sourceOre) && !isOreDisabled(canonical.get())) {
                OreGenerationWeights.recordObservation(
                        canonical.get(),
                        biome,
                        sourceSignature,
                        configured,
                        placement,
                        chance,
                        oreData.size(),
                        target.target);
            }

            OreConfiguration.TargetBlockState unifiedTarget =
                    OreConfiguration.target(target.target, canonical.get().defaultBlockState());

            ResourceLocation canonicalId = BuiltInRegistries.BLOCK.getKey(canonical.get());
            if (canonicalId == null || !OreDepositOrePalette.availableIds().contains(canonicalId)) {
                // The attachment stores an 8-bit ore-palette index. Never coerce an unrepresentable
                // ore to index zero: preserve its normal vein instead, so its texture, drop and
                // /locate material remain truthful even in a very large modpack.
                if (canonicalId != null && LOGGED_UNREPRESENTABLE_ORES.add(canonicalId)) {
                    OresAndDrillsMod.LOGGER.debug(
                            "Ore deposits: {} is outside the {}-entry deposit palette; keeping its"
                                    + " original vein",
                            canonicalId,
                            OreDepositOrePalette.MAX_ORES);
                }
                normalVeinTargets.add(unifiedTarget);
                continue;
            }

            // Unification means one canonical block can be reached from several mods' source
            // blocks, so an exclusion entered as either the source mod's own ID or the
            // canonical/unified ID must match. A disabled ore generates nothing: neither deposits
            // nor its original vein.
            if (isOreDisabled(sourceOre) || isOreDisabled(canonical.get())) {
                ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(canonical.get());
                if (oreId != null && LOGGED_DISABLED_ORES.add(oreId)) {
                    OresAndDrillsMod.LOGGER.debug(
                            "Ore deposits: {} disabled for this world; removing its generation",
                            oreId);
                }
                continue;
            }

            hasCanonicalTarget = true;
            if (firstCanonicalOre == null) {
                firstCanonicalOre = canonical.get();
            }
            depositTargets.add(unifiedTarget);
        }

        if (!hasOreTarget) {
            return false;
        }

        if (ignoredTargets.size() == oreTargets) {
            // Only reached inside a feature that also places handled ores (see replacementFor).
            replacements.add(ignoredOrePlacement(configured, placement, chance));
            return true;
        }
        if (!ignoredTargets.isEmpty()) {
            ConfiguredFeature<OreConfiguration, Feature<OreConfiguration>> ignoredOreFeature =
                    new ConfiguredFeature<OreConfiguration, Feature<OreConfiguration>>(
                            Feature.ORE,
                            new OreConfiguration(
                                    ignoredTargets,
                                    oreData.size(),
                                    oreData.discardChanceOnAirExposure()));
            replacements.add(ignoredOrePlacement(ignoredOreFeature, placement, chance));
        }

        // A datapack rule owns its ore before any automatic candidate is created. This prevents the
        // original PlacedFeature and the explicit cell planner from both generating the same
        // material.
        if (firstCanonicalOre != null
                && DataDrivenOreDepositBiomeModifier.explicitlyConfigured(
                        biome, firstCanonicalOre)) {
            return true;
        }

        if (!normalVeinTargets.isEmpty()) {
            // Rebuild the vanilla-style vein with OreUnifier's canonical block instead of leaving
            // the untouched original in place, so duplicate cross-mod ores don't spawn side by side
            // here either.
            ConfiguredFeature<OreConfiguration, Feature<OreConfiguration>> unifiedOreFeature =
                    new ConfiguredFeature<OreConfiguration, Feature<OreConfiguration>>(
                            Feature.ORE,
                            new OreConfiguration(
                                    normalVeinTargets,
                                    oreData.size(),
                                    oreData.discardChanceOnAirExposure()));
            replacements.add(new PlacedFeature(Holder.direct(unifiedOreFeature), placement));
        }

        if (!hasCanonicalTarget || chance <= 0.0F) {
            return true;
        }

        OreSettingsPresetManager.Settings presetSettings =
                OreSettingsPresetManager.resolve(firstCanonicalOre);
        if (!presetSettings.enabled()) {
            ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(firstCanonicalOre);
            if (oreId != null && LOGGED_PRESET_DISABLED_ORES.add(oreId)) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: disabled {} by datapack preset {}",
                        oreId,
                        OreSettingsPresetManager.activePresetId());
            }
            return true;
        }

        logReplacement(configured, depositTargets, biome);

        OreOverrides.OreOverride override =
                OreOverrides.lookupConfigured(firstCanonicalOre)
                        .orElseGet(presetSettings::multipliers);
        float frequencyMultiplier = clamp(override.frequency(), 0.1F, 6.0F);
        float sizeMultiplier = override.size();
        float richnessMultiplier = override.richness();

        OreDepositFeature.OreVein vanillaVein =
                OreGenerationMode.vanillaLikeGeneration()
                        ? vanillaPlacementVein(
                                oreData, placement, sourceSignature, firstCanonicalOre)
                        : null;
        if (vanillaVein != null) {
            // The original placement chain is kept verbatim: Minecraft evaluates its count, rarity,
            // height provider and filters (including datapack and modded modifiers), and each
            // attempt builds a vein of the original shape out of deposit blocks.
            replacements.add(
                    new PlacedFeature(
                            Holder.direct(
                                    configuredDeposit(
                                            new OreDepositFeature.Configuration(
                                                    depositTargets,
                                                    OreDepositFeature.TIER_TINY,
                                                    chance,
                                                    frequencyMultiplier,
                                                    sizeMultiplier,
                                                    richnessMultiplier,
                                                    biome.unwrapKey().map(key -> key.location()),
                                                    Optional.empty(),
                                                    Optional.of(vanillaVein)))),
                            placement));
        }

        if (vanillaVein == null) {
            // One TINY/SMALL source per original feature: its stone and deepslate targets stay one
            // source, and different features of one material stay separate sources. The feature
            // runs once per chunk and evaluates the original modifiers itself with a stable seed,
            // so they are neither run twice nor counted twice; the selector branch chance is
            // applied once per attempt.
            if (!oreData.sizeKnown()) {
                logUnknownVeinSize(firstCanonicalOre, sourceSignature, oreData.size());
            }
            OreDepositFeature.OreVein sourceVein =
                    new OreDepositFeature.OreVein(
                            oreData.size(),
                            clamp(oreData.discardChanceOnAirExposure(), 0.0F, 1.0F),
                            false,
                            sourceSignature,
                            placement);
            OreDepositFeature.Configuration smallConfig =
                    new OreDepositFeature.Configuration(
                            depositTargets,
                            OreDepositFeature.TIER_TINY,
                            chance,
                            frequencyMultiplier,
                            sizeMultiplier,
                            richnessMultiplier,
                            biome.unwrapKey().map(key -> key.location()),
                            Optional.empty(),
                            Optional.of(sourceVein));
            biome.unwrapKey()
                    .ifPresent(
                            key ->
                                    SmallDepositAttemptPlanner.register(
                                            key.location(), smallConfig));
            replacements.add(
                    new PlacedFeature(Holder.direct(configuredDeposit(smallConfig)), List.of()));
        }

        for (int tier = OreDepositFeature.TIER_MEDIUM;
                tier < OreDepositFeature.TIER_COUNT;
                tier++) {
            // The original chain is scanned for frequency, height and target metadata only.
            // Candidate existence itself is owned by OreDepositFeature's seed planner; keeping a
            // Count/Rarity/Noise modifier here would leave /locate unable to reproduce whether this
            // call happens.
            addTierReplacement(
                    replacements,
                    depositTargets,
                    tier,
                    1.0F,
                    frequencyMultiplier,
                    sizeMultiplier,
                    richnessMultiplier,
                    deterministicDepositAttemptPlacement(),
                    biome,
                    Optional.empty());
        }
        return true;
    }

    private static void logUnknownVeinSize(Block ore, String sourceSignature, int fallbackSize) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (oreId != null && LOGGED_VANILLA_LIKE_FALLBACKS.add(oreId)) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposits: {} source {} has no readable vein size; TINY/SMALL use vein size"
                            + " {}",
                    oreId,
                    sourceSignature,
                    fallbackSize);
        }
    }

    /**
     * Describes one original ore feature for vanilla-like veins. Minecraft runs the original
     * placement chain verbatim (an empty chain runs once per chunk, as in vanilla); an unreadable
     * vein size uses the same fallback as the regular mode. The regular mode's attempt thinning
     * never applies here.
     */
    private static OreDepositFeature.OreVein vanillaPlacementVein(
            OreFeatureData oreData,
            List<PlacementModifier> placement,
            String sourceSignature,
            Block ore) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (!oreData.sizeKnown()) {
            logUnknownVeinSize(ore, sourceSignature, oreData.size());
        }
        if (LOGGED_VANILLA_LIKE_SOURCES.add(sourceSignature)) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposits: vanilla-like veins for {} from {}: vein size {}",
                    oreId,
                    sourceSignature,
                    oreData.size());
        }
        return new OreDepositFeature.OreVein(
                oreData.size(),
                clamp(oreData.discardChanceOnAirExposure(), 0.0F, 1.0F),
                true,
                sourceSignature);
    }

    static OreFeatureData oreFeatureData(FeatureConfiguration config) {
        if (config instanceof OreConfiguration oreConfiguration) {
            return new OreFeatureData(
                    oreConfiguration.targetStates,
                    oreConfiguration.size,
                    oreConfiguration.discardChanceOnAirExposure,
                    true);
        }

        List<OreConfiguration.TargetBlockState> targets = targetList(config, "targetStates");
        if (targets == null) {
            targets = targetList(config, "targetList");
        }
        if (targets == null || targets.isEmpty()) {
            return null;
        }

        Integer size = reflectedSize(config);
        return new OreFeatureData(
                targets, size == null ? 8 : size, reflectedDiscardChance(config), size != null);
    }

    private static float reflectedDiscardChance(FeatureConfiguration config) {
        Object value = reflectedValue(config, "discardChanceOnAirExposure");
        return value instanceof Number number ? clamp(number.floatValue(), 0.0F, 1.0F) : 0.0F;
    }

    @SuppressWarnings("unchecked")
    private static List<OreConfiguration.TargetBlockState> targetList(
            FeatureConfiguration config, String methodName) {
        try {
            Method method = config.getClass().getMethod(methodName);
            method.setAccessible(true);
            Object value = method.invoke(config);
            if (!(value instanceof List<?> list)) {
                return null;
            }

            for (Object entry : list) {
                if (!(entry instanceof OreConfiguration.TargetBlockState)) {
                    return null;
                }
            }
            return (List<OreConfiguration.TargetBlockState>) list;
        } catch (ReflectiveOperationException exception) {
            String key = config.getClass().getName() + "#" + methodName;
            if (isKnownOreConfig(config) && LOGGED_REFLECTION_FALLBACKS.add(key)) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: {}.{}() is unavailable; trying compatible fallbacks",
                        config.getClass().getName(),
                        methodName);
            }
            return null;
        }
    }

    /**
     * The source vein size, or {@code null} when it cannot be read (callers fall back to size 8).
     */
    private static Integer reflectedSize(FeatureConfiguration config) {
        Integer resolved = resolveSize(reflectedValue(config, "size"));
        if (resolved != null) {
            return resolved;
        }

        return resolveSize(reflectedValue(config, "getSize"));
    }

    /**
     * Some mods back their feature size with a lazy supplier tied to their own mod config (e.g.
     * Mekanism's {@code CachedIntValue}), which can throw if invoked before that config is loaded —
     * notably during the world-creation screen's preview scan, well before any world (and its
     * configs) actually exist.
     */
    private static Integer resolveSize(Object value) {
        try {
            if (value instanceof IntSupplier supplier) {
                return Math.max(1, supplier.getAsInt());
            }
            if (value instanceof Number number) {
                return Math.max(1, number.intValue());
            }
        } catch (RuntimeException exception) {
            return null;
        }
        return null;
    }

    private static Object reflectedValue(FeatureConfiguration config, String methodName) {
        try {
            Method method = config.getClass().getMethod(methodName);
            method.setAccessible(true);
            return method.invoke(config);
        } catch (ReflectiveOperationException exception) {
            return null;
        }
    }

    private static boolean isKnownOreConfig(FeatureConfiguration config) {
        String name = config.getClass().getName().toLowerCase(Locale.ROOT);
        return name.contains("ore");
    }

    private static void logReplacement(
            ConfiguredFeature<?, ?> configured,
            List<OreConfiguration.TargetBlockState> targets,
            Holder<Biome> biome) {
        if (!OresAndDrillsMod.LOGGER.isTraceEnabled()) {
            return;
        }
        String targetIds =
                targets.stream()
                        .map(target -> BuiltInRegistries.BLOCK.getKey(target.state.getBlock()))
                        .filter(java.util.Objects::nonNull)
                        .map(ResourceLocation::toString)
                        .sorted()
                        .collect(Collectors.joining(", "));
        String key = configured.feature().getClass().getName() + "|" + targetIds;
        if (!LOGGED_REPLACEMENTS.add(key)) {
            return;
        }

        OresAndDrillsMod.LOGGER.debug(
                "Ore deposits: replacing feature {} config {} for targets [{}] in biome {}"
                        + " (worldgen-frequency weighted deposit tiers)",
                configured.feature().getClass().getName(),
                configured.config().getClass().getName(),
                targetIds,
                biome.unwrapKey().map(value -> value.location().toString()).orElse("<direct>"));
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
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

    private static List<PlacementModifier> mergedPlacement(
            List<PlacementModifier> parentPlacement, List<PlacementModifier> childPlacement) {
        if (childPlacement.isEmpty()) {
            return parentPlacement;
        }

        List<PlacementModifier> merged =
                new ArrayList<>(parentPlacement.size() + childPlacement.size());
        merged.addAll(parentPlacement);
        merged.addAll(childPlacement);
        return List.copyOf(merged);
    }

    private static ConfiguredFeature<OreDepositFeature.Configuration, OreDepositFeature>
            configuredDeposit(OreDepositFeature.Configuration config) {
        return new ConfiguredFeature<>(ModWorldgen.ORE_DEPOSIT_FEATURE.get(), config);
    }

    private static void addTierReplacement(
            List<PlacedFeature> replacements,
            List<OreConfiguration.TargetBlockState> targets,
            int tier,
            float sourceProbability,
            float frequencyMultiplier,
            float sizeMultiplier,
            float richnessMultiplier,
            List<PlacementModifier> placement,
            Holder<Biome> sourceBiome,
            Optional<OreDepositFeature.OreVein> vein) {
        Optional<ResourceLocation> sourceBiomeId =
                sourceBiome.unwrapKey().map(key -> key.location());
        OreDepositFeature.Configuration config =
                new OreDepositFeature.Configuration(
                        targets,
                        tier,
                        sourceProbability,
                        frequencyMultiplier,
                        sizeMultiplier,
                        richnessMultiplier,
                        sourceBiomeId,
                        Optional.empty(),
                        vein);
        if (tier >= OreDepositFeature.TIER_MEDIUM && sourceBiomeId.isPresent()) {
            COMPACT_CONFIGS.compute(
                    sourceBiomeId.get(),
                    (id, old) -> {
                        List<OreDepositFeature.Configuration> values =
                                old == null ? new ArrayList<>() : new ArrayList<>(old);
                        String material =
                                OreUnifier.materialKeyFor(targets.getFirst().state.getBlock());
                        for (int i = 0; i < values.size(); i++) {
                            OreDepositFeature.Configuration existing = values.get(i);
                            if (existing.sizeTier() != tier
                                    || !material.equals(
                                            OreUnifier.materialKeyFor(
                                                    existing.targets()
                                                            .getFirst()
                                                            .state
                                                            .getBlock()))) continue;
                            List<OreConfiguration.TargetBlockState> merged =
                                    new ArrayList<>(existing.targets());
                            for (OreConfiguration.TargetBlockState target : targets) {
                                String signature = targetSignature(target);
                                if (merged.stream()
                                        .noneMatch(t -> signature.equals(targetSignature(t))))
                                    merged.add(target);
                            }
                            merged.sort(
                                    java.util.Comparator.comparing(
                                            t ->
                                                    stableRuleTestKey(t.target)
                                                            + "->"
                                                            + BuiltInRegistries.BLOCK.getKey(
                                                                    t.state.getBlock())));
                            values.set(
                                    i,
                                    new OreDepositFeature.Configuration(
                                            merged,
                                            tier,
                                            1.0F,
                                            frequencyMultiplier,
                                            sizeMultiplier,
                                            richnessMultiplier,
                                            sourceBiomeId));
                            return List.copyOf(values);
                        }
                        values.add(config);
                        return List.copyOf(values);
                    });
        }
        replacements.add(new PlacedFeature(Holder.direct(configuredDeposit(config)), placement));
    }

    /** Candidate existence for every tier is owned by the common immutable cell planner. */
    private static List<PlacementModifier> deterministicDepositAttemptPlacement() {
        return List.of();
    }

    /**
     * MEDIUM/LARGE own one shared material lottery per cell. Several source features of the same
     * unified material contribute metadata, but must not each launch an independent physical
     * candidate.
     */
    static void coalesceIndependentTierReplacements(List<Holder<PlacedFeature>> replacements) {
        Map<IndependentTierKey, Integer> firstByKey = new LinkedHashMap<>();
        List<Holder<PlacedFeature>> result = new ArrayList<>(replacements.size());
        for (Holder<PlacedFeature> holder : replacements) {
            PlacedFeature placed = holder.value();
            ConfiguredFeature<?, ?> configured = placed.feature().value();
            if (!(configured.feature() instanceof OreDepositFeature)
                    || !(configured.config() instanceof OreDepositFeature.Configuration config)
                    || config.sizeTier() < OreDepositFeature.TIER_MEDIUM
                    || config.targets().isEmpty()) {
                result.add(holder);
                continue;
            }

            String materialKey =
                    OreUnifier.materialKeyFor(config.targets().getFirst().state.getBlock());
            IndependentTierKey key =
                    new IndependentTierKey(config.sizeTier(), materialKey, config.sourceBiome());
            Integer existingIndex = firstByKey.get(key);
            if (existingIndex == null) {
                firstByKey.put(key, result.size());
                result.add(holder);
                continue;
            }

            PlacedFeature existing = result.get(existingIndex).value();
            OreDepositFeature.Configuration existingConfig =
                    (OreDepositFeature.Configuration) existing.feature().value().config();
            List<OreConfiguration.TargetBlockState> mergedTargets =
                    new ArrayList<>(existingConfig.targets());
            for (OreConfiguration.TargetBlockState target : config.targets()) {
                if (!mergedTargets.contains(target)) {
                    mergedTargets.add(target);
                }
            }
            OreDepositFeature.Configuration mergedConfig =
                    new OreDepositFeature.Configuration(
                            List.copyOf(mergedTargets),
                            existingConfig.sizeTier(),
                            existingConfig.sourceProbability(),
                            existingConfig.frequencyMultiplier(),
                            existingConfig.sizeMultiplier(),
                            existingConfig.richnessMultiplier(),
                            existingConfig.sourceBiome());
            OreDepositFeature depositFeature =
                    (OreDepositFeature) existing.feature().value().feature();
            ConfiguredFeature<OreDepositFeature.Configuration, OreDepositFeature> mergedFeature =
                    new ConfiguredFeature<>(depositFeature, mergedConfig);
            result.set(
                    existingIndex,
                    Holder.direct(
                            new PlacedFeature(Holder.direct(mergedFeature), existing.placement())));
        }
        replacements.clear();
        replacements.addAll(result);
    }

    static String classifyTarget(RuleTest ruleTest) {
        TagKey<Block> tag = reflectedFieldValue(ruleTest, TagKey.class);
        if (tag != null) {
            return baseKeyFromPath(tag.location().getPath());
        }

        Block block = reflectedFieldValue(ruleTest, Block.class);
        if (block != null) {
            ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(block);
            return blockId != null ? baseKeyFromPath(blockId.getPath()) : null;
        }

        return null;
    }

    private static String baseKeyFromPath(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        if (lower.contains("deepslate")) {
            return "deepslate";
        }
        if (lower.contains("nether")) {
            return "nether";
        }
        if (lower.contains("end")) {
            return "end";
        }
        if (lower.contains("stone") || lower.contains("replaceable")) {
            return "stone";
        }
        return lower;
    }

    /** Disabled for this world on its creation screen. */
    static boolean isOreDisabled(Block block) {
        return OreWorldDisabledOres.isDisabled(block);
    }

    /**
     * Listed in the common config's {@code ignored_ores}: the mod leaves the ore's own worldgen
     * untouched (no deposits, no unification, no rarity weight). An entry matches the source block
     * or the unified block of its material, so ignoring {@code minecraft:iron_ore} also leaves
     * every other mod's iron ore alone.
     */
    public static boolean isOreIgnored(Block block) {
        if (!OreDepositConfig.SPEC.isLoaded()) {
            return false;
        }
        List<? extends String> ignored = OreDepositConfig.IGNORED_ORES.get();
        if (ignored.isEmpty()) {
            return false;
        }
        if (OreWorldDisabledOres.matches(ignored, block)) {
            return true;
        }
        Block unified = OreUnifier.unifiedBlockFor(block).orElse(block);
        return unified != block && OreWorldDisabledOres.matches(ignored, unified);
    }

    /**
     * Whether {@link #modify} replaces this feature: it reaches at least one ore target that is not
     * ignored.
     */
    static boolean placesHandledOre(ConfiguredFeature<?, ?> configured) {
        return Boolean.FALSE.equals(
                onlyIgnoredOres(configured, Collections.newSetFromMap(new IdentityHashMap<>())));
    }

    /**
     * {@code TRUE} when every ore target reachable from {@code configured} is ignored, {@code
     * FALSE} when any ore target is not, {@code null} when it places no ore at all. Mirrors {@link
     * #collectOreReplacements}.
     */
    private static Boolean onlyIgnoredOres(
            ConfiguredFeature<?, ?> configured, Set<ConfiguredFeature<?, ?>> visited) {
        if (configured.feature() instanceof OreDepositFeature || !visited.add(configured)) {
            return null;
        }
        Boolean result = null;
        OreFeatureData oreData = oreFeatureData(configured.config());
        if (oreData != null) {
            for (OreConfiguration.TargetBlockState target : oreData.targets()) {
                if (OreTags.isOre(target.state)) {
                    if (!isOreIgnored(target.state.getBlock())) {
                        visited.remove(configured);
                        return Boolean.FALSE;
                    }
                    result = Boolean.TRUE;
                }
            }
            visited.remove(configured);
            return result;
        }

        List<ConfiguredFeature<?, ?>> children = new ArrayList<>();
        if (configured.config() instanceof RandomFeatureConfiguration random) {
            random.features.forEach(
                    weighted -> children.add(weighted.feature.value().feature().value()));
            children.add(random.defaultFeature.value().feature().value());
        } else {
            configured.config().getFeatures().forEach(children::add);
        }
        for (ConfiguredFeature<?, ?> child : children) {
            Boolean childResult = onlyIgnoredOres(child, visited);
            if (Boolean.FALSE.equals(childResult)) {
                visited.remove(configured);
                return Boolean.FALSE;
            }
            if (childResult != null) {
                result = Boolean.TRUE;
            }
        }
        visited.remove(configured);
        return result;
    }

    /**
     * An ignored ore inside a feature that also places handled ores: its original configured
     * feature keeps its placement, and a weighted-selector branch keeps its chance (rounded to a "1
     * in N" rarity).
     */
    private static PlacedFeature ignoredOrePlacement(
            ConfiguredFeature<?, ?> configured, List<PlacementModifier> placement, float chance) {
        if (chance >= 0.999F) {
            return new PlacedFeature(Holder.direct(configured), placement);
        }
        List<PlacementModifier> thinned = new ArrayList<>(placement);
        thinned.add(
                RarityFilter.onAverageOnceEvery(
                        Math.max(1, Math.round(1.0F / Math.max(chance, 1.0E-4F)))));
        return new PlacedFeature(Holder.direct(configured), List.copyOf(thinned));
    }

    record OreFeatureData(
            List<OreConfiguration.TargetBlockState> targets,
            int size,
            float discardChanceOnAirExposure,
            boolean sizeKnown) {}

    private record Replacement(List<PlacedFeature> placedFeatures) {
        private static final Replacement KEEP = new Replacement(List.of());
        private static final Replacement REMOVE = new Replacement(List.of());
    }

    private record IndependentTierKey(
            int tier, String materialKey, Optional<ResourceLocation> sourceBiome) {}
}

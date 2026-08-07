package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.config.OreOverrides;
import dev.config.OreSettingsPresetManager;
import dev.registry.ModWorldgen;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
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
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.neoforged.neoforge.common.world.BiomeGenerationSettingsBuilder;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntSupplier;
import java.lang.reflect.Method;
import java.util.stream.Collectors;

public enum ReplaceOreFeaturesBiomeModifier implements BiomeModifier {
    INSTANCE;

    private static final Set<String> LOGGED_REPLACEMENTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> LOGGED_REFLECTION_FALLBACKS = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> LOGGED_DISABLED_ORES = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> LOGGED_PRESET_DISABLED_ORES = ConcurrentHashMap.newKeySet();
    private static final Set<ResourceLocation> LOGGED_UNREPRESENTABLE_ORES = ConcurrentHashMap.newKeySet();

    @Override
    public void modify(Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (!OreDepositConfig.SPEC.isLoaded()) {
            // The common config isn't loaded yet in some preview/bootstrap registry contexts (before
            // ModConfigEvent.Loading fires); nothing safe to do here yet.
            return;
        }

        if (phase != Phase.REMOVE && phase != Phase.AFTER_EVERYTHING) {
            return;
        }

        BiomeGenerationSettingsBuilder generation = builder.getGenerationSettings();
        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> features = generation.getFeatures(step);
            List<Holder<PlacedFeature>> replacements = new ArrayList<>();
            Map<Object, Replacement> evaluatedSources = new HashMap<>();
            int removed = 0;

            for (Holder<PlacedFeature> holder : List.copyOf(features)) {
                Object sourceKey = holder.unwrapKey().<Object>map(key -> key).orElse(holder.value());
                Replacement replacement = evaluatedSources.get(sourceKey);
                if (replacement == null) {
                    replacement = replacementFor(holder.value(), biome, stablePlacedFeatureKey(holder));
                    evaluatedSources.put(sourceKey, replacement);
                } else if (replacement != Replacement.KEEP) {
                    // A biome JSON and an AddFeatures biome modifier may both attach the exact same
                    // registered PlacedFeature. Remove the duplicate source without installing a second
                    // replacement chain or recording its frequency again.
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
                OresAndDrillsMod.LOGGER.debug("Ore deposits: replaced {} ore feature(s) in biome {} step {}", removed, biome.unwrapKey(), step);
            }
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return ModWorldgen.REPLACE_ORE_FEATURES.get();
    }

    private static Replacement replacementFor(
            PlacedFeature placedFeature,
            Holder<Biome> biome,
            String sourceSignature
    ) {
        List<PlacedFeature> replacements = new ArrayList<>();
        boolean foundOreFeature = collectOreReplacements(
                placedFeature.feature().value(),
                placedFeature.placement(),
                biome,
                1.0F,
                sourceSignature,
                replacements,
                Collections.newSetFromMap(new IdentityHashMap<>())
        );

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
            configSignature = "size=" + oreData.size()
                    + ";discard=" + oreData.discardChanceOnAirExposure()
                    + ";targets=" + oreData.targets().stream()
                            .map(target -> stableRuleTestKey(target.target) + "->"
                                    + BuiltInRegistries.BLOCK.getKey(target.state.getBlock()))
                            .sorted()
                            .toList();
        }
        String placementSignature = placedFeature.placement().stream()
                .map(modifier -> modifier.getClass().getName() + ":" + modifier)
                .toList().toString();
        return "direct:" + Long.toUnsignedString(SeedMixer.hash64(
                "feature=" + featureId + ";config=" + configSignature + ";placement=" + placementSignature
        ));
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
            Set<ConfiguredFeature<?, ?>> visitedConfiguredFeatures
    ) {
        if (configured.feature() instanceof OreDepositFeature) {
            return false;
        }

        if (!visitedConfiguredFeatures.add(configured)) {
            return false;
        }

        OreFeatureData oreData = oreFeatureData(configured.config());
        if (oreData != null) {
            boolean result = addOreReplacements(
                    configured, oreData, placement, biome, chance, sourceSignature, replacements
            );
            visitedConfiguredFeatures.remove(configured);
            return result;
        }

        boolean foundOreFeature = false;
        if (configured.config() instanceof RandomFeatureConfiguration randomFeatureConfiguration) {
            float remainingChance = 1.0F;
            for (int branch = 0; branch < randomFeatureConfiguration.features.size(); branch++) {
                WeightedPlacedFeature weightedFeature = randomFeatureConfiguration.features.get(branch);
                float weightedChance = chance * remainingChance * weightedFeature.chance;
                remainingChance *= 1.0F - weightedFeature.chance;
                foundOreFeature |= collectOreReplacements(
                        weightedFeature.feature.value().feature().value(),
                        mergedPlacement(placement, weightedFeature.feature.value().placement()),
                        biome,
                        weightedChance,
                        sourceSignature + "/weighted/" + branch,
                        replacements,
                        visitedConfiguredFeatures
                );
            }

            if (remainingChance > 0.0F) {
                foundOreFeature |= collectOreReplacements(
                        randomFeatureConfiguration.defaultFeature.value().feature().value(),
                        mergedPlacement(placement, randomFeatureConfiguration.defaultFeature.value().placement()),
                        biome,
                        chance * remainingChance,
                        sourceSignature + "/default",
                        replacements,
                        visitedConfiguredFeatures
                );
            }
            visitedConfiguredFeatures.remove(configured);
            return foundOreFeature;
        }

        List<ConfiguredFeature<?, ?>> children = configured.config().getFeatures().toList();
        for (int childIndex = 0; childIndex < children.size(); childIndex++) {
            foundOreFeature |= collectOreReplacements(
                    children.get(childIndex), placement, biome, chance,
                    sourceSignature + "/child/" + childIndex, replacements, visitedConfiguredFeatures
            );
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
            List<PlacedFeature> replacements
    ) {
        // Targets that still become deposit tiers, and targets that keep their original vanilla vein
        // instead because the player excluded that ore from deposits (it still spawns normally).
        List<OreConfiguration.TargetBlockState> depositTargets = new ArrayList<>();
        List<OreConfiguration.TargetBlockState> normalVeinTargets = new ArrayList<>();
        boolean hasOreTarget = false;
        boolean hasCanonicalTarget = false;
        Block firstCanonicalOre = null;

        for (OreConfiguration.TargetBlockState target : oreData.targets()) {
            if (!OreTags.isOre(target.state)) {
                continue;
            }

            hasOreTarget = true;
            Block sourceOre = target.state.getBlock();
            var canonical = OreUnifier.canonicalFor(sourceOre, classifyTarget(target.target));
            if (canonical.isEmpty()) {
                continue;
            }
            OreSpawnDimensions.record(sourceOre, biome);
            OreSpawnDimensions.record(canonical.get(), biome);
            OreGenerationWeights.recordObservation(
                    canonical.get(), biome, sourceSignature, configured,
                    placement, chance, oreData.size(), target.target
            );

            OreConfiguration.TargetBlockState unifiedTarget = OreConfiguration.target(target.target, canonical.get().defaultBlockState());

            ResourceLocation canonicalId = BuiltInRegistries.BLOCK.getKey(canonical.get());
            if (canonicalId == null || !OreDepositOrePalette.availableIds().contains(canonicalId)) {
                // The attachment stores an 8-bit ore-palette index. Never coerce an unrepresentable ore to
                // index zero: preserve its normal vein instead, so its texture, drop and /locate material
                // remain truthful even in a very large modpack.
                if (canonicalId != null && LOGGED_UNREPRESENTABLE_ORES.add(canonicalId)) {
                    OresAndDrillsMod.LOGGER.debug(
                            "Ore deposits: {} is outside the {}-entry deposit palette; keeping its original vein",
                            canonicalId, OreDepositOrePalette.MAX_ORES
                    );
                }
                normalVeinTargets.add(unifiedTarget);
                continue;
            }

            // Unification means one canonical block can be reached from several mods' source blocks, so an
            // exclusion entered as either the source mod's own ID or the canonical/unified ID must match.
            if (isDepositDisabled(sourceOre) || isDepositDisabled(canonical.get())) {
                ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(canonical.get());
                if (oreId != null && LOGGED_DISABLED_ORES.add(oreId)) {
                    OresAndDrillsMod.LOGGER.debug("Ore deposits: {} excluded from deposits by server config; keeping its original vein instead", oreId);
                }
                normalVeinTargets.add(unifiedTarget);
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

        // A datapack rule owns its ore before any automatic candidate is created. This prevents the
        // original PlacedFeature and the explicit cell planner from both generating the same material.
        if (firstCanonicalOre != null
                && DataDrivenOreDepositBiomeModifier.explicitlyConfigured(biome, firstCanonicalOre)) {
            return true;
        }

        if (!normalVeinTargets.isEmpty()) {
            // Rebuild the vanilla-style vein with OreUnifier's canonical block instead of leaving the
            // untouched original in place, so duplicate cross-mod ores don't spawn side by side here either.
            ConfiguredFeature<OreConfiguration, Feature<OreConfiguration>> unifiedOreFeature = new ConfiguredFeature<OreConfiguration, Feature<OreConfiguration>>(
                    Feature.ORE, new OreConfiguration(normalVeinTargets, oreData.size(), oreData.discardChanceOnAirExposure())
            );
            replacements.add(new PlacedFeature(Holder.direct(unifiedOreFeature), placement));
        }

        if (!hasCanonicalTarget || chance <= 0.0F) {
            return true;
        }

        OreSettingsPresetManager.Settings presetSettings = OreSettingsPresetManager.resolve(firstCanonicalOre);
        if (!presetSettings.enabled()) {
            ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(firstCanonicalOre);
            if (oreId != null && LOGGED_PRESET_DISABLED_ORES.add(oreId)) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: disabled {} by datapack preset {}",
                        oreId,
                        OreSettingsPresetManager.activePresetId()
                );
            }
            return true;
        }

        logReplacement(configured, depositTargets, biome);

        OreOverrides.OreOverride override = OreOverrides.lookupConfigured(firstCanonicalOre)
                .orElseGet(presetSettings::multipliers);
        float frequencyMultiplier = clamp(override.frequency(), 0.1F, 6.0F);
        float sizeMultiplier = override.size();
        float richnessMultiplier = override.richness();

        for (int tier = OreDepositFeature.TIER_TINY; tier < OreDepositFeature.TIER_COUNT; tier++) {
            // The original chain is scanned for frequency, height and target metadata only. Candidate
            // existence itself is owned by OreDepositFeature's seed planner for every tier; retaining a
            // Count/Rarity/Noise modifier here made /locate unable to reproduce whether this call existed.
            List<PlacementModifier> tierPlacement = deterministicDepositAttemptPlacement();
            if (tier < OreDepositFeature.TIER_MEDIUM) {
                // Do not duplicate a placed feature for every whole-number frequency multiplier. With all
                // ores at 600%, that used to install six TINY and six SMALL feature copies per original
                // ore feature, making both world loading and chunk decoration grow without a useful bound.
                // The feature applies the multiplier to its retention gate instead, and its shared
                // per-chunk workload budget keeps an extreme preset responsive.
                addTierReplacement(
                        replacements, depositTargets, tier, chance, frequencyMultiplier,
                        sizeMultiplier, richnessMultiplier, tierPlacement, biome
                );
            } else {
                addTierReplacement(
                        replacements, depositTargets, tier, 1.0F, frequencyMultiplier,
                        sizeMultiplier, richnessMultiplier, tierPlacement, biome
                );
            }
        }
        return true;
    }

    static OreFeatureData oreFeatureData(FeatureConfiguration config) {
        if (config instanceof OreConfiguration oreConfiguration) {
            return new OreFeatureData(oreConfiguration.targetStates, oreConfiguration.size, oreConfiguration.discardChanceOnAirExposure);
        }

        List<OreConfiguration.TargetBlockState> targets = targetList(config, "targetStates");
        if (targets == null) {
            targets = targetList(config, "targetList");
        }
        if (targets == null || targets.isEmpty()) {
            return null;
        }

        return new OreFeatureData(targets, reflectedSize(config), reflectedDiscardChance(config));
    }

    private static float reflectedDiscardChance(FeatureConfiguration config) {
        Object value = reflectedValue(config, "discardChanceOnAirExposure");
        return value instanceof Number number ? clamp(number.floatValue(), 0.0F, 1.0F) : 0.0F;
    }

    @SuppressWarnings("unchecked")
    private static List<OreConfiguration.TargetBlockState> targetList(FeatureConfiguration config, String methodName) {
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
            return (List<OreConfiguration.TargetBlockState>)list;
        } catch (ReflectiveOperationException exception) {
            String key = config.getClass().getName() + "#" + methodName;
            if (isKnownOreConfig(config) && LOGGED_REFLECTION_FALLBACKS.add(key)) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: {}.{}() is unavailable; trying compatible fallbacks",
                        config.getClass().getName(),
                        methodName
                );
            }
            return null;
        }
    }

    private static int reflectedSize(FeatureConfiguration config) {
        Integer resolved = resolveSize(reflectedValue(config, "size"));
        if (resolved != null) {
            return resolved;
        }

        resolved = resolveSize(reflectedValue(config, "getSize"));
        return resolved != null ? resolved : 8;
    }

    /**
     * Some mods back their feature size with a lazy supplier tied to their own mod config (e.g. Mekanism's
     * {@code CachedIntValue}), which can throw if invoked before that config is loaded — notably during
     * the world-creation screen's preview scan, well before any world (and its configs) actually exist.
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
            Holder<Biome> biome
    ) {
        if (!OresAndDrillsMod.LOGGER.isTraceEnabled()) {
            return;
        }
        String targetIds = targets.stream()
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
                "Ore deposits: replacing feature {} config {} for targets [{}] in biome {} (worldgen-frequency weighted deposit tiers)",
                configured.feature().getClass().getName(),
                configured.config().getClass().getName(),
                targetIds,
                biome.unwrapKey().map(value -> value.location().toString()).orElse("<direct>")
        );
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

    private static List<PlacementModifier> mergedPlacement(List<PlacementModifier> parentPlacement, List<PlacementModifier> childPlacement) {
        if (childPlacement.isEmpty()) {
            return parentPlacement;
        }

        List<PlacementModifier> merged = new ArrayList<>(parentPlacement.size() + childPlacement.size());
        merged.addAll(parentPlacement);
        merged.addAll(childPlacement);
        return List.copyOf(merged);
    }

    private static ConfiguredFeature<OreDepositFeature.Configuration, OreDepositFeature> configuredDeposit(OreDepositFeature.Configuration config) {
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
            Holder<Biome> sourceBiome
    ) {
        Optional<ResourceLocation> sourceBiomeId = sourceBiome.unwrapKey().map(key -> key.location());
        OreDepositFeature.Configuration config = new OreDepositFeature.Configuration(
                targets, tier, sourceProbability, frequencyMultiplier,
                sizeMultiplier, richnessMultiplier, sourceBiomeId
        );
        replacements.add(new PlacedFeature(Holder.direct(configuredDeposit(config)), placement));
    }

    /** Candidate existence for every tier is owned by the common immutable cell planner. */
    private static List<PlacementModifier> deterministicDepositAttemptPlacement() {
        return List.of();
    }

    /**
     * MEDIUM/LARGE own one shared material lottery per cell. Several source features of the same unified
     * material contribute metadata, but must not each launch an independent physical candidate.
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

            String materialKey = OreUnifier.materialKeyFor(config.targets().getFirst().state.getBlock());
            IndependentTierKey key = new IndependentTierKey(config.sizeTier(), materialKey, config.sourceBiome());
            Integer existingIndex = firstByKey.get(key);
            if (existingIndex == null) {
                firstByKey.put(key, result.size());
                result.add(holder);
                continue;
            }

            PlacedFeature existing = result.get(existingIndex).value();
            OreDepositFeature.Configuration existingConfig =
                    (OreDepositFeature.Configuration) existing.feature().value().config();
            List<OreConfiguration.TargetBlockState> mergedTargets = new ArrayList<>(existingConfig.targets());
            for (OreConfiguration.TargetBlockState target : config.targets()) {
                if (!mergedTargets.contains(target)) {
                    mergedTargets.add(target);
                }
            }
            OreDepositFeature.Configuration mergedConfig = new OreDepositFeature.Configuration(
                    List.copyOf(mergedTargets),
                    existingConfig.sizeTier(),
                    existingConfig.sourceProbability(),
                    existingConfig.frequencyMultiplier(),
                    existingConfig.sizeMultiplier(),
                    existingConfig.richnessMultiplier(),
                    existingConfig.sourceBiome()
            );
            OreDepositFeature depositFeature = (OreDepositFeature) existing.feature().value().feature();
            ConfiguredFeature<OreDepositFeature.Configuration, OreDepositFeature> mergedFeature =
                    new ConfiguredFeature<>(depositFeature, mergedConfig);
            result.set(existingIndex, Holder.direct(new PlacedFeature(
                    Holder.direct(mergedFeature), existing.placement()
            )));
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

    private static boolean isDepositDisabled(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return false;
        }

        for (String configuredEntry : OreDepositConfig.DISABLED_DEPOSIT_ORES.get()) {
            if (configuredEntry.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(configuredEntry.substring(1));
                if (tagId != null && block.defaultBlockState().is(TagKey.create(Registries.BLOCK, tagId))) {
                    return true;
                }
            } else if (configuredEntry.equals(id.toString())) {
                return true;
            }
        }

        return false;
    }

    record OreFeatureData(List<OreConfiguration.TargetBlockState> targets, int size, float discardChanceOnAirExposure) {
    }

    private record Replacement(List<PlacedFeature> placedFeatures) {
        private static final Replacement KEEP = new Replacement(List.of());
        private static final Replacement REMOVE = new Replacement(List.of());
    }

    private record IndependentTierKey(int tier, String materialKey, Optional<ResourceLocation> sourceBiome) {
    }
}

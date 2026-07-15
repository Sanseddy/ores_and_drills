package dev.world.level.levelgen;

import dev.FactoryExpansionMod;
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
import net.minecraft.world.level.levelgen.placement.CountPlacement;
import net.minecraft.world.level.levelgen.placement.PlacementModifier;
import net.minecraft.world.level.levelgen.placement.PlacementModifierType;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.common.world.BiomeGenerationSettingsBuilder;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
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
        if (phase == Phase.AFTER_EVERYTHING) {
            addForcedAlexUraniumDeposits(generation);
        }

        for (GenerationStep.Decoration step : GenerationStep.Decoration.values()) {
            List<Holder<PlacedFeature>> features = generation.getFeatures(step);
            List<Holder<PlacedFeature>> replacements = new ArrayList<>();
            int removed = 0;

            for (Holder<PlacedFeature> holder : List.copyOf(features)) {
                Replacement replacement = replacementFor(holder.value(), biome);
                if (replacement == Replacement.KEEP) {
                    continue;
                }
                features.remove(holder);
                removed++;
                for (PlacedFeature placedFeature : replacement.placedFeatures()) {
                    replacements.add(Holder.direct(placedFeature));
                }
            }

            features.addAll(replacements);
            if (removed > 0) {
                FactoryExpansionMod.LOGGER.trace("Ore deposits: replaced {} ore feature(s) in biome {} step {}", removed, biome.unwrapKey(), step);
            }
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return ModWorldgen.REPLACE_ORE_FEATURES.get();
    }

    private static Replacement replacementFor(PlacedFeature placedFeature, Holder<Biome> biome) {
        List<PlacedFeature> replacements = new ArrayList<>();
        boolean foundOreFeature = collectOreReplacements(
                placedFeature.feature().value(),
                placedFeature.placement(),
                biome,
                1.0F,
                replacements,
                Collections.newSetFromMap(new IdentityHashMap<>())
        );

        if (!foundOreFeature) {
            return Replacement.KEEP;
        }

        return replacements.isEmpty() ? Replacement.REMOVE : new Replacement(replacements);
    }

    private static boolean collectOreReplacements(
            ConfiguredFeature<?, ?> configured,
            List<PlacementModifier> placement,
            Holder<Biome> biome,
            float chance,
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
                    configured, oreData, placement, biome, chance, replacements
            );
            visitedConfiguredFeatures.remove(configured);
            return result;
        }

        boolean foundOreFeature = false;
        if (configured.config() instanceof RandomFeatureConfiguration randomFeatureConfiguration) {
            float remainingChance = 1.0F;
            for (WeightedPlacedFeature weightedFeature : randomFeatureConfiguration.features) {
                float weightedChance = chance * remainingChance * weightedFeature.chance;
                remainingChance *= 1.0F - weightedFeature.chance;
                foundOreFeature |= collectOreReplacements(
                        weightedFeature.feature.value().feature().value(),
                        mergedPlacement(placement, weightedFeature.feature.value().placement()),
                        biome,
                        weightedChance,
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
                        replacements,
                        visitedConfiguredFeatures
                );
            }
            visitedConfiguredFeatures.remove(configured);
            return foundOreFeature;
        }

        for (ConfiguredFeature<?, ?> child : configured.config().getFeatures().toList()) {
            foundOreFeature |= collectOreReplacements(child, placement, biome, chance, replacements, visitedConfiguredFeatures);
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
            if (isUranium(sourceOre) && !canGenerateUraniumIn(biome)) {
                continue;
            }

            var canonical = OreUnifier.canonicalFor(sourceOre, classifyTarget(target.target));
            if (canonical.isEmpty()) {
                continue;
            }

            OreSpawnDimensions.record(sourceOre, biome);
            OreSpawnDimensions.record(canonical.get(), biome);
            OreGenerationWeights.recordObservation(canonical.get(), biome, configured, placement, chance, oreData.size());

            OreConfiguration.TargetBlockState unifiedTarget = OreConfiguration.target(target.target, canonical.get().defaultBlockState());

            ResourceLocation canonicalId = BuiltInRegistries.BLOCK.getKey(canonical.get());
            if (canonicalId == null || !OreDepositOrePalette.availableIds().contains(canonicalId)) {
                // The attachment stores an 8-bit ore-palette index. Never coerce an unrepresentable ore to
                // index zero: preserve its normal vein instead, so its texture, drop and /locate material
                // remain truthful even in a very large modpack.
                if (canonicalId != null && LOGGED_UNREPRESENTABLE_ORES.add(canonicalId)) {
                    FactoryExpansionMod.LOGGER.warn(
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
                    FactoryExpansionMod.LOGGER.info("Ore deposits: {} excluded from deposits by server config; keeping its original vein instead", oreId);
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
                FactoryExpansionMod.LOGGER.info(
                        "Ore deposits: disabled {} by datapack preset {}",
                        oreId,
                        OreSettingsPresetManager.activePresetId()
                );
            }
            return true;
        }

        // addForcedAlexUraniumDeposits installs the only forced feature, once for every biome at
        // AFTER_EVERYTHING. Re-adding MEDIUM/LARGE copies from Alex's source feature makes generation
        // order-dependent and wastes two placement attempts per toxic-caves chunk.
        boolean alexUraniumOnly = ModList.get().isLoaded("alexscaves")
                && !depositTargets.isEmpty()
                && depositTargets.stream().allMatch(target -> isUranium(target.state.getBlock()));
        if (alexUraniumOnly) {
            return true;
        }

        logReplacement(configured, depositTargets, biome);

        OreOverrides.OreOverride override = OreOverrides.lookupConfigured(firstCanonicalOre)
                .orElseGet(presetSettings::multipliers);
        float frequencyMultiplier = clamp(override.frequency(), 0.1F, 6.0F);
        float sizeMultiplier = override.size();
        float richnessMultiplier = override.richness();

        int firstTier = isUranium(firstCanonicalOre) && ModList.get().isLoaded("alexscaves")
                ? OreDepositFeature.TIER_LARGE
                : OreDepositFeature.TIER_TINY;
        for (int tier = firstTier; tier < OreDepositFeature.TIER_COUNT; tier++) {
            // TINY/SMALL retain Minecraft's source placement chain (count/rarity, in-square X/Z and
            // height distribution), while keeping the mod's own deposit-lens shape. MEDIUM/LARGE own a separate
            // deterministic region grid, so only they strip source attempt modifiers.
            List<PlacementModifier> tierPlacement = tier < OreDepositFeature.TIER_MEDIUM
                    ? placement
                    : singleDepositAttemptPlacement(placement);
            if (tier < OreDepositFeature.TIER_MEDIUM) {
                // Do not duplicate a placed feature for every whole-number frequency multiplier. With all
                // ores at 600%, that used to install six TINY and six SMALL feature copies per original
                // ore feature, making both world loading and chunk decoration grow without a useful bound.
                // The feature applies the multiplier to its retention gate instead, and its shared
                // per-chunk workload budget keeps an extreme preset responsive.
                addTierReplacement(
                        replacements, depositTargets, tier, chance, frequencyMultiplier,
                        sizeMultiplier, richnessMultiplier, tierPlacement, false
                );
            } else {
                addTierReplacement(
                        replacements, depositTargets, tier, 1.0F, frequencyMultiplier,
                        sizeMultiplier, richnessMultiplier, tierPlacement, false
                );
            }
        }
        return true;
    }

    private static Block canonicalUraniumOre() {
        return OreTags.oreBlocks().stream()
                .filter(ReplaceOreFeaturesBiomeModifier::isUranium)
                .map(block -> OreUnifier.canonicalFor(block).orElse(null))
                .filter(Objects::nonNull)
                .min(Comparator
                        .comparingInt(OreUnifier::modPriorityRank)
                        .thenComparing(block -> {
                            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
                            return id == null ? "" : id.toString();
                        }))
                .orElse(null);
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
                FactoryExpansionMod.LOGGER.trace(
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
        if (!FactoryExpansionMod.LOGGER.isTraceEnabled()) {
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

        FactoryExpansionMod.LOGGER.trace(
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
            boolean forcedAlexUranium
    ) {
        OreDepositFeature.Configuration config = new OreDepositFeature.Configuration(
                targets, tier, sourceProbability, frequencyMultiplier,
                sizeMultiplier, richnessMultiplier, forcedAlexUranium
        );
        replacements.add(new PlacedFeature(Holder.direct(configuredDeposit(config)), placement));
    }

    /**
     * MEDIUM/LARGE frequency is an independent rare-event budget calculated inside OreDepositFeature.
     * Keep spatial, height and biome filters, but strip source count/noise/rarity multipliers for those
     * tiers only. TINY/SMALL never call this method and retain the complete original placement chain.
     */
    private static List<PlacementModifier> singleDepositAttemptPlacement(List<PlacementModifier> placement) {
        return placement.stream()
                .filter(modifier -> {
                    PlacementModifierType<?> type = modifier.type();
                    return type != PlacementModifierType.COUNT
                            && type != PlacementModifierType.COUNT_ON_EVERY_LAYER
                            && type != PlacementModifierType.NOISE_BASED_COUNT
                            && type != PlacementModifierType.NOISE_THRESHOLD_COUNT
                            && type != PlacementModifierType.RARITY_FILTER;
                })
                .toList();
    }

    private static void addForcedAlexUraniumDeposits(BiomeGenerationSettingsBuilder generation) {
        if (!ModList.get().isLoaded("alexscaves")) {
            return;
        }

        Block uranium = canonicalUraniumOre();
        if (uranium == null || isDepositDisabled(uranium)) {
            return;
        }

        OreSettingsPresetManager.Settings presetSettings = OreSettingsPresetManager.resolve(uranium);
        if (!presetSettings.enabled()) {
            return;
        }

        OreOverrides.OreOverride override = OreOverrides.lookupConfigured(uranium)
                .orElseGet(presetSettings::multipliers);
        List<OreConfiguration.TargetBlockState> uraniumTargets = List.of(
                OreConfiguration.target(new TagMatchTest(OreDepositStonePalette.STONES), uranium.defaultBlockState())
        );
        List<Holder<PlacedFeature>> features = generation.getFeatures(GenerationStep.Decoration.UNDERGROUND_ORES);
        // One unconditional forced attempt per chunk; OreDepositFeature verifies the 3D Toxic Caves biome.
        OreDepositFeature.Configuration config = new OreDepositFeature.Configuration(
                uraniumTargets,
                OreDepositFeature.TIER_LARGE,
                1.0F,
                override.frequency(),
                override.size(),
                override.richness(),
                true
        );
        features.add(Holder.direct(new PlacedFeature(
                Holder.direct(configuredDeposit(config)),
                List.of(CountPlacement.of(1))
        )));
    }

    static boolean canGenerateUraniumIn(Holder<Biome> biome) {
        if (!ModList.get().isLoaded("alexscaves")) {
            return true;
        }

        return isAlexToxicCavesBiome(biome);
    }

    private static boolean isAlexToxicCavesBiome(Holder<Biome> biome) {
        if (!ModList.get().isLoaded("alexscaves")) {
            return false;
        }

        return biome.unwrapKey()
                .map(key -> key.location())
                .filter(location -> location.getNamespace().equals("alexscaves"))
                .map(ResourceLocation::getPath)
                .filter(path -> path.equals("toxic_caves") || path.contains("toxic_caves"))
                .isPresent();
    }

    static boolean isUranium(Block block) {
        ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return false;
        }

        String path = id.getPath();
        return path.contains("uranium") || path.contains("uraninite") || path.contains("uran");
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
}

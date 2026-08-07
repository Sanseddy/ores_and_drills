package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Collection;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class OreSpawnDimensions {
    private static final ConcurrentMap<ResourceLocation, EnumSet<SpawnDimension>> DIMENSIONS_BY_ORE = new ConcurrentHashMap<>();
    private static final ConcurrentMap<ResourceLocation, Set<ResourceLocation>> DIMENSION_IDS_BY_ORE = new ConcurrentHashMap<>();

    private OreSpawnDimensions() {
    }

    public static void record(Block ore, Holder<Biome> biome) {
        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (oreId == null) {
            return;
        }

        SpawnDimension dimension = dimensionFor(biome);
        DIMENSIONS_BY_ORE.compute(oreId, (ignored, existing) -> {
            EnumSet<SpawnDimension> dimensions = existing == null ? EnumSet.noneOf(SpawnDimension.class) : EnumSet.copyOf(existing);
            dimensions.add(dimension);
            return dimensions;
        });
    }

    public static void scanOriginalPlacedFeatures(RegistryAccess registryAccess) {
        Map<ResourceKey<LevelStem>, LevelStem> dimensions = registryAccess.registry(Registries.LEVEL_STEM)
                .map(registry -> Map.copyOf(registry.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue))))
                .orElseGet(Map::of);
        scanOriginalPlacedFeatures(registryAccess, dimensions);
    }

    public static void scanOriginalPlacedFeatures(
            RegistryAccess registryAccess,
            Registry<LevelStem> dimensions
    ) {
        scanOriginalPlacedFeatures(
                registryAccess,
                Map.copyOf(dimensions.entrySet().stream()
                        .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue)))
        );
    }

    private static void scanOriginalPlacedFeatures(
            RegistryAccess registryAccess,
            Map<ResourceKey<LevelStem>, LevelStem> dimensions
    ) {
        DIMENSIONS_BY_ORE.clear();
        DIMENSION_IDS_BY_ORE.clear();
        OreGenerationWeights.clear();
        List<BiomeModifier> biomeModifiers = biomeModifiersExcludingOwn(registryAccess);
        Map<ResourceLocation, Set<ResourceLocation>> dimensionIdsByBiome = dimensionIdsByBiome(dimensions);
        registryAccess.registry(Registries.BIOME).ifPresent(registry ->
                registry.holders().forEach(biomeHolder -> scanBiome(
                        biomeHolder,
                        biomeModifiers,
                        biomeHolder.unwrapKey()
                                .map(key -> dimensionIdsByBiome.getOrDefault(key.location(), Set.of()))
                                .orElseGet(Set::of)
                )));
    }

    /**
     * On a real running server, {@link ReplaceOreFeaturesBiomeModifier} already populates
     * {@link OreGenerationWeights} accurately (real chance/placement data) the moment biome modifiers
     * apply, well before any player can run a command. If that already happened, a rescan here would only
     * be a WORSE approximation (chance hard-coded to 1.0, no descent into weighted sub-features) and would
     * silently overwrite the good data — this previously caused {@code /locate} to reset (and further
     * corrupt) the weight table the very first time it was invoked in a session. Only fall back to the
     * scratch registry-wide scan when nothing has recorded anything yet (e.g. our own modifier hasn't run
     * for this registry, such as a preview registry that never triggers a real server's biome modifiers).
     */
    public static void ensureOriginalPlacedFeaturesScanned(RegistryAccess registryAccess) {
        if (!OreGenerationWeights.isEmpty()) {
            return;
        }

        synchronized (OreSpawnDimensions.class) {
            if (!OreGenerationWeights.isEmpty()) {
                return;
            }
            scanOriginalPlacedFeatures(registryAccess);
        }
    }

    /**
     * Most modded ores aren't baked into biome JSON like vanilla ores — they're injected at runtime via
     * {@link BiomeModifier}s (e.g. {@code AddFeaturesBiomeModifier}), which NeoForge only applies to an
     * actual running server's registry ({@code ServerLifecycleHooks.runModifiers}), never to the
     * world-creation screen's preview registry. Without this, only vanilla ores would show a dimension here.
     * <p>
     * Our own {@link ReplaceOreFeaturesBiomeModifier} is deliberately excluded: this scan wants the
     * *original* vanilla/modded ore features for display, and running our own modifier here would replace
     * them with deposit-lottery features before we ever get to look at them.
     */
    private static List<BiomeModifier> biomeModifiersExcludingOwn(RegistryAccess registryAccess) {
        return registryAccess.registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS)
                .holders()
                .map(Holder::value)
                .filter(modifier -> modifier != ReplaceOreFeaturesBiomeModifier.INSTANCE)
                .toList();
    }

    public static Set<SpawnDimension> dimensionsFor(Collection<ResourceLocation> oreIds) {
        EnumSet<SpawnDimension> result = EnumSet.noneOf(SpawnDimension.class);
        for (ResourceLocation oreId : oreIds) {
            Set<SpawnDimension> dimensions = DIMENSIONS_BY_ORE.get(oreId);
            if (dimensions != null) {
                result.addAll(dimensions);
            }
        }
        return Set.copyOf(result);
    }

    public static Set<ResourceLocation> dimensionIdsFor(Collection<ResourceLocation> oreIds) {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (ResourceLocation oreId : oreIds) {
            Set<ResourceLocation> dimensions = DIMENSION_IDS_BY_ORE.get(oreId);
            if (dimensions != null) {
                result.addAll(dimensions);
            }
        }
        return Set.copyOf(result);
    }

    private static Map<ResourceLocation, Set<ResourceLocation>> dimensionIdsByBiome(
            Map<ResourceKey<LevelStem>, LevelStem> dimensions
    ) {
        Map<ResourceLocation, Set<ResourceLocation>> mutable = new HashMap<>();
        for (Map.Entry<ResourceKey<LevelStem>, LevelStem> entry : dimensions.entrySet()) {
            ResourceLocation dimensionId = entry.getKey().location();
            try {
                for (Holder<Biome> biome : entry.getValue().generator().getBiomeSource().possibleBiomes()) {
                    biome.unwrapKey().ifPresent(key -> mutable
                            .computeIfAbsent(key.location(), ignored -> new LinkedHashSet<>())
                            .add(dimensionId));
                }
            } catch (RuntimeException exception) {
                OresAndDrillsMod.LOGGER.debug(
                        "Ore deposits: skipped dimension {} while mapping ore biomes",
                        dimensionId,
                        exception
                );
            }
        }

        Map<ResourceLocation, Set<ResourceLocation>> result = new HashMap<>();
        mutable.forEach((biomeId, dimensionIds) -> result.put(biomeId, Set.copyOf(dimensionIds)));
        return Map.copyOf(result);
    }

    private static void recordDimensionIds(Block ore, Collection<ResourceLocation> dimensionIds) {
        if (dimensionIds.isEmpty()) {
            return;
        }

        ResourceLocation oreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (oreId == null) {
            return;
        }

        DIMENSION_IDS_BY_ORE.compute(oreId, (ignored, existing) -> {
            Set<ResourceLocation> merged = new LinkedHashSet<>();
            if (existing != null) {
                merged.addAll(existing);
            }
            merged.addAll(dimensionIds);
            return Set.copyOf(merged);
        });
    }

    private static void scanBiome(
            Holder<Biome> biome,
            List<BiomeModifier> biomeModifiers,
            Set<ResourceLocation> dimensionIds
    ) {
        BiomeGenerationSettings generationSettings = simulatedGenerationSettings(biome, biomeModifiers);
        for (HolderSet<PlacedFeature> stepFeatures : generationSettings.features()) {
            for (Holder<PlacedFeature> placedFeature : stepFeatures) {
                scanPlacedFeatureSafely(placedFeature, biome, dimensionIds);
            }
        }
    }

    /**
     * Replays the same modifier loop {@code ModifiableBiomeInfo.applyBiomeModifiers} runs, but against a
     * scratch builder copied from the biome's ORIGINAL (unmodified) data — never touching
     * {@code biome.value().modifiableBiomeInfo()} itself. That real method is a use-once guard ("will do
     * nothing if this modifier had already been applied"): calling it here on the create-world screen's
     * registry would permanently mark every biome as already-modified before our own
     * {@link ReplaceOreFeaturesBiomeModifier} ever got a real chance to run later — since NeoForge reuses
     * this same registry/these same Biome instances for the actual world, that silently disabled all our
     * ore-deposit generation (this was tried and caused exactly that regression).
     */
    private static BiomeGenerationSettings simulatedGenerationSettings(Holder<Biome> biome, List<BiomeModifier> biomeModifiers) {
        ModifiableBiomeInfo.BiomeInfo original = biome.value().modifiableBiomeInfo().getOriginalBiomeInfo();
        ModifiableBiomeInfo.BiomeInfo.Builder builder = ModifiableBiomeInfo.BiomeInfo.Builder.copyOf(original);
        for (BiomeModifier.Phase phase : BiomeModifier.Phase.values()) {
            for (BiomeModifier modifier : biomeModifiers) {
                try {
                    modifier.modify(biome, phase, builder);
                } catch (RuntimeException exception) {
                    OresAndDrillsMod.LOGGER.debug("Ore deposits: skipped a biome modifier while scanning ore dimensions", exception);
                }
            }
        }
        return builder.getGenerationSettings().build();
    }

    /**
     * This reflectively pokes at arbitrary third-party {@code FeatureConfiguration} classes across every
     * biome; some (e.g. Mekanism's) back a value with a lazy supplier tied to their own mod config, which
     * can throw before that config is loaded (this preview scan runs on the create-world screen, before
     * any world/config exists). The scan is best-effort for a GUI hint, so one bad feature shouldn't crash it.
     */
    private static void scanPlacedFeatureSafely(
            Holder<PlacedFeature> placedFeature,
            Holder<Biome> biome,
            Set<ResourceLocation> dimensionIds
    ) {
        try {
            scanConfiguredFeature(
                    placedFeature.value().feature().value(),
                    placedFeature.value(),
                    ReplaceOreFeaturesBiomeModifier.stablePlacedFeatureKey(placedFeature),
                    biome,
                    dimensionIds
            );
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug("Ore deposits: skipped a feature while scanning ore dimensions", exception);
        }
    }

    private static void scanConfiguredFeature(
            ConfiguredFeature<?, ?> configured,
            PlacedFeature placedFeature,
            String sourceSignature,
            Holder<Biome> biome,
            Set<ResourceLocation> dimensionIds
    ) {
        ReplaceOreFeaturesBiomeModifier.OreFeatureData oreData = ReplaceOreFeaturesBiomeModifier.oreFeatureData(configured.config());
        if (oreData == null) {
            return;
        }

        for (OreConfiguration.TargetBlockState target : oreData.targets()) {
            if (!OreTags.isOre(target.state)) {
                continue;
            }

            Block sourceOre = target.state.getBlock();
            OreUnifier.canonicalFor(sourceOre, ReplaceOreFeaturesBiomeModifier.classifyTarget(target.target))
                    .ifPresent(canonical -> {
                        record(sourceOre, biome);
                        record(canonical, biome);
                        recordDimensionIds(sourceOre, dimensionIds);
                        recordDimensionIds(canonical, dimensionIds);
                        OreGenerationWeights.recordObservation(
                                canonical,
                                biome,
                                sourceSignature,
                                configured,
                                placedFeature.placement(),
                                1.0F,
                                oreData.size(),
                                target.target
                        );
                    });
        }
    }

    static SpawnDimension dimensionFor(Holder<Biome> biome) {
        if (biome.is(BiomeTags.IS_NETHER)) {
            return SpawnDimension.NETHER;
        }
        if (biome.is(BiomeTags.IS_END)) {
            return SpawnDimension.END;
        }
        if (biome.is(BiomeTags.IS_OVERWORLD)) {
            return SpawnDimension.OVERWORLD;
        }
        return SpawnDimension.UNKNOWN;
    }

    public enum SpawnDimension {
        OVERWORLD,
        NETHER,
        END,
        UNKNOWN
    }
}

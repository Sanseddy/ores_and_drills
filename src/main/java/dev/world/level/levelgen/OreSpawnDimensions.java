package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.BiomeGenerationSettings;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class OreSpawnDimensions {
    private static final ConcurrentMap<ResourceLocation, EnumSet<SpawnDimension>> DIMENSIONS_BY_ORE = new ConcurrentHashMap<>();

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
        DIMENSIONS_BY_ORE.clear();
        OreGenerationWeights.clear();
        List<BiomeModifier> biomeModifiers = biomeModifiersExcludingOwn(registryAccess);
        registryAccess.registry(Registries.BIOME).ifPresent(registry ->
                registry.holders().forEach(biomeHolder -> scanBiome(biomeHolder, biomeModifiers)));
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

    private static void scanBiome(Holder<Biome> biome, List<BiomeModifier> biomeModifiers) {
        BiomeGenerationSettings generationSettings = simulatedGenerationSettings(biome, biomeModifiers);
        for (HolderSet<PlacedFeature> stepFeatures : generationSettings.features()) {
            for (Holder<PlacedFeature> placedFeature : stepFeatures) {
                scanPlacedFeatureSafely(placedFeature, biome);
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
                    OresAndDrillsMod.LOGGER.trace("Ore deposits: skipped a biome modifier while scanning ore dimensions", exception);
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
    private static void scanPlacedFeatureSafely(Holder<PlacedFeature> placedFeature, Holder<Biome> biome) {
        try {
            scanConfiguredFeature(
                    placedFeature.value().feature().value(),
                    placedFeature.value(),
                    ReplaceOreFeaturesBiomeModifier.stablePlacedFeatureKey(placedFeature),
                    biome
            );
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.trace("Ore deposits: skipped a feature while scanning ore dimensions", exception);
        }
    }

    private static void scanConfiguredFeature(
            ConfiguredFeature<?, ?> configured,
            PlacedFeature placedFeature,
            String sourceSignature,
            Holder<Biome> biome
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

package dev.world.level.levelgen;

import dev.config.OreSettingsPresetManager;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.levelgen.placement.PlacedFeature;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Ore generation as it is after {@link ReplaceOreFeaturesBiomeModifier} swapped the original ore features for
 * deposits, for recipe viewers that read worldgen (EMI Ores). Those viewers see only registered placed
 * features, so on their own they list ore veins no biome generates any more and none of the deposits.
 * The server builds the summary from the observations recorded while features were replaced and syncs it.
 */
public final class OreGenerationSummary {
    private static volatile List<Entry> clientEntries = List.of();
    private static volatile List<ResourceLocation> clientReplacedFeatures = List.of();

    private OreGenerationSummary() {
    }

    /**
     * @param deposit whether the ore generates as deposits; an ore outside the deposit palette keeps
     *                ordinary veins of its canonical block (see {@link ReplaceOreFeaturesBiomeModifier})
     */
    public record Entry(ResourceLocation oreId, List<ResourceLocation> biomes, int minY, int maxY, boolean deposit) {
        public Entry {
            biomes = List.copyOf(biomes);
        }
    }

    /** One entry per enabled material that at least one biome generates, sorted by material. */
    public static List<Entry> entries() {
        Map<String, List<ResourceLocation>> biomes = OreGenerationWeights.biomesByMaterial();
        List<ResourceLocation> palette = OreDepositOrePalette.availableIds();
        Map<String, Entry> byMaterial = new TreeMap<>();
        for (OreSpawnDimensions.SpawnDimension dimension : OreSpawnDimensions.SpawnDimension.values()) {
            for (OreGenerationWeights.OreGenerationData data : OreGenerationWeights.dataFor(dimension)) {
                List<ResourceLocation> materialBiomes = biomes.get(data.materialKey());
                if (materialBiomes == null || materialBiomes.isEmpty() || !BuiltInRegistries.BLOCK.containsKey(data.oreId())) {
                    continue;
                }
                Block ore = BuiltInRegistries.BLOCK.get(data.oreId());
                if (ReplaceOreFeaturesBiomeModifier.isOreDisabled(ore) || !OreSettingsPresetManager.resolve(ore).enabled()) {
                    continue;
                }
                // A material spread over several dimensions shows the span of all of them.
                byMaterial.merge(data.materialKey(),
                        new Entry(data.oreId(), materialBiomes, data.minY(), data.maxY(), palette.contains(data.oreId())),
                        (first, other) -> new Entry(first.oreId(), first.biomes(), Math.min(first.minY(), other.minY()),
                                Math.max(first.maxY(), other.maxY()), first.deposit()));
            }
        }
        return List.copyOf(byMaterial.values());
    }

    /** Registered placed features that place a handled ore but are no longer attached to any biome. */
    public static List<ResourceLocation> replacedFeatures(RegistryAccess access) {
        Set<ResourceLocation> attached = new HashSet<>();
        access.registryOrThrow(Registries.BIOME).forEach(biome -> biome.getGenerationSettings().features()
                .forEach(step -> step.forEach(holder -> holder.unwrapKey()
                        .ifPresent(key -> attached.add(key.location())))));
        List<ResourceLocation> replaced = new ArrayList<>();
        access.registryOrThrow(Registries.PLACED_FEATURE).entrySet().forEach(entry -> {
            ResourceLocation id = entry.getKey().location();
            PlacedFeature feature = entry.getValue();
            if (!attached.contains(id) && feature.feature().isBound()
                    && ReplaceOreFeaturesBiomeModifier.placesHandledOre(feature.feature().value())) {
                replaced.add(id);
            }
        });
        replaced.sort(null);
        return List.copyOf(replaced);
    }

    public static void applyClient(List<Entry> entries, List<ResourceLocation> replacedFeatures) {
        clientEntries = List.copyOf(entries);
        clientReplacedFeatures = List.copyOf(replacedFeatures);
    }

    public static List<Entry> clientEntries() {
        return clientEntries;
    }

    public static List<ResourceLocation> clientReplacedFeatures() {
        return clientReplacedFeatures;
    }
}

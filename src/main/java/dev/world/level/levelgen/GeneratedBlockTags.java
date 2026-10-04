package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModBlockTags;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagEntry;
import net.minecraft.tags.TagLoader;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Adds the default block (the base rock) of every noise generator setting, including those defined inline by
 * dimensions, to {@code #c:stones} while block tags are loaded, so every tag check (ours and other mods')
 * sees it. {@code #ores_and_drills:ore_bases} includes {@code #c:stones}, so these rocks are also replaced
 * when deposits spawn. Generated entries go before the data pack entries, so a pack can still exclude a
 * block with a {@code remove}.
 */
public final class GeneratedBlockTags {
    private static final String SOURCE = OresAndDrillsMod.MOD_ID + " (generated)";
    private static final String BLOCK_TAG_DIRECTORY = Registries.tagsDirPath(Registries.BLOCK);
    /** Captured when a tag reload starts; worldgen registries are already loaded at that point. */
    private static volatile Set<ResourceLocation> noiseBaseStones = Set.of();

    private GeneratedBlockTags() {
    }

    public static void prepare(RegistryAccess registryAccess) {
        Set<ResourceLocation> stones = new LinkedHashSet<>();
        registryAccess.registry(Registries.NOISE_SETTINGS).ifPresent(registry -> registry.forEach(settings -> addBaseStone(stones, settings)));
        registryAccess.registry(Registries.LEVEL_STEM).ifPresent(registry -> registry.forEach(stem -> {
            if (stem.generator() instanceof NoiseBasedChunkGenerator generator) {
                addBaseStone(stones, generator.generatorSettings().value());
            }
        }));
        noiseBaseStones = Set.copyOf(stones);
        OresAndDrillsMod.LOGGER.debug("Ore deposits: base stones from noise settings are {}", stones);
    }

    public static void augment(String directory, Map<ResourceLocation, List<TagLoader.EntryWithSource>> tags) {
        if (!BLOCK_TAG_DIRECTORY.equals(directory)) {
            return;
        }

        prepend(tags, ModBlockTags.STONES.location(), noiseBaseStones);
        OresAndDrillsMod.LOGGER.debug(
                "Ore deposits: added {} noise base stone(s) to {}",
                noiseBaseStones.size(), ModBlockTags.STONES.location()
        );
    }

    private static void addBaseStone(Set<ResourceLocation> stones, NoiseGeneratorSettings settings) {
        BlockState state = settings.defaultBlock();
        if (state.isAir() || !state.getFluidState().isEmpty()) {
            return;
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (!id.getNamespace().equals(OresAndDrillsMod.MOD_ID)) {
            stones.add(id);
        }
    }

    private static void prepend(
            Map<ResourceLocation, List<TagLoader.EntryWithSource>> tags,
            ResourceLocation tag,
            Iterable<ResourceLocation> ids
    ) {
        List<TagLoader.EntryWithSource> generated = new ArrayList<>();
        for (ResourceLocation id : ids) {
            generated.add(new TagLoader.EntryWithSource(TagEntry.element(id), SOURCE));
        }
        if (!generated.isEmpty()) {
            tags.computeIfAbsent(tag, ignored -> new ArrayList<>()).addAll(0, generated);
        }
    }
}

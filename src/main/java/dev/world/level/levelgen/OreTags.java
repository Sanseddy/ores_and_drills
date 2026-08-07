package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModBlockTags;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

public final class OreTags {
    public static final TagKey<Block> ORES = ModBlockTags.ORES;
    private static Set<Block> cachedOreBlocks;

    private OreTags() {
    }

    public static boolean isOre(BlockState state) {
        return state.is(ORES) || state.getTags().anyMatch(OreTags::isOreTag);
    }

    public static Set<Block> oreBlocks() {
        if (cachedOreBlocks != null) {
            return cachedOreBlocks;
        }

        Set<Block> result = new LinkedHashSet<>();
        for (Holder<Block> holder : BuiltInRegistries.BLOCK.getTagOrEmpty(ORES)) {
            addExternalBlock(result, holder.value());
        }

        BuiltInRegistries.BLOCK.getTags().forEach(pair -> {
            if (!isOreTag(pair.getFirst())) {
                return;
            }

            for (Holder<Block> holder : pair.getSecond()) {
                addExternalBlock(result, holder.value());
            }
        });
        OresAndDrillsMod.LOGGER.debug("Ore deposits: found {} ore block(s)", result.size());
        OresAndDrillsMod.LOGGER.debug(
                "Ore deposits: ore blocks are {}",
                result.stream()
                        .map(BuiltInRegistries.BLOCK::getKey)
                        .filter(java.util.Objects::nonNull)
                        .map(ResourceLocation::toString)
                        .sorted()
                        .collect(Collectors.joining(", "))
        );
        cachedOreBlocks = Set.copyOf(result);
        return cachedOreBlocks;
    }

    public static void clearCache() {
        cachedOreBlocks = null;
    }

    private static boolean isOreTag(TagKey<Block> tag) {
        ResourceLocation location = tag.location();
        return isCommonTagNamespace(location.getNamespace())
                && (location.getPath().equals("ores") || location.getPath().startsWith("ores/"));
    }

    private static boolean isCommonTagNamespace(String namespace) {
        return namespace.equals("c") || namespace.equals("forge") || namespace.equals("neoforge");
    }

    private static void addExternalBlock(Set<Block> target, Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id != null && !id.getNamespace().equals(OresAndDrillsMod.MOD_ID)) {
            target.add(block);
        }
    }
}

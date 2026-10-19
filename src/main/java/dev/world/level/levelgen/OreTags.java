package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModBlockTags;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public final class OreTags {
    public static final TagKey<Block> ORES = ModBlockTags.ORES;
    private static Set<Block> cachedOreBlocks;

    private OreTags() {
    }

    public static boolean isOre(BlockState state) {
        return state.is(ORES) || state.getTags().anyMatch(OreTags::isOreTag) || isOreItem(state.getBlock());
    }

    /**
     * Ids of every ore tag of the block: its block tags, then the tags of its item. Some mods tag only the item
     * form of an ore block ({@code c:ores/uranium} as an item tag, as Create: Crowns does); the block is the same
     * ore for worldgen.
     */
    public static Stream<ResourceLocation> oreTagIds(Block block) {
        Stream<ResourceLocation> blockTags = block.builtInRegistryHolder().tags().map(TagKey::location);
        Item item = block.asItem();
        Stream<ResourceLocation> itemTags = item == Items.AIR
                ? Stream.empty()
                : item.builtInRegistryHolder().tags().map(TagKey::location);
        return Stream.concat(blockTags, itemTags).filter(OreTags::isOreTagId);
    }

    private static boolean isOreItem(Block block) {
        Item item = block.asItem();
        return item != Items.AIR && item.builtInRegistryHolder().tags().anyMatch(tag -> isOreTagId(tag.location()));
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
        BuiltInRegistries.ITEM.getTags().forEach(pair -> {
            if (!isOreTagId(pair.getFirst().location())) {
                return;
            }

            for (Holder<Item> holder : pair.getSecond()) {
                if (holder.value() instanceof BlockItem blockItem) {
                    addExternalBlock(result, blockItem.getBlock());
                }
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
        return isOreTagId(tag.location());
    }

    private static boolean isOreTagId(ResourceLocation location) {
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

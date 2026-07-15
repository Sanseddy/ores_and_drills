package dev.registry;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ModBlockTags {
    public static final TagKey<Block> ORES = common("ores");
    public static final TagKey<Block> STONES = common("stones");

    public static final TagKey<Block> ORE_BASES = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "ore_bases")
    );

    private ModBlockTags() {
    }

    private static TagKey<Block> common(String path) {
        return TagKey.create(Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("c", path));
    }
}

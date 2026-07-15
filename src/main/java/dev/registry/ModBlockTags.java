package dev.registry;

import dev.FactoryExpansionMod;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.level.block.Block;

public final class ModBlockTags {
    public static final TagKey<Block> ORE_BASES = TagKey.create(
            Registries.BLOCK,
            ResourceLocation.fromNamespaceAndPath(FactoryExpansionMod.MOD_ID, "ore_bases")
    );

    private ModBlockTags() {
    }
}

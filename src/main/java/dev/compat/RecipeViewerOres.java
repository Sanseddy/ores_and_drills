package dev.compat;

import dev.registry.ModRecipes;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.common.Tags;

/**
 * Ores as recipe viewers see them: the c:ores tags plus every block a drill recipe mines. JEI/EMI
 * tag pages for these blocks list dozens of generic tags and bury the drill recipe, so both
 * integrations hide tag recipes built from this set.
 */
public final class RecipeViewerOres {
    private RecipeViewerOres() {}

    public static Set<Block> blocks(RecipeManager recipes) {
        Set<Block> ores = new LinkedHashSet<>();
        BuiltInRegistries.BLOCK
                .getTagOrEmpty(Tags.Blocks.ORES)
                .forEach(holder -> ores.add(holder.value()));
        BuiltInRegistries.ITEM
                .getTagOrEmpty(Tags.Items.ORES)
                .forEach(
                        holder -> {
                            if (holder.value() instanceof BlockItem item) ores.add(item.getBlock());
                        });
        recipes.getAllRecipesFor(ModRecipes.DRILL_MINING.get())
                .forEach(
                        holder -> holder.value().input().forEach(block -> ores.add(block.value())));
        ores.remove(Blocks.AIR);
        return ores;
    }

    public static Set<Item> items(Set<Block> ores) {
        Set<Item> items = new HashSet<>();
        for (Block ore : ores) {
            Item item = ore.asItem();
            if (item != Items.AIR) items.add(item);
        }
        return items;
    }

    /** Every block and item tag any of the ores belongs to. */
    @SuppressWarnings("deprecation")
    public static Set<TagKey<?>> tags(Set<Block> ores) {
        Set<TagKey<?>> tags = new HashSet<>();
        for (Block ore : ores) {
            ore.builtInRegistryHolder().tags().forEach(tags::add);
            Item item = ore.asItem();
            if (item != Items.AIR) item.builtInRegistryHolder().tags().forEach(tags::add);
        }
        return tags;
    }
}

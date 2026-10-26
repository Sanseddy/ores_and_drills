package dev.recipe;

import dev.registry.ModBlocks;
import dev.registry.ModRecipes;
import dev.world.level.levelgen.OreDepositData;
import java.util.Collection;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * Index belongs to a RecipeManager snapshot; reloads replace the snapshot. No per-tick recipe
 * scans.
 */
public final class DrillMiningRecipes {
    private static final Map<RecipeManager, Index> INDEXES = new WeakHashMap<>();

    private record Index(
            Collection<RecipeHolder<?>> snapshot,
            List<RecipeHolder<DrillMiningRecipe>> recipes,
            Map<Block, List<RecipeHolder<DrillMiningRecipe>>> byBlock) {}

    private DrillMiningRecipes() {}

    public static BlockState sourceState(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(ModBlocks.ORE_DEPOSIT.get())) {
            Block ore = OreDepositData.oreBlockAt(level, pos);
            return ore == null ? state : ore.defaultBlockState();
        }
        return state;
    }

    public static DrillMiningRecipe find(ServerLevel level, BlockState source) {
        return find(level.getRecipeManager(), source);
    }

    public static DrillMiningRecipe find(RecipeManager manager, BlockState source) {
        List<RecipeHolder<DrillMiningRecipe>> recipes = matching(manager, source);
        return recipes.isEmpty() ? null : recipes.getFirst().value();
    }

    public static boolean requiresFluidOutsideDrills(ServerLevel level, BlockState source) {
        return requiresFluidOutsideDrills(level.getRecipeManager(), source);
    }

    /** Also used by client integrations with the server-synchronized recipe manager. */
    public static boolean requiresFluidOutsideDrills(RecipeManager manager, BlockState source) {
        // Any fluid recipe locks every mining but the drills', independent of current machine/world
        // conditions.
        return matching(manager, source).stream()
                .anyMatch(holder -> holder.value().requiresFluid());
    }

    public static synchronized void onTagsUpdated(TagsUpdatedEvent event) {
        INDEXES.clear();
        dev.world.block.entity.drill.OreScanner.clearDropRangeCache();
    }

    /** Selection order: the first accepting recipe in this order is the one a drill uses. */
    public static List<RecipeHolder<DrillMiningRecipe>> ordered(RecipeManager manager) {
        Comparator<RecipeHolder<DrillMiningRecipe>> order =
                Comparator.<RecipeHolder<DrillMiningRecipe>>comparingInt(
                                holder -> holder.value().priority())
                        .reversed()
                        .thenComparing(holder -> !holder.value().requiresFluid())
                        .thenComparing(holder -> holder.value().input().unwrapKey().isPresent())
                        .thenComparing(holder -> holder.id().toString());
        return manager.getAllRecipesFor(ModRecipes.DRILL_MINING.get()).stream()
                .sorted(order)
                .toList();
    }

    private static synchronized List<RecipeHolder<DrillMiningRecipe>> matching(
            RecipeManager manager, BlockState state) {
        Collection<RecipeHolder<?>> snapshot = manager.getRecipes();
        Index index = INDEXES.get(manager);
        if (index == null || index.snapshot != snapshot) {
            dev.world.block.entity.drill.OreScanner.clearDropRangeCache();
            index = new Index(snapshot, ordered(manager), new IdentityHashMap<>());
            INDEXES.put(manager, index);
        }
        Index current = index;
        return index.byBlock.computeIfAbsent(
                state.getBlock(),
                block ->
                        current.recipes.stream()
                                .filter(holder -> holder.value().accepts(state))
                                .toList());
    }
}

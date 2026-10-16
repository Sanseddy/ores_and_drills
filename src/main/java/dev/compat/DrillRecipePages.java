package dev.compat;

import dev.recipe.DrillMiningCondition;
import dev.recipe.DrillMiningFluid;
import dev.recipe.DrillMiningRecipe;
import dev.recipe.DrillMiningRecipes;
import net.minecraft.client.Minecraft;
import dev.world.level.levelgen.OreDepositOrePalette;
import net.minecraft.core.Holder;
import net.minecraft.core.HolderSet;
import net.minecraft.core.component.DataComponentPatch;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;

/** Drill recipes split into recipe viewer pages; shared by the JEI and EMI integrations. */
public final class DrillRecipePages {
    private DrillRecipePages() {}

    public static RecipeManager clientRecipes() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != null) return minecraft.level.getRecipeManager();
        var connection = minecraft.getConnection();
        return connection == null ? null : connection.getRecipeManager();
    }

    /** The first ore block of a page; merged pages share one output, so it stands for all of them. */
    public static ItemStack ore(DrillMiningRecipe page) {
        List<ItemStack> ores = ores(page);
        return ores.isEmpty() ? ItemStack.EMPTY : ores.getFirst();
    }

    /**
     * What the drill outputs for a page: the recipe's explicit result, otherwise the ore's loot drop.
     * Loot tables are server-only, so the drop comes from the ore palette the server syncs on login
     * (before recipes, hence before JEI/EMI build their pages). Unknown ores fall back to the block.
     */
    public static ItemStack output(DrillMiningRecipe page) {
        if (page.result().isPresent()) return page.result().get().copy();
        ItemStack ore = ore(page);
        if (ore.isEmpty()) return ItemStack.EMPTY;
        ResourceLocation dropId = page.input().stream().findFirst()
                .map(holder -> OreDepositOrePalette.dropIdForOre(BuiltInRegistries.BLOCK.getKey(holder.value())))
                .orElse(null);
        Item drop = dropId == null ? Items.AIR : BuiltInRegistries.ITEM.get(dropId);
        return drop == Items.AIR ? ore : new ItemStack(drop);
    }

    /**
     * A block can match several recipes (e.g. a specific acid recipe and the generic #c:ores one), but the
     * drill only ever uses the first one in {@link DrillMiningRecipes#ordered} order. Only that winner gets
     * pages for the block, so overridden fallbacks don't show up as extra recipes.
     */
    public static List<DrillMiningRecipe> pages(RecipeManager manager) {
        List<RecipeHolder<DrillMiningRecipe>> ordered = DrillMiningRecipes.ordered(manager);
        Map<Block, DrillMiningRecipe> winners = new IdentityHashMap<>();
        for (RecipeHolder<DrillMiningRecipe> holder : ordered) {
            holder.value().input().forEach(block -> winners.putIfAbsent(block.value(), holder.value()));
        }
        return merge(ordered.stream()
                .flatMap(holder -> pages(holder.value(), block -> winners.get(block) == holder.value()).stream())
                .toList());
    }

    /** Everything a page shows except its ore; pages with equal keys look identical apart from the ore. */
    private record MergeKey(Item item, DataComponentPatch components, int count, List<List<Object>> fluids,
                            int fluidAmount, int tier, List<DrillMiningCondition> conditions, boolean explicitResult) {
        static MergeKey of(DrillMiningRecipe page) {
            ItemStack output = output(page);
            List<List<Object>> fluids = page.fluid().map(fluid -> fluid.displayStacks().stream()
                    .map(stack -> List.<Object>of(stack.getFluid(), stack.getComponentsPatch())).toList())
                    .orElse(List.of());
            return new MergeKey(output.getItem(), output.getComponentsPatch(), output.getCount(), fluids,
                    page.fluid().map(DrillMiningFluid::amount).orElse(0), page.minimumDrillTier(),
                    page.conditions(), page.result().isPresent());
        }
    }

    /**
     * Ore variants that end up with the same drop, fluid, tier and conditions (stone/deepslate ores, or
     * several ores of one tag) are one recipe to the player: they share a page with the ores cycling.
     */
    private static List<DrillMiningRecipe> merge(List<DrillMiningRecipe> pages) {
        Map<MergeKey, List<DrillMiningRecipe>> groups = new LinkedHashMap<>();
        for (DrillMiningRecipe page : pages) {
            groups.computeIfAbsent(MergeKey.of(page), key -> new ArrayList<>()).add(page);
        }
        return groups.values().stream().map(group -> {
            DrillMiningRecipe first = group.getFirst();
            if (group.size() == 1) return first;
            List<Holder<Block>> ores = group.stream().flatMap(page -> page.input().stream()).toList();
            return new DrillMiningRecipe(HolderSet.direct(ores), first.fluid(), first.minimumDrillTier(),
                    first.conditions(), first.priority(), first.result());
        }).toList();
    }

    /** Every ore block a (possibly merged) page covers. */
    public static List<ItemStack> ores(DrillMiningRecipe page) {
        return page.input().stream().map(holder -> new ItemStack(holder.value()))
                .filter(stack -> !stack.isEmpty()).toList();
    }

    /**
     * Recipe viewers treat every stack in one slot as interchangeable alternatives. Passing a complete
     * ore tag there made the recipe of one ore visibly cycle through every ore installed in the pack and
     * made drill usages look like a broken tag dump. Runtime recipes remain tag based; only their view
     * is split into one concrete input per page so recipe focus and navigation stay meaningful.
     */
    private static List<DrillMiningRecipe> pages(DrillMiningRecipe recipe, Predicate<Block> effective) {
        List<Optional<DrillMiningFluid>> fluids = new ArrayList<>();
        if (recipe.fluid().isEmpty()) {
            fluids.add(Optional.empty());
        } else {
            DrillMiningFluid requirement = recipe.fluid().orElseThrow();
            List<FluidStack> visible = requirement.displayStacks();
            if (visible.size() <= 1 || requirement.hasSameNamedAlternatives()) {
                fluids.add(recipe.fluid());
            } else {
                // Different player-facing liquids get separate recipes instead of one rotating tank.
                visible.forEach(stack -> fluids.add(Optional.of(DrillMiningFluid.displayVariant(stack))));
            }
        }
        List<DrillMiningRecipe> pages = new ArrayList<>();
        recipe.input().stream()
                .filter(holder -> !new ItemStack(holder.value()).isEmpty() && effective.test(holder.value()))
                .forEach(holder -> fluids.forEach(fluid -> pages.add(new DrillMiningRecipe(
                        HolderSet.direct(holder), fluid, recipe.minimumDrillTier(),
                        recipe.conditions(), recipe.priority(), recipe.result()))));
        return List.copyOf(pages);
    }
}

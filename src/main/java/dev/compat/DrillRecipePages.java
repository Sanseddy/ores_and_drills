package dev.compat;

import dev.drill.AdvancedMiningDrill;
import dev.drill.BurnerMiningDrill;
import dev.drill.ElectricMiningDrill;
import dev.drill.UltimateMiningDrill;
import dev.recipe.DrillMiningCondition;
import dev.recipe.DrillMiningFluid;
import dev.recipe.DrillMiningRecipe;
import dev.recipe.DrillMiningRecipes;
import dev.registry.ModBlocks;
import dev.world.block.MiningDrillTier;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
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
import net.minecraft.world.level.ItemLike;
import net.minecraft.world.level.block.Block;
import net.neoforged.neoforge.fluids.FluidStack;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.function.Supplier;

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
                .filter(holder -> !new ItemStack(holder.value()).isEmpty() && effective.test(holder.value())
                        && !isDepositContainer(holder.value()))
                .forEach(holder -> {
                    int tier = Math.max(recipe.minimumDrillTier(), lowestHarvestingTier(holder.value()));
                    fluids.forEach(fluid -> pages.add(new DrillMiningRecipe(
                            HolderSet.direct(holder), fluid, tier,
                            recipe.conditions(), recipe.priority(), recipe.result())));
                });
        return List.copyOf(pages);
    }

    /**
     * The deposit blocks sit in c:ores for other mods, but they only hold a real ore that has its own page;
     * drills mine that ore, so a page for the container itself is a meaningless duplicate.
     */
    private static boolean isDepositContainer(Block block) {
        return block == ModBlocks.ORE_DEPOSIT.get() || block == ModBlocks.EXHAUSTED_ORE_DEPOSIT.get();
    }

    /** A standard drill and its item; listed in ascending recipe level. */
    private record Drill(MiningDrillTier tier, Supplier<? extends ItemLike> item) {}

    private static final List<Drill> DRILLS = List.of(
            new Drill(BurnerMiningDrill.TIER, ModBlocks.BURNER_MINING_DRILL_ITEM),
            new Drill(ElectricMiningDrill.TIER, ModBlocks.ELECTRIC_MINING_DRILL_ITEM),
            new Drill(AdvancedMiningDrill.TIER, ModBlocks.ADVANCED_MINING_DRILL_ITEM),
            new Drill(UltimateMiningDrill.TIER, ModBlocks.ULTIMATE_MINING_DRILL_ITEM));

    private static final int MAX_DRILL_LEVEL = DRILLS.stream().mapToInt(drill -> drill.tier().recipeLevel()).max().orElse(1);

    /**
     * Drills also skip ores their representative pickaxe cannot harvest (a burner drill's stone pickaxe
     * cannot get diamonds), so the page shows the first drill tier that can actually mine the ore.
     */
    private static int lowestHarvestingTier(Block ore) {
        return DRILLS.stream().map(Drill::tier).filter(tier -> tier.canHarvest(ore.defaultBlockState()))
                .mapToInt(MiningDrillTier::recipeLevel).min().orElse(MAX_DRILL_LEVEL + 1);
    }

    /**
     * Drills able to mine a page, weakest first. Recipe viewers list workstations per category, so the page
     * shows these as its own catalyst slots instead of the category listing every drill.
     */
    public static List<ItemStack> drills(DrillMiningRecipe page) {
        return DRILLS.stream().filter(drill -> drill.tier().recipeLevel() >= page.minimumDrillTier())
                .map(drill -> new ItemStack(drill.item().get())).toList();
    }

    /** Y of the first text line: below the drill slots, or in their place when no drill can mine the page. */
    public static int infoTop(DrillMiningRecipe page) {
        return drills(page).isEmpty() ? 96 : 116;
    }

    /** Text below the recipe artwork, one component per line. */
    public static List<Component> infoLines(DrillMiningRecipe page) {
        List<Component> lines = new ArrayList<>();
        // No standard drill can mine the page, so there are no drill slots to show the requirement.
        if (page.minimumDrillTier() > MAX_DRILL_LEVEL) {
            lines.add(Component.translatable("recipe.ores_and_drills.minimum_tier", page.minimumDrillTier()));
        }
        if (!page.conditions().isEmpty()) {
            lines.add(Component.translatable("recipe.ores_and_drills.conditions", page.conditions().size()));
        }
        return List.copyOf(lines);
    }
}

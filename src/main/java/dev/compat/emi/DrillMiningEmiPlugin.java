package dev.compat.emi;

import dev.OresAndDrillsMod;
import dev.compat.DrillRecipePages;
import dev.compat.RecipeViewerOres;
import dev.emi.emi.api.EmiEntrypoint;
import dev.emi.emi.api.EmiPlugin;
import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.neoforge.NeoForgeEmiStack;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.recipe.DrillMiningFluid;
import dev.recipe.DrillMiningRecipe;
import dev.registry.ModBlocks;
import dev.world.block.entity.AbstractDrillBlockEntity;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Native EMI integration. EMI's JEMI bridge skips the JEI plugin of any mod that ships an EMI plugin, so
 * the drill category is registered here directly with the same layout as the JEI category.
 * <p>
 * JEMI also copies JEI's "Block Tags"/"Item Tags" categories (minecraft:tag_recipes/*) without JEI's hidden
 * state; tag pages containing an ore are removed so an ore's recipe view goes straight to the drill recipe.
 */
@EmiEntrypoint
public final class DrillMiningEmiPlugin implements EmiPlugin {
    private static final ResourceLocation DRILL_JEI = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID, "textures/gui/jei/drill_jei.png");
    private static final ResourceLocation DRILL_ICON = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID, "textures/gui/drill_gui.png");
    private static final ResourceLocation MINING_PROGRESS = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID, "textures/gui/progress_2.png");
    private static final EmiRecipeCategory CATEGORY = new EmiRecipeCategory(
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "drill_mining"),
            EmiStack.of(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get()));

    @Override
    public void register(EmiRegistry registry) {
        registry.addCategory(CATEGORY);
        registry.addWorkstation(CATEGORY, EmiStack.of(ModBlocks.BURNER_MINING_DRILL_ITEM.get()));
        registry.addWorkstation(CATEGORY, EmiStack.of(ModBlocks.ELECTRIC_MINING_DRILL_ITEM.get()));
        registry.addWorkstation(CATEGORY, EmiStack.of(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get()));
        registry.addWorkstation(CATEGORY, EmiStack.of(ModBlocks.ULTIMATE_MINING_DRILL_ITEM.get()));

        RecipeManager manager = registry.getRecipeManager();
        List<DrillMiningRecipe> pages = DrillRecipePages.pages(manager);
        pages.forEach(page -> registry.addRecipe(new DrillRecipe(page)));
        OresAndDrillsMod.LOGGER.info("EMI: {} drill recipe pages", pages.size());

        Set<Item> ores = RecipeViewerOres.items(RecipeViewerOres.blocks(manager));
        if (ores.isEmpty()) return;
        registry.removeRecipes(recipe -> isTagRecipe(recipe) && recipe.getOutputs().stream()
                .anyMatch(stack -> ores.contains(stack.getItemStack().getItem())));
    }

    private static boolean isTagRecipe(EmiRecipe recipe) {
        EmiRecipeCategory category = recipe.getCategory();
        return category != null && category.getId() != null && category.getId().getPath().startsWith("tag_recipes/");
    }

    private static final class DrillRecipe implements EmiRecipe {
        private final DrillMiningRecipe recipe;
        private final EmiIngredient ore;
        private final EmiIngredient fluid;
        private final EmiStack output;
        private final List<EmiIngredient> inputs = new ArrayList<>();
        private final List<EmiStack> outputs = new ArrayList<>();

        DrillRecipe(DrillMiningRecipe recipe) {
            this.recipe = recipe;
            this.ore = EmiIngredient.of(DrillRecipePages.ores(recipe).stream().map(EmiStack::of).toList());
            this.fluid = recipe.fluid()
                    .map(requirement -> EmiIngredient.of(requirement.displayStacks().stream()
                            .map(NeoForgeEmiStack::of).toList(), requirement.amount()))
                    .orElse(EmiStack.EMPTY);
            this.output = EmiStack.of(DrillRecipePages.output(recipe));
            inputs.add(ore);
            if (!fluid.isEmpty()) inputs.add(fluid);
            // Outputs drive the "Recipes" key (R) lookup; the ores are listed too so R on an ore finds
            // drill mining as well as R on its drop. Widgets below decide what is actually drawn.
            if (!output.isEmpty()) outputs.add(output);
            ore.getEmiStacks().forEach(outputs::add);
        }

        @Override public EmiRecipeCategory getCategory() { return CATEGORY; }
        @Override public ResourceLocation getId() { return null; }
        @Override public List<EmiIngredient> getInputs() { return inputs; }
        @Override public List<EmiStack> getOutputs() { return outputs; }
        @Override public int getDisplayWidth() { return 142; }
        @Override public int getDisplayHeight() { return 116; }
        @Override public boolean supportsRecipeTree() { return false; }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            // drill_jei.png content occupies u 0..141, v 0..91. EMI slots are 18x18 with the stack drawn
            // 1px inside, so each slot sits 1px up-left of the JEI coordinate; the texture draws the frames.
            widgets.addTexture(DRILL_JEI, 0, 0, 142, 92, 0, 0);
            widgets.addDrawable(0, 0, 142, 92, (gui, mouseX, mouseY, delta) -> {
                // Same animated drill artwork and progress bar used by the actual machine screen.
                int frame = (int) ((net.minecraft.Util.getMillis() / 100L)
                        % AbstractDrillBlockEntity.GUI_ANIMATION_FRAME_COUNT);
                gui.blit(DRILL_ICON, 45, 3, 0, frame * 66, 46, 66, 46, 330);
                gui.blit(MINING_PROGRESS, 37, 77, 0, 0, 64, 3, 64, 3);
            });
            if (!fluid.isEmpty()) {
                var tank = widgets.addTank(fluid, 0, 0, 16, 92, (int) Math.max(1, fluid.getAmount()))
                        .drawBack(false);
                recipe.fluid().filter(DrillMiningFluid::hasSameNamedAlternatives)
                        .filter(requirement -> !requirement.displayStacks().isEmpty())
                        .ifPresent(requirement -> tank.appendTooltip(Component.translatable(
                                "recipe.ores_and_drills.any_fluid", requirement.displayStacks().getFirst().getHoverName())));
            }
            // Scanned ore preview at the top-right, mined result (explicit result or loot drop) below.
            widgets.addSlot(ore, 106, 0).drawBack(false);
            widgets.addSlot(output, 106, 74).drawBack(false).recipeContext(this);

            widgets.addText(Component.translatable("recipe.ores_and_drills.minimum_tier", recipe.minimumDrillTier()),
                    1, 96, 0x404040, false);
            if (recipe.result().isEmpty()) widgets.addText(Component.translatable("recipe.ores_and_drills.ore_loot"),
                    1, 107, 0x404040, false);
            if (!recipe.conditions().isEmpty()) widgets.addText(Component.translatable(
                    "recipe.ores_and_drills.conditions", recipe.conditions().size()), 71, 96, 0x404040, false);
        }
    }
}

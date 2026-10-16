package dev.compat.jei;

import dev.OresAndDrillsMod;
import dev.compat.DrillRecipePages;
import dev.compat.RecipeViewerOres;
import dev.recipe.DrillMiningRecipe;
import dev.registry.ModBlocks;
import dev.world.block.entity.AbstractDrillBlockEntity;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.crafting.RecipeManager;

import java.util.List;
import java.util.Set;

@JeiPlugin
public final class DrillMiningJeiPlugin implements IModPlugin {
    private static final RecipeType<DrillMiningRecipe> TYPE = RecipeType.create("ores_and_drills", "drill_mining", DrillMiningRecipe.class);
    private static final ResourceLocation DRILL_JEI = ResourceLocation.fromNamespaceAndPath(
            "ores_and_drills", "textures/gui/jei/drill_jei.png");
    private static final ResourceLocation DRILL_ICON = ResourceLocation.fromNamespaceAndPath(
            "ores_and_drills", "textures/gui/drill_gui.png");
    private static final ResourceLocation MINING_PROGRESS = ResourceLocation.fromNamespaceAndPath(
            "ores_and_drills", "textures/gui/progress_2.png");

    @Override
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath("ores_and_drills", "drill_mining"); }
    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new Category(registration.getJeiHelpers().getGuiHelper()));
    }
    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // With EMI installed this plugin is skipped (our mod has a native EMI plugin), see DrillMiningEmiPlugin.
        RecipeManager manager = DrillRecipePages.clientRecipes();
        if (manager == null) {
            OresAndDrillsMod.LOGGER.warn("JEI: no client recipe manager, drill recipes not registered");
            return;
        }
        List<DrillMiningRecipe> pages = DrillRecipePages.pages(manager);
        OresAndDrillsMod.LOGGER.info("JEI: {} drill recipe pages", pages.size());
        registration.addRecipes(TYPE, pages);
    }

    /**
     * JEI's built-in "Block Tags"/"Item Tags" categories (minecraft:tag_recipes/*) list every tag an ore
     * belongs to, which buried the drill recipe behind pages of tag dumps. Tag recipes containing any ore
     * are hidden so an ore's recipe view only shows meaningful categories.
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        RecipeManager manager = DrillRecipePages.clientRecipes();
        if (manager == null) {
            OresAndDrillsMod.LOGGER.warn("JEI: no client recipe manager, ore tag recipes not hidden");
            return;
        }
        // JEI's tag recipe class is internal, so the tag is read through its public getTag() accessor;
        // looking recipes up by item focus missed them.
        Set<TagKey<?>> oreTags = RecipeViewerOres.tags(RecipeViewerOres.blocks(manager));
        if (oreTags.isEmpty()) {
            OresAndDrillsMod.LOGGER.warn("JEI: no ore tags found, ore tag recipes not hidden");
            return;
        }

        IRecipeManager recipes = runtime.getRecipeManager();
        int hidden = recipes.createRecipeCategoryLookup().includeHidden().get()
                .map(IRecipeCategory::getRecipeType)
                .filter(type -> type.getUid().getPath().startsWith("tag_recipes/"))
                .mapToInt(type -> hideTagRecipes(recipes, type, oreTags))
                .sum();
        OresAndDrillsMod.LOGGER.info("Hid {} JEI tag recipes containing ores", hidden);
    }

    private static <T> int hideTagRecipes(IRecipeManager recipes, RecipeType<T> type, Set<TagKey<?>> oreTags) {
        List<T> hidden = recipes.createRecipeLookup(type).get()
                .filter(recipe -> oreTags.contains(tagOf(recipe)))
                .toList();
        if (!hidden.isEmpty()) recipes.hideRecipes(type, hidden);
        return hidden.size();
    }

    private static TagKey<?> tagOf(Object recipe) {
        try {
            return recipe.getClass().getMethod("getTag").invoke(recipe) instanceof TagKey<?> tag ? tag : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(ModBlocks.BURNER_MINING_DRILL_ITEM.get()), TYPE);
        registration.addRecipeCatalyst(new ItemStack(ModBlocks.ELECTRIC_MINING_DRILL_ITEM.get()), TYPE);
        registration.addRecipeCatalyst(new ItemStack(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get()), TYPE);
        registration.addRecipeCatalyst(new ItemStack(ModBlocks.ULTIMATE_MINING_DRILL_ITEM.get()), TYPE);
    }

    private static final class Category implements IRecipeCategory<DrillMiningRecipe> {
        private final IDrawable icon;
        private final IDrawable background;
        Category(IGuiHelper gui) {
            icon = gui.createDrawableItemLike(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get());
            // drill_jei.png content occupies u 0..141, v 0..91; slot coordinates below are texture-relative.
            background = gui.createDrawable(DRILL_JEI, 0, 0, 142, 92);
        }
        public RecipeType<DrillMiningRecipe> getRecipeType() { return TYPE; }
        public Component getTitle() { return Component.translatable("recipe.ores_and_drills.drill_mining"); }
        public IDrawable getIcon() { return icon; }
        public IDrawable getBackground() { return background; }
        public int getWidth() { return 142; }
        public int getHeight() { return 116; }
        public void setRecipe(IRecipeLayoutBuilder builder, DrillMiningRecipe recipe, IFocusGroup focuses) {
            // Coordinates mirror AbstractEnergyDrillMenu: scanned ore preview at the top-right,
            // mining fluid in the left tank and the mined result (explicit result or the ore's loot
            // drop) in the lower-right output slot.
            builder.addSlot(RecipeIngredientRole.INPUT, 107, 1).addItemStacks(DrillRecipePages.ores(recipe));
            builder.addSlot(RecipeIngredientRole.OUTPUT, 107, 75).addItemStack(DrillRecipePages.output(recipe));
            // The ore is also an invisible output, so the "Recipes" key (R) finds drill mining both on the
            // ore and on its drop.
            builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT).addItemStacks(DrillRecipePages.ores(recipe));
            recipe.fluid().ifPresent(fluid -> {
                var slot = builder.addSlot(RecipeIngredientRole.INPUT, 1, 1)
                        .setFluidRenderer(Math.max(1, fluid.amount()), false, 14, 90);
                if (fluid.hasSameNamedAlternatives() && !fluid.displayStacks().isEmpty()) {
                    Component name = fluid.displayStacks().getFirst().getHoverName();
                    slot.addRichTooltipCallback((view, tooltip) -> tooltip.add(
                            Component.translatable("recipe.ores_and_drills.any_fluid", name)));
                }
                for (var stack : fluid.displayStacks()) {
                    slot.addFluidStack(stack.getFluid(), stack.getAmount(), stack.getComponentsPatch());
                }
            });
        }
        @Override
        public void draw(DrillMiningRecipe recipe, IRecipeSlotsView slots, GuiGraphics gui, double mouseX, double mouseY) {
            var font = Minecraft.getInstance().font;
            // Same animated drill artwork and progress bar used by the actual machine screen.
            int frame = (int) ((net.minecraft.Util.getMillis() / 100L)
                    % AbstractDrillBlockEntity.GUI_ANIMATION_FRAME_COUNT);
            gui.blit(DRILL_ICON, 45, 3, 0, frame * 66, 46, 66, 46, 330);
            gui.blit(MINING_PROGRESS, 37, 77, 0, 0, 64, 3, 64, 3);
            gui.drawString(font, Component.translatable("recipe.ores_and_drills.minimum_tier", recipe.minimumDrillTier()), 1, 96, 0x404040, false);
            if (recipe.result().isEmpty()) gui.drawString(font, Component.translatable("recipe.ores_and_drills.ore_loot"), 1, 107, 0x404040, false);
            if (!recipe.conditions().isEmpty()) gui.drawString(font,
                    Component.translatable("recipe.ores_and_drills.conditions", recipe.conditions().size()), 71, 96, 0x404040, false);
        }
    }
}

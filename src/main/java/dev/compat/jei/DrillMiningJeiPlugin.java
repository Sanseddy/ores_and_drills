package dev.compat.jei;

import dev.OresAndDrillsMod;
import dev.client.AbstractEnergyDrillScreen;
import dev.compat.DrillRecipePages;
import dev.compat.RecipeViewerOres;
import dev.recipe.DrillMiningRecipe;
import dev.registry.ModBlocks;
import dev.world.block.entity.AbstractDrillBlockEntity;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.neoforge.NeoForgeTypes;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.IRecipeManager;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeManager;

@JeiPlugin
public final class DrillMiningJeiPlugin implements IModPlugin {
    private static final RecipeType<DrillMiningRecipe> TYPE =
            RecipeType.create("ores_and_drills", "drill_mining", DrillMiningRecipe.class);
    private static final ResourceLocation DRILL_JEI =
            ResourceLocation.fromNamespaceAndPath(
                    "ores_and_drills", "textures/gui/jei/drill_jei.png");
    private static final ResourceLocation DRILL_ICON =
            ResourceLocation.fromNamespaceAndPath("ores_and_drills", "textures/gui/drill_gui.png");
    private static final ResourceLocation MINING_PROGRESS =
            ResourceLocation.fromNamespaceAndPath("ores_and_drills", "textures/gui/progress_2.png");

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("ores_and_drills", "drill_mining");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new Category(registration.getJeiHelpers().getGuiHelper()));
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        // With EMI installed this plugin is skipped (our mod has a native EMI plugin), see
        // DrillMiningEmiPlugin.
        RecipeManager manager = DrillRecipePages.clientRecipes();
        if (manager == null) {
            OresAndDrillsMod.LOGGER.warn(
                    "JEI: no client recipe manager, drill recipes not registered");
            return;
        }
        List<DrillMiningRecipe> pages = DrillRecipePages.pages(manager);
        OresAndDrillsMod.LOGGER.info("JEI: {} drill recipe pages", pages.size());
        registration.addRecipes(TYPE, pages);
    }

    /**
     * JEI's built-in "Block Tags"/"Item Tags" categories (minecraft:tag_recipes/*) list every tag
     * an ore belongs to, which buries the drill recipe behind pages of tag dumps. Tag recipes
     * containing any ore are hidden so an ore's recipe view only shows meaningful categories.
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime runtime) {
        RecipeManager manager = DrillRecipePages.clientRecipes();
        if (manager == null) {
            OresAndDrillsMod.LOGGER.warn(
                    "JEI: no client recipe manager, ore tag recipes not hidden");
            return;
        }
        // JEI's tag recipe class is internal, so the tag is read through its public getTag()
        // accessor. Looking the recipes up by item focus does not find them.
        Set<TagKey<?>> oreTags = RecipeViewerOres.tags(RecipeViewerOres.blocks(manager));
        if (oreTags.isEmpty()) {
            OresAndDrillsMod.LOGGER.warn("JEI: no ore tags found, ore tag recipes not hidden");
            return;
        }

        IRecipeManager recipes = runtime.getRecipeManager();
        int hidden =
                recipes.createRecipeCategoryLookup()
                        .includeHidden()
                        .get()
                        .map(IRecipeCategory::getRecipeType)
                        .filter(type -> type.getUid().getPath().startsWith("tag_recipes/"))
                        .mapToInt(type -> hideTagRecipes(recipes, type, oreTags))
                        .sum();
        OresAndDrillsMod.LOGGER.info("Hid {} JEI tag recipes containing ores", hidden);
    }

    private static <T> int hideTagRecipes(
            IRecipeManager recipes, RecipeType<T> type, Set<TagKey<?>> oreTags) {
        List<T> hidden =
                recipes.createRecipeLookup(type)
                        .get()
                        .filter(recipe -> oreTags.contains(tagOf(recipe)))
                        .toList();
        if (!hidden.isEmpty()) recipes.hideRecipes(type, hidden);
        return hidden.size();
    }

    private static TagKey<?> tagOf(Object recipe) {
        try {
            return recipe.getClass().getMethod("getTag").invoke(recipe) instanceof TagKey<?> tag
                    ? tag
                    : null;
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        // Lets the recipe (R) and usage (U) keys work on the fluid in a drill tank, not only on
        // items.
        registration.addGuiContainerHandler(
                AbstractEnergyDrillScreen.class,
                new IGuiContainerHandler<>() {
                    @Override
                    public Optional<? extends IClickableIngredient<?>>
                            getClickableIngredientUnderMouse(
                                    IClickableIngredientFactory factory,
                                    AbstractEnergyDrillScreen screen,
                                    double mouseX,
                                    double mouseY) {
                        return screen.fluidUnderMouse(mouseX, mouseY)
                                .flatMap(
                                        tank ->
                                                factory.createBuilder(
                                                                NeoForgeTypes.FLUID_STACK,
                                                                tank.fluid())
                                                        .buildWithArea(
                                                                tank.x(),
                                                                tank.y(),
                                                                tank.width(),
                                                                tank.height()));
                    }
                });
    }

    // No category-wide catalysts: they would list every drill on every page. Each page carries
    // catalyst slots with only the drills able to mine it, which also drive the "Uses" (U) lookup
    // on a drill.

    private static final class Category implements IRecipeCategory<DrillMiningRecipe> {
        private final IDrawable icon;
        private final IDrawable background;

        Category(IGuiHelper gui) {
            icon = gui.createDrawableItemLike(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get());
            // drill_jei.png content occupies u 0..141, v 0..91; slot coordinates below are
            // texture-relative.
            background = gui.createDrawable(DRILL_JEI, 0, 0, 142, 92);
        }

        public RecipeType<DrillMiningRecipe> getRecipeType() {
            return TYPE;
        }

        public Component getTitle() {
            return Component.translatable("recipe.ores_and_drills.drill_mining");
        }

        public IDrawable getIcon() {
            return icon;
        }

        public IDrawable getBackground() {
            return background;
        }

        public int getWidth() {
            return 142;
        }

        public int getHeight() {
            return 127;
        }

        public void setRecipe(
                IRecipeLayoutBuilder builder, DrillMiningRecipe recipe, IFocusGroup focuses) {
            // Coordinates mirror AbstractEnergyDrillMenu: scanned ore preview at the top-right,
            // mining fluid in the left tank and the mined result (explicit result or the ore's loot
            // drop) in the lower-right output slot.
            builder.addSlot(RecipeIngredientRole.INPUT, 107, 1)
                    .addItemStacks(DrillRecipePages.ores(recipe));
            builder.addSlot(RecipeIngredientRole.OUTPUT, 107, 75)
                    .addItemStack(DrillRecipePages.output(recipe));
            // The ore is also an invisible output, so the "Recipes" key (R) finds drill mining both
            // on the ore and on its drop.
            builder.addInvisibleIngredients(RecipeIngredientRole.OUTPUT)
                    .addItemStacks(DrillRecipePages.ores(recipe));
            recipe.fluid()
                    .ifPresent(
                            fluid -> {
                                var slot =
                                        builder.addSlot(RecipeIngredientRole.INPUT, 1, 1)
                                                .setFluidRenderer(
                                                        Math.max(1, fluid.amount()), false, 14, 90);
                                if (fluid.hasSameNamedAlternatives()
                                        && !fluid.displayStacks().isEmpty()) {
                                    Component name =
                                            fluid.displayStacks().getFirst().getHoverName();
                                    slot.addRichTooltipCallback(
                                            (view, tooltip) ->
                                                    tooltip.add(
                                                            Component.translatable(
                                                                    "recipe.ores_and_drills.any_fluid",
                                                                    name)));
                                }
                                for (var stack : fluid.displayStacks()) {
                                    slot.addFluidStack(
                                            stack.getFluid(),
                                            stack.getAmount(),
                                            stack.getComponentsPatch());
                                }
                            });
            // Drills able to mine this ore, in the bottom-left corner below the notes.
            List<ItemStack> drills = DrillRecipePages.drills(recipe);
            for (int i = 0; i < drills.size(); i++) {
                builder.addSlot(RecipeIngredientRole.CATALYST, 1 + i * 18, 97)
                        .addItemStack(drills.get(i))
                        .setStandardSlotBackground();
            }
        }

        @Override
        public void draw(
                DrillMiningRecipe recipe,
                IRecipeSlotsView slots,
                GuiGraphics gui,
                double mouseX,
                double mouseY) {
            var font = Minecraft.getInstance().font;
            // Same animated drill artwork and progress bar used by the actual machine screen.
            int frame =
                    (int)
                            ((net.minecraft.Util.getMillis() / 100L)
                                    % AbstractDrillBlockEntity.GUI_ANIMATION_FRAME_COUNT);
            gui.blit(DRILL_ICON, 45, 3, 0, frame * 66, 46, 66, 46, 330);
            gui.blit(MINING_PROGRESS, 37, 77, 0, 0, 64, 3, 64, 3);
            // The minimum drill is shown by the drill slots, so only the remaining notes are text.
            List<Component> lines = DrillRecipePages.infoLines(recipe);
            int top = DrillRecipePages.infoTop(recipe);
            for (int i = 0; i < lines.size(); i++)
                gui.drawString(font, lines.get(i), 1, top + i * 11, 0x404040, false);
        }
    }
}

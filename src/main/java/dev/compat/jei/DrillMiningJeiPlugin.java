package dev.compat.jei;

import dev.recipe.DrillMiningRecipe;
import dev.registry.ModBlocks;
import dev.registry.ModRecipes;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.helpers.IGuiHelper;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

@JeiPlugin
public final class DrillMiningJeiPlugin implements IModPlugin {
    private static final RecipeType<DrillMiningRecipe> TYPE = RecipeType.create("ores_and_drills", "drill_mining", DrillMiningRecipe.class);

    @Override
    public ResourceLocation getPluginUid() { return ResourceLocation.fromNamespaceAndPath("ores_and_drills", "drill_mining"); }
    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        registration.addRecipeCategories(new Category(registration.getJeiHelpers().getGuiHelper()));
    }
    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level != null) registration.addRecipes(TYPE, level.getRecipeManager().getAllRecipesFor(ModRecipes.DRILL_MINING.get())
                .stream().map(holder -> holder.value()).toList());
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
        Category(IGuiHelper gui) { icon = gui.createDrawableItemLike(ModBlocks.ADVANCED_MINING_DRILL_ITEM.get()); }
        public RecipeType<DrillMiningRecipe> getRecipeType() { return TYPE; }
        public Component getTitle() { return Component.translatable("recipe.ores_and_drills.drill_mining"); }
        public IDrawable getIcon() { return icon; }
        public int getWidth() { return 160; }
        public int getHeight() { return 65; }
        public void setRecipe(IRecipeLayoutBuilder builder, DrillMiningRecipe recipe, IFocusGroup focuses) {
            builder.addSlot(RecipeIngredientRole.INPUT, 5, 5).setStandardSlotBackground().addItemStacks(
                    recipe.input().stream().map(holder -> new ItemStack(holder.value())).filter(stack -> !stack.isEmpty()).toList());
            recipe.fluid().ifPresent(fluid -> {
                var slot = builder.addSlot(RecipeIngredientRole.INPUT, 45, 5).setStandardSlotBackground();
                for (var stack : fluid.displayStacks()) {
                    slot.addFluidStack(stack.getFluid(), stack.getAmount(), stack.getComponentsPatch());
                }
            });
            recipe.result().ifPresent(result -> builder.addSlot(RecipeIngredientRole.OUTPUT, 125, 5).setOutputSlotBackground().addItemStack(result));
        }
        @Override
        public void draw(DrillMiningRecipe recipe, IRecipeSlotsView slots, GuiGraphics gui, double mouseX, double mouseY) {
            var font = Minecraft.getInstance().font;
            gui.drawString(font, Component.translatable("recipe.ores_and_drills.minimum_tier", recipe.minimumDrillTier()), 5, 30, 0x404040, false);
            if (recipe.result().isEmpty()) gui.drawString(font, Component.translatable("recipe.ores_and_drills.ore_loot"), 5, 43, 0x404040, false);
            if (!recipe.conditions().isEmpty()) gui.drawString(font,
                    Component.translatable("recipe.ores_and_drills.conditions", recipe.conditions().size()), 5, 54, 0x404040, false);
        }
    }
}

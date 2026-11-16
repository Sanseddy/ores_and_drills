package dev.compat.jei;

import dev.client.assembly.AssemblyScreen;
import dev.registry.ModAssembly;
import dev.world.assembly.AssemblyBlockEntity;
import dev.world.assembly.AssemblyRecipe;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IRecipeLayoutBuilder;
import mezz.jei.api.gui.drawable.IDrawable;
import mezz.jei.api.gui.ingredient.IRecipeSlotsView;
import mezz.jei.api.recipe.IFocusGroup;
import mezz.jei.api.recipe.RecipeIngredientRole;
import mezz.jei.api.recipe.RecipeType;
import mezz.jei.api.recipe.category.IRecipeCategory;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeCatalystRegistration;
import mezz.jei.api.registration.IRecipeCategoryRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

@JeiPlugin
public final class AssemblyJeiPlugin implements IModPlugin {
    private static final RecipeType<AssemblyRecipe> TYPE =
            RecipeType.create("ores_and_drills", "drill_assembly", AssemblyRecipe.class);

    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath("ores_and_drills", "drill_assembly");
    }

    @Override
    public void registerCategories(IRecipeCategoryRegistration registration) {
        var helper = registration.getJeiHelpers().getGuiHelper();
        registration.addRecipeCategories(
                new IRecipeCategory<AssemblyRecipe>() {
                    @Override
                    public RecipeType<AssemblyRecipe> getRecipeType() {
                        return TYPE;
                    }

                    @Override
                    public Component getTitle() {
                        return Component.translatable("block.ores_and_drills.drill_assembly_table");
                    }

                    @Override
                    public IDrawable getBackground() {
                        return helper.createBlankDrawable(156, 88);
                    }

                    @Override
                    public IDrawable getIcon() {
                        return helper.createDrawableItemStack(
                                new ItemStack(ModAssembly.TABLE_ITEM.get()));
                    }

                    @Override
                    public void setRecipe(
                            IRecipeLayoutBuilder builder,
                            AssemblyRecipe recipe,
                            IFocusGroup focuses) {
                        for (int i = 0; i < recipe.parts().size(); i++) {
                            var part = recipe.parts().get(i);
                            builder.addSlot(
                                            RecipeIngredientRole.INPUT,
                                            3 + i % 3 * 24,
                                            12 + i / 3 * 28)
                                    .addItemStacks(
                                            java.util.Arrays.stream(part.ingredient().getItems())
                                                    .map(stack -> stack.copyWithCount(part.count()))
                                                    .toList());
                        }
                        builder.addSlot(RecipeIngredientRole.OUTPUT, 128, 26)
                                .addItemStack(recipe.result());
                    }

                    @Override
                    public void draw(
                            AssemblyRecipe recipe,
                            IRecipeSlotsView slots,
                            GuiGraphics gui,
                            double mouseX,
                            double mouseY) {
                        var font = Minecraft.getInstance().font;
                        for (int i = 0; i < recipe.parts().size(); i++) {
                            gui.drawString(
                                    font,
                                    Integer.toString(i + 1),
                                    3 + i % 3 * 24,
                                    2 + i / 3 * 28,
                                    0xff606060,
                                    false);
                        }
                        gui.drawString(font, "→", 96, 30, 0xff8b6735, false);
                        gui.drawString(
                                font,
                                Component.translatable(
                                        "gui.ores_and_drills.assembly.seconds",
                                        recipe.duration() / 20),
                                3,
                                63,
                                0xff404040,
                                false);
                        gui.drawString(
                                font,
                                Component.translatable("gui.ores_and_drills.assembly.viewer"),
                                3,
                                76,
                                0xff404040,
                                false);
                    }
                });
    }

    @Override
    public void registerRecipes(IRecipeRegistration registration) {
        var level = Minecraft.getInstance().level;
        if (level != null)
            registration.addRecipes(
                    TYPE,
                    AssemblyBlockEntity.recipes(level).stream()
                            .map(holder -> holder.value())
                            .toList());
    }

    @Override
    public void registerRecipeCatalysts(IRecipeCatalystRegistration registration) {
        registration.addRecipeCatalyst(new ItemStack(ModAssembly.TABLE_ITEM.get()), TYPE);
    }

    @Override
    public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addRecipeClickArea(AssemblyScreen.class, 103, 66, 25, 18, TYPE);
    }
}

package dev.compat.emi;

import dev.emi.emi.api.EmiRegistry;
import dev.emi.emi.api.recipe.EmiRecipe;
import dev.emi.emi.api.recipe.EmiRecipeCategory;
import dev.emi.emi.api.stack.EmiIngredient;
import dev.emi.emi.api.stack.EmiStack;
import dev.emi.emi.api.widget.WidgetHolder;
import dev.registry.ModAssembly;
import dev.registry.ModRecipes;
import dev.world.assembly.AssemblyRecipe;
import java.util.List;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.crafting.RecipeHolder;

public final class AssemblyEmiRecipes {
    private static final EmiRecipeCategory CATEGORY =
            new EmiRecipeCategory(
                    ResourceLocation.fromNamespaceAndPath("ores_and_drills", "drill_assembly"),
                    EmiStack.of(ModAssembly.TABLE_ITEM.get())) {
                @Override
                public Component getName() {
                    return Component.translatable("block.ores_and_drills.drill_assembly_table");
                }
            };

    public static void register(EmiRegistry registry) {
        registry.addCategory(CATEGORY);
        registry.addWorkstation(CATEGORY, EmiStack.of(ModAssembly.TABLE_ITEM.get()));
        for (var holder :
                registry.getRecipeManager().getAllRecipesFor(ModRecipes.DRILL_ASSEMBLY.get())) {
            registry.addRecipe(new Page(holder));
        }
    }

    private static final class Page implements EmiRecipe {
        private final RecipeHolder<AssemblyRecipe> holder;
        private final List<EmiIngredient> inputs;
        private final List<EmiStack> outputs;

        private Page(RecipeHolder<AssemblyRecipe> holder) {
            this.holder = holder;
            inputs =
                    holder.value().parts().stream()
                            .map(part -> EmiIngredient.of(part.ingredient(), part.count()))
                            .toList();
            outputs = List.of(EmiStack.of(holder.value().result()));
        }

        @Override
        public EmiRecipeCategory getCategory() {
            return CATEGORY;
        }

        @Override
        public ResourceLocation getId() {
            return holder.id();
        }

        @Override
        public List<EmiIngredient> getInputs() {
            return inputs;
        }

        @Override
        public List<EmiStack> getOutputs() {
            return outputs;
        }

        @Override
        public int getDisplayWidth() {
            return 156;
        }

        @Override
        public int getDisplayHeight() {
            return 88;
        }

        @Override
        public void addWidgets(WidgetHolder widgets) {
            for (int i = 0; i < inputs.size(); i++) {
                widgets.addText(
                        Component.literal(Integer.toString(i + 1)),
                        3 + i % 3 * 24,
                        1 + i / 3 * 28,
                        0xff606060,
                        false);
                widgets.addSlot(inputs.get(i), 2 + i % 3 * 24, 11 + i / 3 * 28);
            }
            widgets.addFillingArrow(88, 26, holder.value().duration() * 50);
            widgets.addSlot(outputs.getFirst(), 127, 25).recipeContext(this);
            widgets.addText(
                    Component.translatable(
                            "gui.ores_and_drills.assembly.seconds", holder.value().duration() / 20),
                    3,
                    63,
                    0xff404040,
                    false);
            widgets.addText(
                    Component.translatable("gui.ores_and_drills.assembly.viewer"),
                    3,
                    76,
                    0xff404040,
                    false);
        }
    }

    private AssemblyEmiRecipes() {}
}

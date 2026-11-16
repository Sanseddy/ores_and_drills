package dev.world.assembly;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;

/** Six numbered fixture positions, in the same order as the recipe's bill of materials. */
public record AssemblyInput(Container container) implements RecipeInput {
    @Override
    public ItemStack getItem(int slot) {
        return container.getItem(slot);
    }

    @Override
    public int size() {
        return 6;
    }
}

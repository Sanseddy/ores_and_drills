package dev.recipe;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeInput;
import net.minecraft.world.level.block.state.BlockState;

public record DrillMiningInput(BlockState state, BlockPos drillPos, BlockPos orePos, int drillTier) implements RecipeInput {
    @Override
    public ItemStack getItem(int slot) {
        if (slot != 0) throw new IllegalArgumentException("No mining input slot " + slot);
        return new ItemStack(state.getBlock());
    }

    @Override
    public int size() { return 1; }

    @Override
    public boolean isEmpty() { return false; }
}

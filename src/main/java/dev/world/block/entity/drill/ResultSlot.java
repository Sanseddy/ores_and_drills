package dev.world.block.entity.drill;

import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;

public final class ResultSlot {
    private final Container container;
    private final int slot;

    public ResultSlot(Container container, int slot) {
        this.container = container;
        this.slot = slot;
    }

    public boolean canAccept(ItemStack stack) {
        if (stack.isEmpty()) {
            return false;
        }

        ItemStack output = container.getItem(slot);
        int maxStackSize = Math.min(stack.getMaxStackSize(), container.getMaxStackSize());
        if (output.isEmpty()) {
            return stack.getCount() <= maxStackSize;
        }

        return ItemStack.isSameItemSameComponents(output, stack)
                && output.getCount() + stack.getCount() <= Math.min(output.getMaxStackSize(), container.getMaxStackSize());
    }

    public void insert(ItemStack stack) {
        ItemStack result = stack.copy();
        ItemStack output = container.getItem(slot);
        if (output.isEmpty()) {
            container.setItem(slot, result);
            return;
        }

        output.grow(result.getCount());
        container.setChanged();
    }
}

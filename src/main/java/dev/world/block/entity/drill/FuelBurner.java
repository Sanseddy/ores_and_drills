package dev.world.block.entity.drill;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeType;

public final class FuelBurner {
    private int fuelTime;
    private int fuelDuration;

    public int fuelTime() {
        return fuelTime;
    }

    public void setFuelTime(int fuelTime) {
        this.fuelTime = fuelTime;
    }

    public int fuelDuration() {
        return fuelDuration;
    }

    public void setFuelDuration(int fuelDuration) {
        this.fuelDuration = fuelDuration;
    }

    public boolean isBurning() {
        return fuelTime > 0;
    }

    public void tickDown() {
        fuelTime--;
    }

    public boolean clearIfIdle() {
        if (fuelTime <= 0 && fuelDuration != 0) {
            fuelDuration = 0;
            return true;
        }

        return false;
    }

    /** Lights the next fuel item exactly like a furnace: smelting burn time, and remainders such as an empty bucket stay. */
    public boolean consume(Container container, int slot) {
        ItemStack fuelStack = container.getItem(slot);
        int burnTime = fuelStack.isEmpty() ? 0 : fuelStack.getBurnTime(RecipeType.SMELTING);
        if (burnTime <= 0) {
            return false;
        }

        fuelTime = burnTime;
        fuelDuration = burnTime;
        if (fuelStack.hasCraftingRemainingItem()) {
            container.setItem(slot, fuelStack.getCraftingRemainingItem());
        } else {
            fuelStack.shrink(1);
            if (fuelStack.isEmpty()) {
                container.setItem(slot, fuelStack.getCraftingRemainingItem());
            }
        }
        return true;
    }

    public void load(CompoundTag tag) {
        fuelTime = tag.getInt("FuelTime");
        fuelDuration = tag.getInt("FuelDuration");
    }

    public void save(CompoundTag tag) {
        tag.putInt("FuelTime", fuelTime);
        tag.putInt("FuelDuration", fuelDuration);
    }
}

package dev.world.block.entity.drill;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;

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

    public boolean consume(ItemStack fuelStack) {
        int burnTime = fuelStack.getBurnTime(null);
        if (burnTime <= 0) {
            return false;
        }

        fuelTime = burnTime;
        fuelDuration = burnTime;
        fuelStack.shrink(1);
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

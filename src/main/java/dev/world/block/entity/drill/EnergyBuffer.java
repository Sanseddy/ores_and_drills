package dev.world.block.entity.drill;

import net.neoforged.neoforge.energy.EnergyStorage;

public final class EnergyBuffer extends EnergyStorage {
    private final Runnable onChanged;

    public EnergyBuffer(int capacity, int maxReceive, Runnable onChanged) {
        super(capacity, maxReceive, 0);
        this.onChanged = onChanged;
    }

    @Override
    public int receiveEnergy(int maxReceive, boolean simulate) {
        int received = super.receiveEnergy(maxReceive, simulate);
        if (received > 0 && !simulate) {
            onChanged.run();
        }
        return received;
    }

    public boolean hasEnergy(int amount) {
        return energy >= amount;
    }

    public void consume(int amount) {
        int previous = energy;
        energy = Math.max(0, energy - amount);
        if (energy != previous) {
            onChanged.run();
        }
    }
}

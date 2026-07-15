package dev.world.block.entity.drill;

import net.neoforged.neoforge.energy.EnergyStorage;

public final class EnergyBuffer extends EnergyStorage {
    public EnergyBuffer(int capacity, int maxReceive) {
        super(capacity, maxReceive, 0);
    }

    public boolean hasEnergy(int amount) {
        return energy >= amount;
    }

    public void consume(int amount) {
        energy = Math.max(0, energy - amount);
    }
}

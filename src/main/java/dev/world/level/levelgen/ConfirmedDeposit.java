package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/** A deposit that placed enough real blocks to be authoritative for /locate. */
public record ConfirmedDeposit(
        long depositId,
        ResourceLocation oreId,
        OreDepositTier tier,
        BlockPos center,
        int placedBlocks,
        int occupiedSections,
        @Nullable Direction wallDirection,
        boolean exposedToCave
) {
    public ConfirmedDeposit {
        if (oreId == null || tier == null || center == null) {
            throw new IllegalArgumentException("Confirmed deposit identity, tier and center are required");
        }
        center = center.immutable();
        if (placedBlocks < 1 || occupiedSections < 1) {
            throw new IllegalArgumentException("Placed blocks and occupied sections must be positive");
        }
        if (wallDirection != null && wallDirection.getAxis().isVertical()) {
            throw new IllegalArgumentException("A confirmed wall direction must be horizontal");
        }
        if (wallDirection != null && !exposedToCave) {
            throw new IllegalArgumentException("A confirmed wall deposit must be exposed to a cave");
        }
    }
}

package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.resources.ResourceLocation;

import javax.annotation.Nullable;

/** Immutable seed plan shared by ore-deposit generation and locating. */
public record DepositCandidate(
        long depositId,
        ResourceLocation oreId,
        OreDepositTier tier,
        BlockPos center,
        int radiusX,
        int radiusZ,
        int verticalRadius,
        long shapeSeed,
        @Nullable Direction wallDirection,
        int expectedBlocks,
        int sourceChunkX,
        int sourceChunkZ
) {
    public DepositCandidate {
        if (oreId == null || tier == null || center == null) {
            throw new IllegalArgumentException("Deposit identity, tier and center are required");
        }
        center = center.immutable();
        if (radiusX < 1 || radiusZ < 1 || verticalRadius < 1 || expectedBlocks < 1) {
            throw new IllegalArgumentException("Deposit dimensions and expected block count must be positive");
        }
        if (wallDirection != null && wallDirection.getAxis().isVertical()) {
            throw new IllegalArgumentException("A deposit wall direction must be horizontal");
        }
    }

    public DepositCandidate withWallAnchor(CaveWallAnchor anchor) {
        if (!tier.prefersCaveWall()) {
            return this;
        }
        return new DepositCandidate(
                depositId, oreId, tier, anchor.position(), radiusX, radiusZ, verticalRadius,
                shapeSeed, anchor.inwardDirection(), expectedBlocks, sourceChunkX, sourceChunkZ
        );
    }

    /** Converts a wall-preferred seed plan to its deterministic underground fallback. */
    public DepositCandidate withUndergroundCenter(BlockPos undergroundCenter) {
        return new DepositCandidate(
                depositId, oreId, tier, undergroundCenter, radiusX, radiusZ, verticalRadius,
                shapeSeed, null, expectedBlocks, sourceChunkX, sourceChunkZ
        );
    }
}

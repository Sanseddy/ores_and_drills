package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;

/** One terrain probe and the logical volume section it represents. */
public record SamplePoint(BlockPos pos, int section) {
    public SamplePoint {
        if (pos == null || section < 0) {
            throw new IllegalArgumentException("A sample position and non-negative section are required");
        }
        pos = pos.immutable();
    }
}

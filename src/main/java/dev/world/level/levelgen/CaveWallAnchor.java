package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/** A real stone block exposed to horizontally adjacent cave space. */
public record CaveWallAnchor(BlockPos position, Direction exposedFace) {
    public CaveWallAnchor {
        if (position == null || exposedFace == null || exposedFace.getAxis().isVertical()) {
            throw new IllegalArgumentException("A cave-wall anchor needs a horizontal exposed face");
        }
        position = position.immutable();
    }

    public Direction inwardDirection() {
        return exposedFace.getOpposite();
    }
}

package dev.world.inventory;

import dev.world.block.DrillStructure;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

public final class DrillMenuValidity {
    private static final double MAX_DISTANCE_SQUARED = 64.0D;

    private DrillMenuValidity() {
    }

    public static boolean stillValid(ContainerLevelAccess access, Player player, Block mainBlock, DrillStructure structure) {
        return access.evaluate((level, origin) -> {
            var state = level.getBlockState(origin);
            if (!state.is(mainBlock) || !state.hasProperty(BlockStateProperties.HORIZONTAL_FACING)) {
                return false;
            }

            // A Sable sub-level stores blocks at its plot coordinates, far away from the
            // overworld coordinates of the player. Entity#distanceToSqr is patched by
            // Sable to project either endpoint out of a sub-level; AABB#distanceToSqr is
            // not. Keep the centre-based check so ordinary large drills retain the same
            // interaction radius while moved drills remain usable.
            var bounds = structure.bounds(origin, state.getValue(BlockStateProperties.HORIZONTAL_FACING));
            var centre = bounds.getCenter();
            return player.distanceToSqr(centre) <= MAX_DISTANCE_SQUARED;
        }, true);
    }
}

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

            // The inventory lives in the main block entity, but the player may have
            // opened it from any part of a large drill. Keep the menu valid while the
            // player is within vanilla's eight-block range of at least one structure
            // cell. Entity#distanceToSqr also preserves Sable's sub-level projection.
            return structure.isWithinInteractionDistance(
                    player,
                    origin,
                    state.getValue(BlockStateProperties.HORIZONTAL_FACING),
                    MAX_DISTANCE_SQUARED
            );
        }, true);
    }
}

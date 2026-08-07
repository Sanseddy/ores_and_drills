package dev.world.block.entity;

import dev.registry.ModBlockEntities;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Marker block entity for drill structure parts.
 *
 * <p>It intentionally stores no data and has no ticker. The capabilities exposed
 * at this position are resolved to the drill's central block entity.</p>
 */
public final class DrillPartBlockEntity extends BlockEntity {
    public DrillPartBlockEntity(BlockPos pos, BlockState state) {
        super(ModBlockEntities.DRILL_PART.get(), pos, state);
    }
}

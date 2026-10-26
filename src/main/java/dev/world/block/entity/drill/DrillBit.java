package dev.world.block.entity.drill;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.Vec3;

/**
 * Where a drill model's bit meets the ground, in blocks from the centre of the drill's main block
 * and in the drill's own frame: {@code right} along the facing's clockwise side, {@code forward}
 * along the facing. Matches the model placement of the drill's renderer.
 *
 * @param radius horizontal radius of the bit at ground level, where mining particles burst out
 * @param debrisPerTick how many debris particles the working bit throws up per tick at full
 *     particle settings
 */
public record DrillBit(double right, double forward, double radius, double debrisPerTick) {
    /**
     * World point where the bit enters the ground: the drill stands on it, at the main block's
     * bottom face.
     */
    public Vec3 groundCenter(BlockPos origin, Direction facing) {
        Direction side = facing.getClockWise();
        return new Vec3(
                origin.getX() + 0.5D + side.getStepX() * right + facing.getStepX() * forward,
                origin.getY(),
                origin.getZ() + 0.5D + side.getStepZ() * right + facing.getStepZ() * forward);
    }
}

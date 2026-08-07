package dev.client.drill;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.DrillModelCell;
import dev.world.block.DrillStructure;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;

/** Client-side exact raycast and outline rendering for rotated drill model cubes. */
public final class DrillExactHitboxes {
    private DrillExactHitboxes() {
    }

    public static boolean usesExactRaycast(BlockState state) {
        return resolve(state).drill();
    }

    @Nullable
    public static BlockHitResult clipBlock(BlockState state, Vec3 start, Vec3 end, BlockPos pos) {
        ResolvedCell resolved = resolve(state);
        return resolved.cell() == null ? null : resolved.cell().clip(start, end, pos);
    }

    public static boolean render(
            BlockState state,
            PoseStack poseStack,
            VertexConsumer consumer,
            BlockPos pos,
            double cameraX,
            double cameraY,
            double cameraZ
    ) {
        ResolvedCell resolved = resolve(state);
        if (!resolved.drill() || resolved.cell() == null) {
            return false;
        }

        PoseStack.Pose pose = poseStack.last();
        double translateX = pos.getX() - cameraX;
        double translateY = pos.getY() - cameraY;
        double translateZ = pos.getZ() - cameraZ;
        for (DrillModelCell.Line line : resolved.cell().lines()) {
            Vec3 start = line.start();
            Vec3 end = line.end();
            Vec3 direction = end.subtract(start);
            double length = direction.length();
            if (length <= 1.0E-8D) {
                continue;
            }

            float normalX = (float) (direction.x / length);
            float normalY = (float) (direction.y / length);
            float normalZ = (float) (direction.z / length);
            consumer.addVertex(
                            pose,
                            (float) (start.x + translateX),
                            (float) (start.y + translateY),
                            (float) (start.z + translateZ)
                    )
                    .setColor(0.0F, 0.0F, 0.0F, 0.4F)
                    .setNormal(pose, normalX, normalY, normalZ);
            consumer.addVertex(
                            pose,
                            (float) (end.x + translateX),
                            (float) (end.y + translateY),
                            (float) (end.z + translateZ)
                    )
                    .setColor(0.0F, 0.0F, 0.0F, 0.4F)
                    .setNormal(pose, normalX, normalY, normalZ);
        }
        return true;
    }

    private static ResolvedCell resolve(BlockState state) {
        if (state.getBlock() instanceof AbstractDrillBlock block) {
            Direction facing = state.getValue(AbstractDrillBlock.FACING);
            return new ResolvedCell(true, block.structure().mainExactCell(facing));
        }
        if (state.getBlock() instanceof AbstractDrillPartBlock block) {
            DrillStructure structure = block.structure();
            Direction facing = state.getValue(AbstractDrillBlock.FACING);
            return new ResolvedCell(true, structure.exactCellForOffset(
                    facing,
                    state.getValue(structure.offsetXProperty()),
                    state.getValue(structure.offsetYProperty()),
                    state.getValue(structure.offsetZProperty())
            ));
        }
        return new ResolvedCell(false, null);
    }

    private record ResolvedCell(boolean drill, DrillModelCell cell) {
    }
}

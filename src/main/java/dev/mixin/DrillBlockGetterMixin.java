package dev.mixin;

import dev.client.drill.DrillExactHitboxes;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Replaces only a drill cell's local block intersection instead of replacing the
 * complete level raycast. This keeps projection-aware {@link BlockGetter#clip}
 * implementations, including Sable's sub-level traversal, in control.
 */
@Mixin(BlockGetter.class)
interface DrillBlockGetterMixin {
    @Inject(method = "clipWithInteractionOverride", at = @At("HEAD"), cancellable = true)
    private void oresAndDrills$useExactDrillCell(
            Vec3 start,
            Vec3 end,
            BlockPos pos,
            VoxelShape blockShape,
            BlockState state,
            CallbackInfoReturnable<BlockHitResult> callback
    ) {
        if (DrillExactHitboxes.usesExactRaycast(state)) {
            callback.setReturnValue(DrillExactHitboxes.clipBlock(state, start, end, pos));
        }
    }
}

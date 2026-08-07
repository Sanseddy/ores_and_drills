package dev.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.client.drill.DrillExactHitboxes;
import dev.world.block.AbstractDrillPartBlock;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.LevelRenderer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(LevelRenderer.class)
abstract class DrillLevelRendererMixin {
    @Unique
    private boolean oresAndDrills$redirectingDrillBreakProgress;

    @Inject(method = "destroyBlockProgress", at = @At("HEAD"), cancellable = true)
    private void oresAndDrills$renderPartBreakingOnDrillModel(
            int breakerId,
            BlockPos pos,
            int progress,
            CallbackInfo callback
    ) {
        if (oresAndDrills$redirectingDrillBreakProgress) {
            return;
        }

        Level level = Minecraft.getInstance().level;
        if (level == null) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof AbstractDrillPartBlock part) {
            callback.cancel();
            oresAndDrills$redirectingDrillBreakProgress = true;
            try {
                ((LevelRenderer) (Object) this).destroyBlockProgress(
                        breakerId,
                        part.structure().originFromPart(pos, state),
                        progress
                );
            } finally {
                oresAndDrills$redirectingDrillBreakProgress = false;
            }
        }
    }

    @Inject(method = "renderHitOutline", at = @At("HEAD"), cancellable = true)
    private void oresAndDrills$renderExactDrillCellOutline(
            PoseStack poseStack,
            VertexConsumer consumer,
            Entity entity,
            double cameraX,
            double cameraY,
            double cameraZ,
            BlockPos pos,
            BlockState state,
            CallbackInfo callback
    ) {
        if (DrillExactHitboxes.render(state, poseStack, consumer, pos, cameraX, cameraY, cameraZ)) {
            callback.cancel();
        }
    }
}

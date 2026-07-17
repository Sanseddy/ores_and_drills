package dev.mixin;

import dev.compat.sable.SableAssemblyTransfer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import org.spongepowered.asm.mixin.injection.callback.LocalCapture;

import java.util.Queue;
import java.util.Set;

/**
 * Simulated normally follows glue connections only. Drill parts are linked by their
 * controller data instead, so add the complete valid drill to Simulated's search
 * queue before it builds the final collection for Sable.
 */
@Pseudo
@Mixin(targets = "dev.simulated_team.simulated.util.assembly.SimAssemblyContraption", remap = false)
public abstract class SimulatedPhysicsAssemblerMixin {
    @Inject(
            method = "moveBlock",
            at = @At(
                    value = "INVOKE_ASSIGN",
                    target = "Lnet/minecraft/world/level/Level;getBlockState(Lnet/minecraft/core/BlockPos;)Lnet/minecraft/world/level/block/state/BlockState;",
                    ordinal = 0
            ),
            locals = LocalCapture.CAPTURE_FAILHARD,
            require = 0,
            remap = false
    )
    private void oresAndDrills$queueCompleteDrill(
            Level level,
            Queue<BlockPos> queue,
            Set<BlockPos> movedBlocks,
            Set<BlockPos> movedBlocksView,
            CallbackInfoReturnable<?> callbackInfo,
            BlockPos currentPos,
            BlockState currentState
    ) {
        SableAssemblyTransfer.expandSimulatedQueue(level, currentPos, currentState, queue, movedBlocks);
    }
}

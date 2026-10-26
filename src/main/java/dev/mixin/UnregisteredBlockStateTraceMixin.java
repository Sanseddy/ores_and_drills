package dev.mixin;

import dev.world.level.chunk.UnregisteredPaletteEntries;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.ProtoChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Names the code that puts an unregistered BlockState into a chunk. Feature and gameplay writes go
 * through these two methods; bulk noise filling does not, so the registry lookup stays off that
 * path.
 */
@Mixin({LevelChunk.class, ProtoChunk.class})
public abstract class UnregisteredBlockStateTraceMixin {
    @Inject(
            method =
                    "setBlockState(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;Z)"
                            + "Lnet/minecraft/world/level/block/state/BlockState;",
            at = @At("HEAD"))
    private void oresAndDrills$traceUnregisteredState(
            BlockPos pos,
            BlockState state,
            boolean moving,
            CallbackInfoReturnable<BlockState> callbackInfo) {
        UnregisteredPaletteEntries.traceWrite(pos, state);
    }
}

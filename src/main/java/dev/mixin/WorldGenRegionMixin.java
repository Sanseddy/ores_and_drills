package dev.mixin;

import dev.world.level.levelgen.DepositWorldgenGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldGenRegion.class)
public abstract class WorldGenRegionMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void oresAndDrills$keepDepositOnSet(
            BlockPos pos, BlockState state, int flags, int recursionLeft, CallbackInfoReturnable<Boolean> cir
    ) {
        if (DepositWorldgenGuard.blocksWrite((WorldGenRegion) (Object) this, pos, state)) {
            cir.setReturnValue(false);
        }
    }

    @Inject(
            method = "destroyBlock(Lnet/minecraft/core/BlockPos;ZLnet/minecraft/world/entity/Entity;I)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void oresAndDrills$keepDepositOnDestroy(
            BlockPos pos, boolean dropBlock, Entity entity, int recursionLeft, CallbackInfoReturnable<Boolean> cir
    ) {
        if (DepositWorldgenGuard.blocksWrite((WorldGenRegion) (Object) this, pos, Blocks.AIR.defaultBlockState())) {
            cir.setReturnValue(false);
        }
    }
}

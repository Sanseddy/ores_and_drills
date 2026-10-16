package dev.mixin;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositRemovalGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import javax.annotation.Nullable;

@Mixin(Level.class)
public abstract class LevelMixin {
    @Inject(
            method = "setBlock(Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/level/block/state/BlockState;II)Z",
            at = @At("HEAD"),
            cancellable = true
    )
    private void oresAndDrills$protectPersistentDeposit(
            BlockPos pos,
            BlockState newState,
            int flags,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        Level level = (Level)(Object)this;
        if (!(level instanceof ServerLevel serverLevel)
                || !serverLevel.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())
                || newState.is(ModBlocks.ORE_DEPOSIT.get())
                || newState.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())
                || OreDepositRemovalGuard.isAuthorized(serverLevel, pos)
                || OreDepositData.isPhysicalTransferSource(serverLevel, pos)) {
            return;
        }

        // There is no common NeoForge event for machine mining. Reject direct replacement so an
        // unsupported machine cannot erase the whole reserve by treating it as one ordinary block.
        callbackInfo.setReturnValue(false);
    }

    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void oresAndDrills$mineOreDepositOnWorldDestroy(
            BlockPos pos,
            boolean dropBlock,
            @Nullable Entity entity,
            int recursionLeft,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        Level level = (Level)(Object)this;
        if (!dropBlock || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState state = serverLevel.getBlockState(pos);
        if (state.is(ModBlocks.ORE_DEPOSIT.get()) && OreDepositData.mineByWorld(serverLevel, pos)) {
            callbackInfo.setReturnValue(true);
        }
    }
}

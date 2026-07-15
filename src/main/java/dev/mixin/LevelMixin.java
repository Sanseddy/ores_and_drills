package dev.mixin;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
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
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void factoryExpansion$mineOreDepositOnWorldDestroy(
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

package dev.mixin;

import dev.registry.ModBlocks;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
    /**
     * Vanilla removes the block on the client the instant mining completes, ahead of the server's
     * response, for responsiveness. We cancel the real destruction server-side and decide the outcome
     * ourselves (deplete the vein or revert to the base block), so letting that local removal through
     * makes the deposit flicker to air for a frame before our correction arrives. Skipping it here (for
     * non-creative players, where the server is authoritative) leaves the hit sounds, swing and the
     * STOP_DESTROY_BLOCK packet untouched — only the premature client-side removal is suppressed.
     */
    @Inject(method = "destroyBlock", at = @At("HEAD"), cancellable = true)
    private void factoryExpansion$skipLocalOreDepositRemoval(BlockPos pos, CallbackInfoReturnable<Boolean> callbackInfo) {
        Minecraft minecraft = Minecraft.getInstance();
        Level level = minecraft.level;
        if (level == null || minecraft.player == null || minecraft.player.isCreative()) {
            return;
        }

        BlockState state = level.getBlockState(pos);
        if (state.is(ModBlocks.ORE_DEPOSIT.get())) {
            callbackInfo.setReturnValue(true);
        }
    }
}

package dev.mixin;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Makes Just Hammers mine one unit from each ore deposit in its area instead of replacing the
 * whole deposit block with air. The posted BreakEvent is left untouched; only Just Hammers'
 * internal "may continue with vanilla destruction" result is changed after the virtual mining.
 */
@Pseudo
@Mixin(targets = "pro.mikey.justhammers.neoforge.XPlatNeoForge", remap = false)
public abstract class JustHammersXPlatMixin {
    @Inject(
            method = "fireBlockBrokenEvent",
            at = @At("RETURN"),
            cancellable = true,
            require = 0,
            remap = false
    )
    private void oresAndDrills$mineOreDepositInArea(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            Player player,
            CallbackInfoReturnable<Boolean> callbackInfo
    ) {
        if (!callbackInfo.getReturnValueZ() || !state.is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }

        OreDepositData.mineByPlayer(level, pos, state, player);

        // False makes Just Hammers skip its direct drops, destroy() and setBlock(AIR) calls.
        callbackInfo.setReturnValue(false);
    }
}

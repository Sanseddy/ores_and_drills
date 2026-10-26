package dev.mixin;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import dev.world.block.OreDepositMiningSpeed;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Every machine that breaks blocks by hardness (Create drills, Offroad rock-cutting wheels, other
 * mods' breakers) mines an ore deposit at the pace of a pickaxe: the ore's own hardness, scaled by
 * the remaining ore. Players are unaffected, since {@code OreDepositBlock.getDestroyProgress} reads
 * the ore's state instead.
 */
@Mixin(BlockBehaviour.BlockStateBase.class)
public abstract class OreDepositHardnessMixin {
    @ModifyReturnValue(method = "getDestroySpeed", at = @At("RETURN"))
    private float oresAndDrills$scaleOreDepositHardness(
            float hardness, BlockGetter level, BlockPos pos) {
        return OreDepositMiningSpeed.machineHardness(
                (BlockState) (Object) this, level, pos, hardness);
    }
}

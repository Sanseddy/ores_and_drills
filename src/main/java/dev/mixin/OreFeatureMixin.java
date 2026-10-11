package dev.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import dev.world.level.levelgen.DepositWorldgenGuard;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.feature.OreFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.Function;

/** OreFeature writes sections directly, bypassing WorldGenRegion.setBlock protection. */
@Mixin(OreFeature.class)
public abstract class OreFeatureMixin {
    @WrapOperation(method="doPlace",at=@At(value="INVOKE",target=
            "Lnet/minecraft/world/level/levelgen/feature/OreFeature;canPlaceOre(Lnet/minecraft/world/level/block/state/BlockState;Ljava/util/function/Function;Lnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration;Lnet/minecraft/world/level/levelgen/feature/configurations/OreConfiguration$TargetBlockState;Lnet/minecraft/core/BlockPos$MutableBlockPos;)Z"))
    private boolean oresAndDrills$skipGravelInPlannedPatch(BlockState host,Function<BlockPos,BlockState> adjacent,
            RandomSource random,OreConfiguration config,OreConfiguration.TargetBlockState target,
            BlockPos.MutableBlockPos pos,Operation<Boolean> original,@Local(argsOnly=true) WorldGenLevel level) {
        // Preserve vanilla RNG consumption even for blocked positions.
        boolean allowed=original.call(host,adjacent,random,config,target,pos);
        return allowed && (!target.state.is(Blocks.GRAVEL) || !DepositWorldgenGuard.blocksGravel(level,pos));
    }
}

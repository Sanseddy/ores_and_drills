package dev.mixin;

import dev.compat.sable.SableAssemblyTransfer;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Protects multipart drills and moves position-based ore attachments during Sable assembly. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper", remap = false)
public abstract class SableSubLevelAssemblyHelperMixin {
    @Inject(method = "moveBlocks", at = @At("HEAD"), require = 0, remap = false)
    private static void oresAndDrills$beginAssemblyTransfer(
            ServerLevel sourceLevel,
            @Coerce Object rawTransform,
            Iterable<BlockPos> positions,
            CallbackInfo callbackInfo
    ) {
        SableAssemblyTransformAccessor transform = (SableAssemblyTransformAccessor)rawTransform;
        SableAssemblyTransfer.begin(
                sourceLevel,
                transform.oresAndDrills$getLevel(),
                transform::oresAndDrills$apply,
                transform::oresAndDrills$apply,
                positions
        );
        OreDepositData.beginPhysicalTransfer(
                sourceLevel,
                transform.oresAndDrills$getLevel(),
                transform::oresAndDrills$apply,
                positions
        );
    }

    @Inject(method = "moveBlocks", at = @At("RETURN"), require = 0, remap = false)
    private static void oresAndDrills$finishAssemblyTransfer(
            ServerLevel sourceLevel,
            @Coerce Object rawTransform,
            Iterable<BlockPos> positions,
            CallbackInfo callbackInfo
    ) {
        OreDepositData.finishPhysicalTransfer();
        SableAssemblyTransfer.finish();
    }
}

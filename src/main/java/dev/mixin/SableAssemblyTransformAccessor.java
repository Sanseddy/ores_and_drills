package dev.mixin;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Optional, vanilla-typed access to Sable's assembly coordinate transform. */
@Pseudo
@Mixin(targets = "dev.ryanhcode.sable.api.SubLevelAssemblyHelper$AssemblyTransform", remap = false)
public interface SableAssemblyTransformAccessor {
    @Invoker(value = "apply", remap = false)
    BlockPos oresAndDrills$apply(BlockPos pos);

    @Invoker(value = "apply", remap = false)
    BlockState oresAndDrills$apply(BlockState state);

    @Invoker(value = "getLevel", remap = false)
    ServerLevel oresAndDrills$getLevel();
}

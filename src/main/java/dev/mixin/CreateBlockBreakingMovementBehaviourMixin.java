package dev.mixin;

import dev.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Keeps a moving Create drill stalled at a persistent ore deposit. Create normally assumes that a
 * completed breaking cycle always removes the block and immediately lets the contraption advance.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.content.kinetics.base.BlockBreakingMovementBehaviour", remap = false)
public abstract class CreateBlockBreakingMovementBehaviourMixin {
    private static final String RETRY_POS = "OresAndDrillsRetryPos";
    private static final String BREAKING_POS = "BreakingPos";

    @Shadow(remap = false)
    public abstract boolean canBreak(Level level, BlockPos pos, BlockState state);

    @Inject(method = "tickBreaker", at = @At("HEAD"), require = 0, remap = false)
    private void oresAndDrills$rememberOreDeposit(
            @Coerce Object movementContext,
            CallbackInfo callbackInfo
    ) {
        CreateMovementContextAccessor context = (CreateMovementContextAccessor) movementContext;
        Level level = context.oresAndDrills$getWorld();
        CompoundTag data = context.oresAndDrills$getData();

        NbtUtils.readBlockPos(data, BREAKING_POS)
                .filter(pos -> level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get()))
                .ifPresent(pos -> data.putLong(RETRY_POS, pos.asLong()));
    }

    @Inject(method = "tickBreaker", at = @At("RETURN"), require = 0, remap = false)
    private void oresAndDrills$retryPersistentOreDeposit(
            @Coerce Object movementContext,
            CallbackInfo callbackInfo
    ) {
        CreateMovementContextAccessor context = (CreateMovementContextAccessor) movementContext;
        CompoundTag data = context.oresAndDrills$getData();
        if (!data.contains(RETRY_POS)) {
            return;
        }

        BlockPos pos = BlockPos.of(data.getLong(RETRY_POS));
        data.remove(RETRY_POS);

        // BreakingPos is only removed when Create considers this breaking cycle finished. On the final
        // virtual ore unit the deposit changes directly into its stored base rock. Create still assumes the
        // old block was removed to air, so without starting a fresh cycle it unstalls and moves into that
        // newly-created solid block. Re-arm the breaker for either the persistent deposit or its breakable
        // replacement; the next cycle then mines the base rock normally before movement can resume.
        Level level = context.oresAndDrills$getWorld();
        BlockState currentState = level.getBlockState(pos);
        if (data.contains(BREAKING_POS)
                || currentState.isAir()
                || !canBreak(level, pos, currentState)) {
            return;
        }

        data.put(BREAKING_POS, NbtUtils.writeBlockPos(pos));
        data.remove("Progress");
        data.remove("TicksUntilNextProgress");
        context.oresAndDrills$setStall(true);
    }
}

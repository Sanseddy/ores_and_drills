package dev.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import dev.recipe.DrillMiningRecipes;
import dev.registry.ModBlocks;
import dev.world.block.OreDepositMiningSpeed;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;

/**
 * Optional Mekanism integration for the Digital Miner.
 *
 * <p>A deposit is one physical block backed by many virtual ore units. Mekanism normally obtains a
 * block's drops, removes it and clears its scan bit in one operation. For deposits we instead replace
 * the pending drop list with the result of one virtual extraction and keep the scan bit set until the
 * reserve reaches zero. This preserves the Digital Miner's normal energy, delay, inventory/overflow
 * and filter handling for every extracted unit.</p>
 *
 * <p>Like a pickaxe, each unit takes {@code delay x remaining-ore multiplier}: when the miner first reaches a
 * unit after its normal delay it waits out the rest before extracting, while still drawing energy.</p>
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.machine.TileEntityDigitalMiner", remap = false)
public abstract class MekanismDigitalMinerMixin {
    @Shadow
    private int cachedToMine;
    @Shadow
    private int delayLength;

    @Unique
    private List<ItemStack> oresAndDrills$pendingDrops;
    @Unique
    private ServerLevel oresAndDrills$pendingLevel;
    @Unique
    private BlockPos oresAndDrills$pendingPos;
    @Unique
    private boolean oresAndDrills$keepScanTarget;
    /** Deposit whose current unit has already been given its extra wait; it is extracted on the next try. */
    @Unique
    private BlockPos oresAndDrills$waitedPos;
    /** Ticks that replace the next normal delay, so the current deposit unit takes as long as with a pickaxe. */
    @Unique
    private int oresAndDrills$extraDelay;

    @Inject(method = "getDrops", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void oresAndDrills$previewDepositDrop(
            ServerLevel level,
            BlockState state,
            BlockPos pos,
            CallbackInfoReturnable<List<ItemStack>> callback
    ) {
        oresAndDrills$clearPendingExtraction();
        if (!level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
            // Mekanism rolls loot itself, without BlockDropsEvent: fluid-locked ore is mined for nothing.
            if (DrillMiningRecipes.requiresFluidOutsideDrills(level, state)) {
                callback.setReturnValue(new ArrayList<>());
            }
            return;
        }

        // A fluid-locked deposit still loses its unit in setReplace, but shows no drop to make room for.
        boolean fluidLocked = OreDepositData.isFluidLocked(level, pos);
        ItemStack preview = fluidLocked ? ItemStack.EMPTY : OreDepositData.previewResult(level, pos);
        if (preview.isEmpty() && !fluidLocked) {
            return;
        }

        // The list must stay mutable: setReplace runs after Mekanism's capacity simulation and swaps
        // this preview for the exact (potentially multi-item, such as raw copper) mined result.
        oresAndDrills$pendingDrops = new ArrayList<>(1);
        if (!preview.isEmpty()) {
            oresAndDrills$pendingDrops.add(preview);
        }
        oresAndDrills$pendingLevel = level;
        oresAndDrills$pendingPos = pos.immutable();
        callback.setReturnValue(oresAndDrills$pendingDrops);
    }

    @Inject(method = "setReplace", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void oresAndDrills$extractOneDepositUnit(
            BlockState state,
            BlockPos pos,
            @Coerce Object filter,
            CallbackInfoReturnable<Boolean> callback
    ) {
        if (oresAndDrills$pendingDrops == null
                || oresAndDrills$pendingLevel == null
                || !pos.equals(oresAndDrills$pendingPos)
                || !oresAndDrills$pendingLevel.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
            oresAndDrills$waitedPos = null;
            return;
        }

        if (!pos.equals(oresAndDrills$waitedPos)) {
            double multiplier = OreDepositMiningSpeed.remainingOreMultiplier(oresAndDrills$pendingLevel, pos);
            int extraDelay = (int)Math.round(delayLength * (multiplier - 1.0D));
            if (extraDelay > 0) {
                // Not yet: nothing is extracted and the scan bit stays, so this same unit is retried once
                // the extra delay has passed.
                oresAndDrills$waitedPos = pos.immutable();
                oresAndDrills$extraDelay = extraDelay;
                oresAndDrills$clearPendingExtraction();
                callback.setReturnValue(false);
                return;
            }
        }
        oresAndDrills$waitedPos = null;

        // mineOne wastes a fluid-locked unit and returns nothing; that still counts as one mined unit.
        boolean fluidLocked = OreDepositData.isFluidLocked(oresAndDrills$pendingLevel, pos);
        ItemStack mined = OreDepositData.mineOne(oresAndDrills$pendingLevel, pos);
        oresAndDrills$pendingDrops.clear();
        if (mined.isEmpty() && !fluidLocked) {
            oresAndDrills$clearPendingExtraction();
            callback.setReturnValue(false);
            return;
        }

        if (!mined.isEmpty()) {
            oresAndDrills$pendingDrops.add(mined);
        }
        oresAndDrills$keepScanTarget = oresAndDrills$pendingLevel.getBlockState(pos)
                .is(ModBlocks.ORE_DEPOSIT.get());
        oresAndDrills$pendingDrops = null;
        oresAndDrills$pendingLevel = null;
        oresAndDrills$pendingPos = null;

        // The virtual extraction is the replacement operation. On the final unit mineOne changes the
        // block to the exhausted deposit and the normal scan bit is allowed to be cleared below.
        callback.setReturnValue(true);
    }

    @ModifyExpressionValue(
            method = "onUpdateServer",
            at = @At(value = "INVOKE", target = "Lmekanism/common/tile/machine/TileEntityDigitalMiner;getDelay()I"),
            require = 0,
            remap = false
    )
    private int oresAndDrills$waitOutDepositUnit(int delay) {
        int extraDelay = oresAndDrills$extraDelay;
        oresAndDrills$extraDelay = 0;
        return extraDelay > 0 ? extraDelay : delay;
    }

    @Redirect(
            method = "tryMineBlock",
            at = @At(value = "INVOKE", target = "Ljava/util/BitSet;clear(I)V", ordinal = 0),
            require = 0,
            remap = false
    )
    private void oresAndDrills$retainNonEmptyDeposit(BitSet targets, int index) {
        if (oresAndDrills$keepScanTarget) {
            // Mekanism decrements this immediately before BitSet.clear. Offset that decrement because
            // the same physical position still represents at least one mineable virtual ore unit.
            cachedToMine++;
            oresAndDrills$keepScanTarget = false;
            return;
        }
        targets.clear(index);
    }

    @Unique
    private void oresAndDrills$clearPendingExtraction() {
        oresAndDrills$pendingDrops = null;
        oresAndDrills$pendingLevel = null;
        oresAndDrills$pendingPos = null;
        oresAndDrills$keepScanTarget = false;
    }
}

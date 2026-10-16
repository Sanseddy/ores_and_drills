package dev.mixin;

import dev.registry.ModBlocks;
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
 */
@Pseudo
@Mixin(targets = "mekanism.common.tile.machine.TileEntityDigitalMiner", remap = false)
public abstract class MekanismDigitalMinerMixin {
    @Shadow
    private int cachedToMine;

    @Unique
    private List<ItemStack> oresAndDrills$pendingDrops;
    @Unique
    private ServerLevel oresAndDrills$pendingLevel;
    @Unique
    private BlockPos oresAndDrills$pendingPos;
    @Unique
    private boolean oresAndDrills$keepScanTarget;

    @Inject(method = "getDrops", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private void oresAndDrills$previewDepositDrop(
            ServerLevel level,
            BlockState state,
            BlockPos pos,
            CallbackInfoReturnable<List<ItemStack>> callback
    ) {
        oresAndDrills$clearPendingExtraction();
        if (!level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }

        ItemStack preview = OreDepositData.previewResult(level, pos);
        if (preview.isEmpty()) {
            return;
        }

        // The list must stay mutable: setReplace runs after Mekanism's capacity simulation and swaps
        // this preview for the exact (potentially multi-item, such as raw copper) mined result.
        oresAndDrills$pendingDrops = new ArrayList<>(1);
        oresAndDrills$pendingDrops.add(preview);
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
            return;
        }

        ItemStack mined = OreDepositData.mineOne(oresAndDrills$pendingLevel, pos);
        oresAndDrills$pendingDrops.clear();
        if (mined.isEmpty()) {
            oresAndDrills$clearPendingExtraction();
            callback.setReturnValue(false);
            return;
        }

        oresAndDrills$pendingDrops.add(mined);
        oresAndDrills$keepScanTarget = oresAndDrills$pendingLevel.getBlockState(pos)
                .is(ModBlocks.ORE_DEPOSIT.get());
        oresAndDrills$pendingDrops = null;
        oresAndDrills$pendingLevel = null;
        oresAndDrills$pendingPos = null;

        // The virtual extraction is the replacement operation. On the final unit mineOne changes the
        // block to the exhausted deposit and the normal scan bit is allowed to be cleared below.
        callback.setReturnValue(true);
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

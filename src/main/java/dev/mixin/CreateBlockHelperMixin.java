package dev.mixin;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import javax.annotation.Nullable;
import java.util.function.Consumer;

/**
 * Optional Create integration. Both stationary and contraption-mounted mechanical drills eventually
 * break blocks through {@code BlockHelper.destroyBlockAs}; intercepting that shared entry point lets
 * Create receive one virtual ore drop without replacing the persistent deposit with air.
 */
@Pseudo
@Mixin(targets = "com.simibubi.create.foundation.utility.BlockHelper", remap = false)
public abstract class CreateBlockHelperMixin {
    @Inject(method = "destroyBlockAs", at = @At("HEAD"), cancellable = true, require = 0, remap = false)
    private static void oresAndDrills$mineOreDeposit(
            Level level,
            BlockPos pos,
            @Nullable Player player,
            ItemStack usedTool,
            float effectChance,
            Consumer<ItemStack> droppedItemCallback,
            CallbackInfo callbackInfo
    ) {
        if (!(level instanceof ServerLevel serverLevel)
                || !serverLevel.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }

        ItemStack mined = OreDepositData.mineOne(serverLevel, pos);
        if (!mined.isEmpty()
                && serverLevel.getGameRules().getBoolean(GameRules.RULE_DOBLOCKDROPS)
                && !serverLevel.restoringBlockSnapshots) {
            droppedItemCallback.accept(mined);
        }

        callbackInfo.cancel();
    }
}

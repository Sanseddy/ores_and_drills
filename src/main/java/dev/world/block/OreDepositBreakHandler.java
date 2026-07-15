package dev.world.block;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.level.BlockEvent;

/**
 * The general-purpose ore-deposit mining entry point. {@code BlockEvent.BreakEvent} is what real players
 * AND any well-behaved automation (Create's drill/saw, quarries, etc.) fire and respect before actually
 * removing a block — cancelling it here and mining one ore unit ourselves means the deposit depletes
 * correctly no matter who or what is breaking it, without needing a mixin per mod.
 */
public final class OreDepositBreakHandler {
    private OreDepositBreakHandler() {
    }

    public static void onBreak(BlockEvent.BreakEvent event) {
        LevelAccessor level = event.getLevel();
        if (!(level instanceof ServerLevel serverLevel)) {
            return;
        }

        BlockState state = event.getState();
        if (!state.is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }

        Player player = event.getPlayer();
        if (player.isCreative()) {
            return;
        }

        if (player instanceof ServerPlayer serverPlayer
                && serverPlayer.blockActionRestricted(serverLevel, event.getPos(), serverPlayer.gameMode.getGameModeForPlayer())) {
            return;
        }

        event.setCanceled(true);
        OreDepositData.mineByPlayer(serverLevel, event.getPos(), state, player);
    }
}

package dev.world.block;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Pure mining-speed scaling shared by the client and server block-breaking paths. */
public final class OreDepositMiningSpeed {
    private OreDepositMiningSpeed() {
    }

    /**
     * Deposits with more than one unit take {@code remainingOre * 0.5} times as long to break as
     * one ordinary ore block. The final unit keeps the ordinary ore block duration.
     */
    static float progressMultiplier(int remainingOre) {
        return remainingOre > 1 ? 1.0F / (remainingOre * 0.5F) : 1.0F;
    }

    public static void onBreakSpeed(PlayerEvent.BreakSpeed event) {
        BlockPos pos = event.getPosition().orElse(null);
        if (pos == null) {
            return;
        }

        Level level = event.getEntity().level();
        if (!level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }

        int remainingOre = OreDepositData.remainingOreAt(level, pos);
        if (remainingOre > 1) {
            event.setNewSpeed(event.getNewSpeed() * progressMultiplier(remainingOre));
        }
    }
}

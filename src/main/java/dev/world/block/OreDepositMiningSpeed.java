package dev.world.block;

import dev.config.OreDepositConfig;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Pure mining-speed scaling shared by the client and server block-breaking paths. */
public final class OreDepositMiningSpeed {
    private OreDepositMiningSpeed() {
    }

    static double durationMultiplier(int oreCount, double exponentialGrowth, double maxExponentialMultiplier) {
        int normalizedCount = Math.max(1, oreCount);
        double exponent = exponentialGrowth * Math.max(0, normalizedCount - 2);
        double exponentialFactor = Math.min(maxExponentialMultiplier, Math.exp(exponent));
        return normalizedCount * exponentialFactor;
    }

    /** Converts the hybrid duration curve into the reciprocal multiplier required by BreakSpeed. */
    static float progressMultiplier(int oreCount, double exponentialGrowth, double maxExponentialMultiplier) {
        return (float)(1.0D / durationMultiplier(oreCount, exponentialGrowth, maxExponentialMultiplier));
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
        event.setNewSpeed(event.getNewSpeed() * progressMultiplier(
                remainingOre,
                OreDepositConfig.ORE_MINING_EXPONENTIAL_GROWTH.get(),
                OreDepositConfig.ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER.get()
        ));
    }
}

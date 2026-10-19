package dev.world.block;

import dev.config.OreDepositConfig;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

/** Mining-speed scaling shared by the client and server block-breaking paths; the curve is in {@link OreDepositMiningCurve}. */
public final class OreDepositMiningSpeed {
    private OreDepositMiningSpeed() {
    }

    /**
     * Hardness every non-player breaker sees for {@code state}: like a player, it mines the deposit's ore at
     * that ore's own hardness, stretched by the same remaining-ore curve as {@link #onBreakSpeed}. Any other
     * block keeps {@code hardness}. Only answered on the server thread, where the deposit data lives; other
     * threads and level views (worldgen regions, contraption worlds) keep the block's flat hardness.
     */
    public static float machineHardness(BlockState state, BlockGetter level, BlockPos pos, float hardness) {
        if (!(state.getBlock() instanceof OreDepositBlock)
                || !(level instanceof ServerLevel serverLevel)
                || !serverLevel.getServer().isSameThread()) {
            return hardness;
        }

        Block ore = OreDepositData.oreBlockAt(serverLevel, pos);
        float oreHardness = ore == null || ore == state.getBlock()
                ? hardness
                : ore.defaultBlockState().getDestroySpeed(level, pos);
        if (oreHardness < 0.0F) {
            return hardness;
        }

        return (float)(oreHardness * remainingOreMultiplier(serverLevel, pos));
    }

    /** How many times longer than a single ore the deposit at {@code pos} takes to mine its next unit. */
    public static double remainingOreMultiplier(ServerLevel level, BlockPos pos) {
        return OreDepositMiningCurve.durationMultiplier(
                OreDepositData.remainingOreAt(level, pos),
                OreDepositConfig.ORE_MINING_EXPONENTIAL_GROWTH.get(),
                OreDepositConfig.ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER.get()
        );
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
        event.setNewSpeed(event.getNewSpeed() * OreDepositMiningCurve.progressMultiplier(
                remainingOre,
                OreDepositConfig.ORE_MINING_EXPONENTIAL_GROWTH.get(),
                OreDepositConfig.ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER.get()
        ));
    }
}

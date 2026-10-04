package dev.world.level.levelgen;

import dev.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps generated deposit blocks from being overwritten by features decorated later in neighbouring
 * chunks. Structures are exempt: deposits move away from planned structures instead, so a structure that
 * still reaches a deposit (for example one planned beyond the readable radius) is built as designed.
 */
public final class DepositWorldgenGuard {
    private static final ThreadLocal<int[]> STRUCTURE_DEPTH = ThreadLocal.withInitial(() -> new int[1]);

    private DepositWorldgenGuard() {
    }

    public static void enterStructure() {
        STRUCTURE_DEPTH.get()[0]++;
    }

    public static void exitStructure() {
        int[] depth = STRUCTURE_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    /** True when this worldgen write would replace a deposit block and must be skipped. */
    public static boolean blocksWrite(WorldGenRegion region, BlockPos pos, BlockState replacement) {
        if (STRUCTURE_DEPTH.get()[0] > 0
                || replacement.is(ModBlocks.ORE_DEPOSIT.get())
                || replacement.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())
                || region.isOutsideBuildHeight(pos)) {
            return false;
        }
        // Outside the writable 3x3 chunks vanilla rejects the write itself; reading there could crash.
        ChunkPos center = region.getCenter();
        if (Math.max(Math.abs((pos.getX() >> 4) - center.x), Math.abs((pos.getZ() >> 4) - center.z))
                > OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS) {
            return false;
        }
        return region.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get());
    }
}

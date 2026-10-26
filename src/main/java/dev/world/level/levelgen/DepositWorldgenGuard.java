package dev.world.level.levelgen;

import dev.registry.ModBlocks;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Keeps generated deposit blocks from being overwritten by features decorated later in neighbouring
 * chunks. Structures are exempt: deposits move away from planned structures instead, so a structure
 * that still reaches a deposit (for example one planned beyond the readable radius) is built as
 * designed.
 */
public final class DepositWorldgenGuard {
    private static final ThreadLocal<int[]> STRUCTURE_DEPTH =
            ThreadLocal.withInitial(() -> new int[1]);
    private static final Map<ServerLevel, Map<Long, BitSet>> GRAVEL_MASKS =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());

    private DepositWorldgenGuard() {}

    public static void enterStructure() {
        STRUCTURE_DEPTH.get()[0]++;
    }

    public static void exitStructure() {
        int[] depth = STRUCTURE_DEPTH.get();
        if (depth[0] > 0) {
            depth[0]--;
        }
    }

    /**
     * Read-only hot path: never discover deposits, evaluate noise or wait for a plan loader here.
     */
    public static boolean blocksGravel(WorldGenLevel level, BlockPos pos) {
        if (STRUCTURE_DEPTH.get()[0] > 0 || level.isOutsideBuildHeight(pos)) return false;
        ServerLevel server = level.getLevel();
        ChunkPos chunk = new ChunkPos(pos);
        // Forced plans can appear after a mask was cached, so consult their existing index live.
        for (var plan : ConfirmedDepositIndex.get(server).forcedPlansFor(chunk))
            if (contains(plan, pos)) return true;
        synchronized (GRAVEL_MASKS) {
            Map<Long, BitSet> masks = GRAVEL_MASKS.get(server);
            BitSet mask = masks == null ? null : masks.get(chunk.toLong());
            return mask != null
                    && mask.get(
                            ((pos.getY() - server.getMinBuildHeight()) << 8)
                                    | ((pos.getZ() & 15) << 4)
                                    | (pos.getX() & 15));
        }
    }

    /**
     * Publish an already selected plan, once per slice; the gravel hook does no planning of its
     * own.
     */
    static void protectPlan(ServerLevel level, OreDepositFeature.CompactPlan plan, ChunkPos chunk) {
        BitSet bits = new BitSet();
        BlockPos origin = plan.candidate().center();
        for (int index : plan.indices(chunk)) {
            var cell = plan.cells().get(index);
            int x = origin.getX() + cell.x(),
                    y = origin.getY() + cell.y(),
                    z = origin.getZ() + cell.z();
            if (!level.isOutsideBuildHeight(y))
                bits.set(((y - level.getMinBuildHeight()) << 8) | ((z & 15) << 4) | (x & 15));
        }
        if (bits.isEmpty()) return;
        synchronized (GRAVEL_MASKS) {
            Map<Long, BitSet> masks =
                    GRAVEL_MASKS.computeIfAbsent(
                            level, ignored -> new LinkedHashMap<>(64, .75F, true));
            masks.computeIfAbsent(chunk.toLong(), ignored -> new BitSet()).or(bits);
            if (masks.size() > 64) masks.remove(masks.keySet().iterator().next());
        }
    }

    private static boolean contains(OreDepositFeature.CompactPlan plan, BlockPos pos) {
        DepositCandidate c = plan.candidate();
        int x = pos.getX() - c.center().getX(),
                y = pos.getY() - c.center().getY(),
                z = pos.getZ() - c.center().getZ();
        if (Math.abs(x) > c.radiusX()
                || Math.abs(y) > c.verticalRadius()
                || Math.abs(z) > c.radiusZ()) return false;
        // Binary search the already sorted x,z,y cells; no geometry recomputation per block.
        int lo = 0, hi = plan.cells().size() - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            var cell = plan.cells().get(mid);
            int order = Integer.compare(cell.x(), x);
            if (order == 0) order = Integer.compare(cell.z(), z);
            if (order == 0) order = Integer.compare(cell.y(), y);
            if (order == 0) return true;
            if (order < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return false;
    }

    /** True when this worldgen write would replace a deposit block and must be skipped. */
    public static boolean blocksWrite(WorldGenRegion region, BlockPos pos, BlockState replacement) {
        if (STRUCTURE_DEPTH.get()[0] > 0
                || replacement.is(ModBlocks.ORE_DEPOSIT.get())
                || replacement.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())
                || region.isOutsideBuildHeight(pos)) {
            return false;
        }
        // Outside the writable 3x3 chunks vanilla rejects the write itself; reading there could
        // crash.
        ChunkPos center = region.getCenter();
        if (Math.max(Math.abs((pos.getX() >> 4) - center.x), Math.abs((pos.getZ() >> 4) - center.z))
                > OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS) {
            return false;
        }
        return region.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())
                || replacement.is(Blocks.GRAVEL) && blocksGravel(region, pos);
    }
}

package dev.world.block;

import dev.world.level.levelgen.OreDepositData;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.LongUnaryOperator;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

/**
 * The part of an ore vein assembled into a physical Sable sub-level loses ore whenever the body is
 * knocked against blocks, like a valuable in R.E.P.O. losing value, without any loot. Seen from
 * above, the vein wears down from its outline in x/z inwards: only the outermost ring of columns
 * that still holds ore is hit, and its centre is left alone until the erosion reaches it. Exhausted
 * deposits stay part of the outline, so the columns behind them stay covered. The whole ring shares
 * each hit, but the point of impact is its epicentre: the harder the hit, the larger the share of
 * its own original ore a block there loses, and farther blocks lose less and less (see {@link
 * OreDepositImpactMath}). The rest of the vein left in the terrain is untouched.
 *
 * <p>Only speed lost counts, so falling, being thrown or pushed costs nothing. A crash and its
 * bounces take the body a few ticks, so after a hit the vein is briefly immune; a stronger reading
 * inside that window only adds the difference. Every ore in the body is its own vein and takes the
 * hit separately.
 */
public final class OreDepositImpactDamage {
    /** Long enough that a crash and the bounces right after it usually count as one impact. */
    static final int IMMUNITY_TICKS = 20;
    private static final int PRUNE_THRESHOLD = 256;
    /** The vein is worn down in plan view: only its outline in x/z is an edge. */
    private static final LongUnaryOperator[] HORIZONTAL_NEIGHBOURS =
            Direction.Plane.HORIZONTAL.stream()
                    .map(direction -> (LongUnaryOperator) pos -> BlockPos.offset(pos, direction))
                    .toArray(LongUnaryOperator[]::new);

    private static final Map<ServerLevel, Map<VeinKey, Hit>> LAST_HITS = new WeakHashMap<>();

    private OreDepositImpactDamage() {}

    /**
     * Applies an impact of the given speed to every vein the physical body {@code bodyId} carries
     * (see {@link OreDepositData#physicalVeins}). Must run outside the physics step.
     *
     * @param direction where the body was heading when it hit, in its own block coordinates: the
     *     side of a vein facing that way is where the impact landed
     */
    public static void onBodyImpact(
            ServerLevel level,
            long bodyId,
            double impactSpeed,
            Vec3 direction,
            Supplier<List<OreDepositData.PhysicalVein>> bodyVeins) {
        double share = OreDepositImpactMath.lossShare(impactSpeed);
        if (share <= 0.0D) {
            return;
        }
        for (OreDepositData.PhysicalVein vein : bodyVeins.get()) {
            double extraShare = claimShare(level, new VeinKey(bodyId, vein.identity()), share);
            if (extraShare > 0.0D) {
                erode(level, vein, direction, extraShare);
            }
        }
    }

    private static void erode(
            ServerLevel level, OreDepositData.PhysicalVein vein, Vec3 direction, double share) {
        List<OreDepositData.VeinBlock> blocks = vein.activeBlocks();
        int[] remaining = new int[blocks.size()];
        int[] initial = new int[blocks.size()];
        long[] columns = new long[blocks.size()];
        double[][] centres = new double[blocks.size()][];
        double[] rolls = new double[blocks.size()];
        for (int index = 0; index < remaining.length; index++) {
            BlockPos pos = blocks.get(index).pos();
            remaining[index] = blocks.get(index).remainingOre();
            initial[index] = blocks.get(index).initialOre();
            columns[index] = column(pos.asLong());
            centres[index] = centre(pos.asLong());
            rolls[index] = level.getRandom().nextDouble();
        }

        LongOpenHashSet footprint = new LongOpenHashSet();
        double[][] shapeCentres = new double[vein.shape().size()][];
        LongIterator shape = vein.shape().iterator();
        for (int index = 0; shape.hasNext(); index++) {
            long pos = shape.nextLong();
            footprint.add(column(pos));
            shapeCentres[index] = centre(pos);
        }
        int[] depths =
                OreDepositImpactMath.edgeDepths(
                        footprint.toLongArray(),
                        footprint::contains,
                        columns,
                        HORIZONTAL_NEIGHBOURS);
        double[] epicentre =
                OreDepositImpactMath.epicentre(
                        shapeCentres, new double[] {direction.x, direction.y, direction.z});
        double[] weights = OreDepositImpactMath.impactWeights(centres, epicentre);
        OreDepositData.depleteVeinByImpact(
                level,
                vein,
                OreDepositImpactMath.erodeOutline(
                        share, remaining, initial, depths, weights, rolls));
    }

    private static double[] centre(long pos) {
        return new double[] {
            BlockPos.getX(pos) + 0.5D, BlockPos.getY(pos) + 0.5D, BlockPos.getZ(pos) + 0.5D
        };
    }

    /** The vertical column a packed position stands in, as a position at y 0. */
    private static long column(long pos) {
        return BlockPos.asLong(BlockPos.getX(pos), 0, BlockPos.getZ(pos));
    }

    /**
     * The share this hit costs: all of it outside the immunity window; inside it the same impact
     * is still going on, so only what a stronger reading adds on top of the share already taken.
     */
    private static double claimShare(ServerLevel level, VeinKey key, double share) {
        long now = level.getGameTime();
        Map<VeinKey, Hit> lastHits = LAST_HITS.computeIfAbsent(level, ignored -> new HashMap<>());
        Hit last = lastHits.get(key);
        boolean immune = last != null && now - last.tick() < IMMUNITY_TICKS;
        double alreadyTaken = immune ? last.share() : 0.0D;
        if (share <= alreadyTaken) {
            return 0.0D;
        }
        if (lastHits.size() >= PRUNE_THRESHOLD) {
            lastHits.values().removeIf(hit -> now - hit.tick() >= IMMUNITY_TICKS);
        }
        lastHits.put(key, new Hit(immune ? last.tick() : now, share));
        return share - alreadyTaken;
    }

    private record VeinKey(long bodyId, OreDepositData.VeinIdentity identity) {}

    /** The impact under way: when it began and the largest share taken so far. */
    private record Hit(long tick, double share) {}
}

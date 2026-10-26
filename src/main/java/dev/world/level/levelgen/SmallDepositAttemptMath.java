package dev.world.level.levelgen;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.DoubleSupplier;

/**
 * Pure TINY/SMALL attempt math. Every attempt of an original ore placed feature is thinned to
 * {@code retention * frequency} expected candidates; each attempt picks one tier for all of its
 * candidates. Nothing here depends on other materials, so a common ore keeps its full share of the
 * original stream.
 */
final class SmallDepositAttemptMath {
    private static final long BRANCH_SALT = 0x4252414E43484741L;
    private static final long COUNT_SALT = 0x434F554E54474154L;
    private static final long TIER_SALT = 0x5449455253504C54L;
    private static final long CANDIDATE_SALT = 0x43414E4449444154L;
    private static final long CHAIN_SALT = 0x534D414C4C434841L;
    private static final long WALL_SALT = 0x57414C4C43484E43L;

    private SmallDepositAttemptMath() {}

    /** One original attempt after the selector branch roll and the retention gate. */
    record Decision(boolean sourceAttempt, int candidates, boolean tiny) {
        static final Decision NOT_AN_ATTEMPT = new Decision(false, 0, false);
    }

    /**
     * Expected candidates per original attempt; the frequency slider scales it without a 100%
     * ceiling.
     */
    static double expectedCandidates(double retention, double frequencyMultiplier) {
        double value = retention * frequencyMultiplier;
        return Double.isFinite(value) && value > 0.0D ? value : 0.0D;
    }

    /** {@code N = floor(lambda) + Bernoulli(frac(lambda))}, with {@code unit} uniform in [0, 1). */
    static int candidateCount(double lambda, double unit) {
        if (!(lambda > 0.0D)) {
            return 0;
        }
        double whole = Math.floor(lambda);
        return (int) whole + (unit < lambda - whole ? 1 : 0);
    }

    static Decision decide(long attemptSeed, double branchChance, double lambda, double tinyShare) {
        if (branchChance < 1.0D
                && unit(attemptSeed ^ BRANCH_SALT) >= Math.max(0.0D, branchChance)) {
            return Decision.NOT_AN_ATTEMPT;
        }
        int candidates = candidateCount(lambda, unit(attemptSeed ^ COUNT_SALT));
        boolean tiny = unit(attemptSeed ^ TIER_SALT) < tinyShare;
        return new Decision(true, candidates, tiny);
    }

    /**
     * Seed of one source feature's placement chain in one chunk. It deliberately omits the biome:
     * every per-biome copy of a source evaluates the same positions and keeps only those inside its
     * own biome, exactly like one shared vanilla feature behind a {@code BiomeFilter}.
     */
    static long chainSeed(
            long worldSeed, String dimension, String sourceSignature, int chunkX, int chunkZ) {
        long seed = worldSeed ^ CHAIN_SALT;
        seed ^= SeedMixer.hash64(dimension);
        seed ^= Long.rotateLeft(SeedMixer.hash64(sourceSignature), 23);
        seed ^= (long) chunkX * 341873128712L;
        seed ^= (long) chunkZ * 132897987541L;
        return SeedMixer.mix(seed);
    }

    static long attemptSeed(long chainSeed, int attemptIndex, long packedPosition) {
        return SeedMixer.mix(
                chainSeed
                        ^ ((long) attemptIndex * 0x9E3779B97F4A7C15L)
                        ^ Long.rotateLeft(packedPosition, 17));
    }

    static long candidateSeed(long attemptSeed, int candidateIndex) {
        return SeedMixer.mix(
                attemptSeed ^ CANDIDATE_SALT ^ ((long) candidateIndex * 0xC2B2AE3D27D4EB4FL));
    }

    /**
     * Chance that a candidate is laid on a cave wall. {@code relativeFrequency} is the ore's
     * original frequency divided by that of the dimension's most common ore; rarer ores stay buried
     * more often. Unknown frequency (no scan data yet) is treated as common.
     */
    static double wallChance(
            double relativeFrequency,
            double commonChance,
            double rareChance,
            double rarityDecades) {
        double common = clampUnit(commonChance);
        double rare = clampUnit(rareChance);
        if (Double.isNaN(relativeFrequency) || relativeFrequency < 0.0D) {
            return common;
        }
        if (relativeFrequency == 0.0D) {
            return rare;
        }
        if (relativeFrequency >= 1.0D) {
            return common;
        }
        if (!(rarityDecades > 0.0D) || Double.isInfinite(rarityDecades)) {
            return Double.isInfinite(rarityDecades) ? common : rare;
        }
        double position = 1.0D + Math.log10(relativeFrequency) / rarityDecades;
        return rare + (common - rare) * Math.max(0.0D, Math.min(1.0D, position));
    }

    private static double clampUnit(double value) {
        return Double.isFinite(value) ? Math.max(0.0D, Math.min(1.0D, value)) : 0.0D;
    }

    static boolean placesOnWall(long candidateSeed, double wallChance) {
        return unit(candidateSeed ^ WALL_SALT) < wallChance;
    }

    static double unit(long seed) {
        return SmallTierSeedMath.unitDouble(seed);
    }

    /**
     * Whole-deposit reserve: a uniform integer in {@code [minimum, maximum]} scaled by Richness,
     * never below one unit. It is not raised to the block count; callers trim the body to the
     * reserve instead.
     */
    static int sampleReserve(int minimum, int maximum, float richness, DoubleSupplier random) {
        int low = Math.max(1, Math.min(minimum, maximum));
        int high = Math.max(low, maximum);
        int base =
                low
                        + (int)
                                Math.min(
                                        high - low,
                                        Math.floor(random.getAsDouble() * (high - low + 1)));
        double scaled = base * (Float.isFinite(richness) ? Math.max(0.0F, richness) : 1.0F);
        return (int) Math.max(1L, Math.min(Integer.MAX_VALUE, Math.round(scaled)));
    }

    /**
     * Indices of the {@code limit} blocks closest to the body's centroid, in their original order.
     * Used when a vein has more blocks than units of reserve: every placed block must hold at least
     * one unit.
     */
    static int[] nearestToCentroid(int[] xs, int[] ys, int[] zs, int limit) {
        return keepForReserve(xs, ys, zs, new boolean[xs.length], limit);
    }

    /**
     * Indices of the {@code limit} blocks a reserve-limited vein keeps, in their original order.
     * Blocks exposed to air come first, so trimming never strips the face of a vein that a cave or
     * the surface cut open; the remaining places go to the blocks closest to the body's centroid.
     */
    static int[] keepForReserve(int[] xs, int[] ys, int[] zs, boolean[] exposed, int limit) {
        int count = xs.length;
        if (ys.length != count || zs.length != count || exposed.length != count) {
            throw new IllegalArgumentException("Coordinate arrays differ in length");
        }
        if (limit >= count) {
            int[] all = new int[count];
            Arrays.setAll(all, index -> index);
            return all;
        }
        if (limit <= 0) {
            return new int[0];
        }
        double cx = 0.0D, cy = 0.0D, cz = 0.0D;
        for (int index = 0; index < count; index++) {
            cx += xs[index];
            cy += ys[index];
            cz += zs[index];
        }
        cx /= count;
        cy /= count;
        cz /= count;
        double[] distance = new double[count];
        Integer[] order = new Integer[count];
        for (int index = 0; index < count; index++) {
            double dx = xs[index] - cx, dy = ys[index] - cy, dz = zs[index] - cz;
            distance[index] = dx * dx + dy * dy + dz * dz;
            order[index] = index;
        }
        Arrays.sort(
                order,
                Comparator.<Integer, Boolean>comparing(index -> !exposed[index])
                        .thenComparingDouble(index -> distance[index])
                        .thenComparingInt(index -> index));
        int[] selected = new int[limit];
        for (int index = 0; index < limit; index++) {
            selected[index] = order[index];
        }
        Arrays.sort(selected);
        return selected;
    }

    /**
     * Equal split of a reserve over blocks; every block gets at least one unit when {@code total >=
     * blocks}.
     */
    static int[] splitReserve(int total, int blocks) {
        if (blocks <= 0) {
            return new int[0];
        }
        if (total < blocks) {
            throw new IllegalArgumentException(
                    "Reserve " + total + " cannot give every one of " + blocks + " blocks a unit");
        }
        int[] amounts = new int[blocks];
        int share = total / blocks;
        int remainder = total - share * blocks;
        for (int index = 0; index < blocks; index++) {
            amounts[index] = share + (index < remainder ? 1 : 0);
        }
        return amounts;
    }
}

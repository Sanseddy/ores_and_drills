package dev.world.block;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.function.LongPredicate;
import java.util.function.LongUnaryOperator;

/**
 * Pure arithmetic behind {@link OreDepositImpactDamage}: how much a hit costs and which blocks of a
 * physical vein lose it. Positions are packed longs; the caller supplies how to reach neighbours.
 */
public final class OreDepositImpactMath {
    /** Impact speeds in blocks per second; Sable breaks fragile blocks from 4. */
    public static final double LIGHT_IMPACT_SPEED = 3.0D;

    public static final double MEDIUM_IMPACT_SPEED = 6.0D;
    public static final double HEAVY_IMPACT_SPEED = 10.0D;

    /**
     * Share of a block's original ore lost per light, medium and heavy hit right at the point of
     * impact; farther blocks lose less. Measured per block, so no block loses more than this in one
     * hit however big its vein is: a block at the epicentre lasts at least 13 heavy crashes.
     */
    static final double LIGHT_IMPACT_LOSS = 0.01D;

    static final double MEDIUM_IMPACT_LOSS = 0.03D;
    static final double HEAVY_IMPACT_LOSS = 0.08D;

    /** Distance in blocks from the point of impact at which a block takes half as much. */
    static final double IMPACT_FALLOFF = 3.0D;

    /** Products like 0.07 * 10000 land a hair below a whole number in floating point. */
    private static final double WHOLE_UNIT_TOLERANCE = 1.0E-9D;

    private OreDepositImpactMath() {}

    /**
     * Whether losing this much speed costs any ore; cheap enough to reject slow changes before any
     * lookup. Gaining speed (a negative loss) never does.
     */
    public static boolean isDamaging(double speedLost) {
        return speedLost >= LIGHT_IMPACT_SPEED;
    }

    /** Share of its original ore a block at the epicentre loses to a hit losing this much speed. */
    static double lossShare(double speedLost) {
        if (speedLost >= HEAVY_IMPACT_SPEED) {
            return HEAVY_IMPACT_LOSS;
        }
        if (speedLost >= MEDIUM_IMPACT_SPEED) {
            return MEDIUM_IMPACT_LOSS;
        }
        return speedLost >= LIGHT_IMPACT_SPEED ? LIGHT_IMPACT_LOSS : 0.0D;
    }

    /**
     * Whole ore units for an exact loss: the fraction becomes one more unit with that chance, so a
     * small block loses ore as often as its share says rather than a unit on every hit.
     *
     * @param roll a uniform random number in [0, 1)
     */
    static int roundUnits(double units, double roll) {
        if (units <= 0.0D) {
            return 0;
        }
        double whole = Math.floor(units + WHOLE_UNIT_TOLERANCE);
        double fraction = Math.max(0.0D, units - whole);
        return (int) Math.min(Integer.MAX_VALUE, whole + (roll < fraction ? 1.0D : 0.0D));
    }

    /**
     * Ore each block loses to one hit. Only the outermost layer that still holds ore is hit, so the
     * vein wears down from its edges and the layers behind it wait until that layer is empty.
     * There every block loses {@code share} of its original ore scaled by how near the epicentre it
     * is ({@code weights}), never more than it holds.
     *
     * @param rolls a uniform random number in [0, 1) per block, see {@link #roundUnits}
     */
    static int[] erodeOutline(
            double share,
            int[] remaining,
            int[] initial,
            int[] depths,
            double[] weights,
            double[] rolls) {
        int[] losses = new int[remaining.length];
        int outline = Integer.MAX_VALUE;
        for (int index = 0; index < remaining.length; index++) {
            if (remaining[index] > 0) {
                outline = Math.min(outline, depths[index]);
            }
        }
        for (int index = 0; index < remaining.length; index++) {
            if (remaining[index] > 0 && depths[index] == outline) {
                double original = Math.max(initial[index], remaining[index]);
                double exact = share * weights[index] * original;
                losses[index] = Math.min(remaining[index], roundUnits(exact, rolls[index]));
            }
        }
        return losses;
    }

    /**
     * Where an impact landed on a vein: the middle of its blocks lying furthest along the
     * direction the body was moving, within half a block of the foremost one.
     *
     * @param points centres of every block of the vein
     */
    static double[] epicentre(double[][] points, double[] direction) {
        double furthest = Double.NEGATIVE_INFINITY;
        for (double[] point : points) {
            furthest = Math.max(furthest, dot(point, direction));
        }
        double[] sum = new double[3];
        int count = 0;
        double length = Math.sqrt(dot(direction, direction));
        double tolerance = 0.5D * (length > 0.0D ? length : 1.0D);
        for (double[] point : points) {
            if (dot(point, direction) >= furthest - tolerance) {
                for (int axis = 0; axis < 3; axis++) {
                    sum[axis] += point[axis];
                }
                count++;
            }
        }
        for (int axis = 0; axis < 3; axis++) {
            sum[axis] /= Math.max(1, count);
        }
        return sum;
    }

    /**
     * How strongly each block feels an impact centred on {@code epicentre}: fully at the point,
     * half as much {@value #IMPACT_FALLOFF} blocks away, a little even across a large vein.
     */
    static double[] impactWeights(double[][] points, double[] epicentre) {
        double[] weights = new double[points.length];
        for (int index = 0; index < points.length; index++) {
            double squared = 0.0D;
            for (int axis = 0; axis < 3; axis++) {
                double offset = points[index][axis] - epicentre[axis];
                squared += offset * offset;
            }
            weights[index] = 1.0D / (1.0D + squared / (IMPACT_FALLOFF * IMPACT_FALLOFF));
        }
        return weights;
    }

    private static double dot(double[] a, double[] b) {
        return a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
    }

    /**
     * How deep inside the vein each target block lies: 0 on its surface (a face open to anything
     * that is not part of the vein), 1 just beneath it, and so on. Exhausted blocks belong to the
     * shape, so an emptied surface keeps covering the layer behind it.
     *
     * @param shape every block of the vein
     * @param inShape whether a position is one of {@code shape}
     * @param neighbours the six face neighbours of a position
     */
    static int[] edgeDepths(
            long[] shape, LongPredicate inShape, long[] targets, LongUnaryOperator[] neighbours) {
        Map<Long, Integer> depths = new HashMap<>(shape.length * 2);
        ArrayDeque<Long> queue = new ArrayDeque<>();
        for (long pos : shape) {
            for (LongUnaryOperator neighbour : neighbours) {
                if (!inShape.test(neighbour.applyAsLong(pos))) {
                    depths.put(pos, 0);
                    queue.add(pos);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            long pos = queue.poll();
            int next = depths.get(pos) + 1;
            for (LongUnaryOperator neighbour : neighbours) {
                long adjacent = neighbour.applyAsLong(pos);
                if (inShape.test(adjacent) && !depths.containsKey(adjacent)) {
                    depths.put(adjacent, next);
                    queue.add(adjacent);
                }
            }
        }
        int[] result = new int[targets.length];
        for (int index = 0; index < targets.length; index++) {
            result[index] = depths.getOrDefault(targets[index], 0);
        }
        return result;
    }
}

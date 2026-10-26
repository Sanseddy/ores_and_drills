package dev.world.block;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;
import java.util.function.LongUnaryOperator;
import org.junit.jupiter.api.Test;

class OreDepositImpactDamageTest {
    /** Test packing: 16 bits per axis, enough for the small shapes below. */
    private static final LongUnaryOperator[] NEIGHBOURS = {
        pos -> pos + 1, pos -> pos - 1,
        pos -> pos + (1L << 16), pos -> pos - (1L << 16),
        pos -> pos + (1L << 32), pos -> pos - (1L << 32)
    };

    @Test
    void slowContactCostsNothing() {
        assertFalse(OreDepositImpactMath.isDamaging(0.0D));
        assertFalse(OreDepositImpactMath.isDamaging(2.9D));
        assertEquals(0.0D, OreDepositImpactMath.lossShare(2.9D));
        assertEquals(0, OreDepositImpactMath.roundUnits(0.0D, 0.0D));
    }

    @Test
    void harderHitsCostMoreOfABlocksOre() {
        assertEquals(0.01D, OreDepositImpactMath.lossShare(3.0D));
        assertEquals(0.03D, OreDepositImpactMath.lossShare(6.0D));
        assertEquals(0.08D, OreDepositImpactMath.lossShare(10.0D));
        assertEquals(0.08D, OreDepositImpactMath.lossShare(40.0D));
    }

    @Test
    void speedingUpIsNoImpact() {
        assertFalse(OreDepositImpactMath.isDamaging(-12.0D));
        assertEquals(0.0D, OreDepositImpactMath.lossShare(-12.0D));
    }

    @Test
    void noBlockLosesMoreThanItsShareHoweverBigTheVein() {
        // A 150-unit block next to a 5000-unit one still loses only 8% of its own ore.
        assertArrayEquals(
                new int[] {400, 12, 12},
                OreDepositImpactMath.erodeOutline(
                        0.08D,
                        new int[] {5_000, 150, 150},
                        new int[] {5_000, 150, 150},
                        new int[] {0, 0, 0},
                        new double[] {1, 1, 1},
                        new double[] {0.99, 0.99, 0.99}));
    }

    @Test
    void aBlockAtTheEpicentreLastsThirteenHeavyHits() {
        int left = 150;
        int hits = 0;
        while (left > 0) {
            left -=
                    OreDepositImpactMath.erodeOutline(
                            0.08D,
                            new int[] {left},
                            new int[] {150},
                            new int[] {0},
                            new double[] {1},
                            new double[] {0.99})[0];
            hits++;
        }
        assertEquals(13, hits);
    }

    @Test
    void fartherBlocksLoseLess() {
        assertArrayEquals(
                new int[] {8, 4},
                OreDepositImpactMath.erodeOutline(
                        0.08D,
                        new int[] {100, 100},
                        new int[] {100, 100},
                        new int[] {0, 0},
                        new double[] {1.0, 0.5},
                        new double[] {0.99, 0.99}));
    }

    @Test
    void onlyTheOutermostLayerWithOreIsHit() {
        double[] ones = {1, 1, 1};
        double[] rolls = {0.0, 0.0, 0.0};
        assertArrayEquals(
                new int[] {1, 1, 0},
                OreDepositImpactMath.erodeOutline(
                        0.08D,
                        new int[] {10, 10, 50},
                        new int[] {10, 10, 50},
                        new int[] {0, 0, 1},
                        ones,
                        rolls));
        // Once the outline is exhausted, the layer behind it is the new outline.
        assertArrayEquals(
                new int[] {0, 0, 4},
                OreDepositImpactMath.erodeOutline(
                        0.08D,
                        new int[] {0, 0, 50},
                        new int[] {10, 10, 50},
                        new int[] {0, 0, 1},
                        ones,
                        rolls));
    }

    @Test
    void fractionsOfAUnitAreLostByChance() {
        // 3% of a 4-unit vein is 0.12 units: lost one time in about eight.
        assertEquals(1, OreDepositImpactMath.roundUnits(0.12D, 0.05D));
        assertEquals(0, OreDepositImpactMath.roundUnits(0.12D, 0.5D));
        assertEquals(2, OreDepositImpactMath.roundUnits(2.5D, 0.7D));
        assertEquals(3, OreDepositImpactMath.roundUnits(2.5D, 0.3D));
        // A product a hair below a whole number is that whole number, not a gamble on it.
        assertEquals(700, OreDepositImpactMath.roundUnits((0.08D - 0.01D) * 10_000, 0.9D));
    }

    @Test
    void impactLandsOnTheSideTheBodyWasHeading() {
        double[][] line = {{0.5, 0.5, 0.5}, {1.5, 0.5, 0.5}, {2.5, 0.5, 0.5}};
        assertArrayEquals(
                new double[] {2.5, 0.5, 0.5},
                OreDepositImpactMath.epicentre(line, new double[] {4, 0, 0}),
                1.0E-9);
        // Falling flat, the whole underside hits: its middle is the epicentre.
        assertArrayEquals(
                new double[] {1.5, 0.5, 0.5},
                OreDepositImpactMath.epicentre(line, new double[] {0, -9, 0}),
                1.0E-9);
    }

    @Test
    void blocksNearTheImpactTakeMoreButAllShare() {
        double[][] points = {{0, 0, 0}, {3, 0, 0}, {12, 0, 0}};
        double[] weights = OreDepositImpactMath.impactWeights(points, new double[] {0, 0, 0});
        assertEquals(1.0D, weights[0], 1.0E-9);
        assertEquals(0.5D, weights[1], 1.0E-9);
        assertTrue(weights[2] > 0.0D && weights[2] < weights[1]);
    }

    @Test
    void depthCountsLayersFromTheVeinSurface() {
        Set<Long> cube = cube(5);
        long[] targets = {pack(0, 0, 0), pack(1, 1, 1), pack(2, 2, 2), pack(2, 1, 3)};
        assertArrayEquals(new int[] {0, 1, 2, 1}, depths(cube, targets));
    }

    @Test
    void onlyBlocksMissingFromTheVeinBareTheLayerBehind() {
        // Exhausted blocks stay in the shape, so the centre remains covered by them.
        Set<Long> cube = cube(3);
        long[] centre = {pack(1, 1, 1)};
        assertArrayEquals(new int[] {1}, depths(cube, centre));
        // A block that is no part of the vein at all leaves the centre on the surface.
        cube.remove(pack(1, 2, 1));
        assertArrayEquals(new int[] {0}, depths(cube, centre));
    }

    @Test
    void flatVeinKeepsItsCentreInPlanView() {
        // Over all six faces a one-block-thick vein is surface everywhere; in x/z it has a centre.
        Set<Long> plate = new HashSet<>();
        for (int x = 0; x < 5; x++) {
            for (int z = 0; z < 5; z++) {
                plate.add(pack(x, 0, z));
            }
        }
        long[] targets = {pack(0, 0, 0), pack(1, 0, 1), pack(2, 0, 2)};
        LongUnaryOperator[] horizontal = {NEIGHBOURS[0], NEIGHBOURS[1], NEIGHBOURS[4], NEIGHBOURS[5]};
        assertArrayEquals(new int[] {0, 0, 0}, depths(plate, targets));
        assertArrayEquals(
                new int[] {0, 1, 2},
                OreDepositImpactMath.edgeDepths(
                        plate.stream().mapToLong(Long::longValue).toArray(),
                        plate::contains,
                        targets,
                        horizontal));
    }

    private static int[] depths(Set<Long> shape, long[] targets) {
        return OreDepositImpactMath.edgeDepths(
                shape.stream().mapToLong(Long::longValue).toArray(),
                shape::contains,
                targets,
                NEIGHBOURS);
    }

    private static Set<Long> cube(int size) {
        Set<Long> shape = new HashSet<>();
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                for (int z = 0; z < size; z++) {
                    shape.add(pack(x, y, z));
                }
            }
        }
        return shape;
    }

    private static long pack(int x, int y, int z) {
        return x + ((long) y << 16) + ((long) z << 32);
    }
}

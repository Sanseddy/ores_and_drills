package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.SplittableRandom;
import org.junit.jupiter.api.Test;

class SmallDepositAttemptMathTest {
    private static final double RETENTION =
            dev.config.OreDepositConfig.SMALL_DEPOSIT_ATTEMPT_RETENTION;
    private static final double TINY_SHARE = dev.config.OreDepositConfig.SMALL_DEPOSIT_TINY_SHARE;

    @Test
    void defaultSettingsKeepConfiguredShareSplitFortySixty() {
        assertEquals(0.0109375D, RETENTION, 1.0E-12D);
        assertEquals(0.40D, TINY_SHARE, 1.0E-12D);
        double lambda = SmallDepositAttemptMath.expectedCandidates(RETENTION, 1.0D);
        int attempts = 400_000, tiny = 0, small = 0, kept = 0;
        long chain =
                SmallDepositAttemptMath.chainSeed(
                        42L, "minecraft:overworld", "placed:minecraft:ore_iron_upper", 3, -7);
        for (int index = 0; index < attempts; index++) {
            var decision =
                    SmallDepositAttemptMath.decide(
                            SmallDepositAttemptMath.attemptSeed(chain, index, index * 31L),
                            1.0D,
                            lambda,
                            TINY_SHARE);
            assertTrue(decision.sourceAttempt());
            assertTrue(
                    decision.candidates() <= 1,
                    "One default attempt created more than one candidate");
            if (decision.candidates() == 1) {
                kept++;
                if (decision.tiny()) tiny++;
                else small++;
            }
        }
        // 1.09375% kept: 0.4375% TINY, 0.65625% SMALL, 98.90625% skipped.
        assertEquals(RETENTION, kept / (double) attempts, 0.003D);
        assertEquals(RETENTION * TINY_SHARE, tiny / (double) attempts, 0.003D);
        assertEquals(RETENTION * (1.0D - TINY_SHARE), small / (double) attempts, 0.003D);
        assertEquals(1.0D - RETENTION, 1.0D - kept / (double) attempts, 0.003D);
    }

    @Test
    void frequencySliderScalesWithoutCeiling() {
        for (double frequency : new double[] {0.1D, 0.5D, 1.0D, 2.0D, 3.0D, 6.0D}) {
            double lambda = SmallDepositAttemptMath.expectedCandidates(RETENTION, frequency);
            assertEquals(RETENTION * frequency, lambda, 1.0E-12D);
            SplittableRandom random = new SplittableRandom(Double.doubleToLongBits(frequency));
            long total = 0;
            int attempts = 200_000;
            for (int index = 0; index < attempts; index++) {
                int count = SmallDepositAttemptMath.candidateCount(lambda, random.nextDouble());
                assertTrue(
                        count == (int) Math.floor(lambda) || count == (int) Math.floor(lambda) + 1);
                total += count;
            }
            assertEquals(lambda, total / (double) attempts, Math.max(0.004D, lambda * 0.01D));
        }
        assertEquals(2, SmallDepositAttemptMath.candidateCount(2.1D, 0.5D));
        assertEquals(3, SmallDepositAttemptMath.candidateCount(2.1D, 0.05D));
        assertEquals(0, SmallDepositAttemptMath.candidateCount(0.0D, 0.0D));
        assertEquals(0, SmallDepositAttemptMath.candidateCount(Double.NaN, 0.0D));
    }

    @Test
    void selectorBranchProbabilityGatesOriginalAttemptsOnce() {
        double lambda = SmallDepositAttemptMath.expectedCandidates(RETENTION, 1.0D);
        int attempts = 200_000, original = 0, kept = 0;
        for (int index = 0; index < attempts; index++) {
            var decision =
                    SmallDepositAttemptMath.decide(SeedMixer.mix(index), 0.25D, lambda, TINY_SHARE);
            if (decision.sourceAttempt()) original++;
            kept += decision.candidates();
            if (!decision.sourceAttempt()) assertEquals(0, decision.candidates());
        }
        assertEquals(0.25D, original / (double) attempts, 0.004D);
        // The retention applies to attempts the selector really ran, not on top of a second branch
        // roll.
        assertEquals(RETENTION, kept / (double) original, 0.006D);
    }

    @Test
    void retentionDoesNotDependOnMaterialFrequency() {
        // Common and rare sources share the same per-attempt gate: no penalty relative to the
        // commonest ore.
        double lambda = SmallDepositAttemptMath.expectedCandidates(RETENTION, 1.0D);
        for (String source :
                new String[] {
                    "placed:minecraft:ore_coal_upper",
                    "placed:minecraft:ore_diamond",
                    "placed:minecraft:ore_emerald",
                    "direct:123"
                }) {
            int kept = 0, attempts = 100_000;
            for (int chunk = 0; chunk < attempts / 10; chunk++) {
                long chain =
                        SmallDepositAttemptMath.chainSeed(
                                7L, "minecraft:overworld", source, chunk, -chunk);
                for (int index = 0; index < 10; index++) {
                    kept +=
                            SmallDepositAttemptMath.decide(
                                            SmallDepositAttemptMath.attemptSeed(
                                                    chain, index, index),
                                            1.0D,
                                            lambda,
                                            TINY_SHARE)
                                    .candidates();
                }
            }
            assertEquals(RETENTION, kept / (double) attempts, 0.006D, source);
        }
    }

    @Test
    void seedsAreStableAndIndependentOfEvaluationOrder() {
        long first = SmallDepositAttemptMath.chainSeed(1L, "minecraft:overworld", "placed:a", 5, 6);
        assertEquals(
                first,
                SmallDepositAttemptMath.chainSeed(1L, "minecraft:overworld", "placed:a", 5, 6));
        assertNotEquals(
                first,
                SmallDepositAttemptMath.chainSeed(2L, "minecraft:overworld", "placed:a", 5, 6));
        assertNotEquals(
                first,
                SmallDepositAttemptMath.chainSeed(1L, "minecraft:the_nether", "placed:a", 5, 6));
        assertNotEquals(
                first,
                SmallDepositAttemptMath.chainSeed(1L, "minecraft:overworld", "placed:b", 5, 6));
        assertNotEquals(
                first,
                SmallDepositAttemptMath.chainSeed(1L, "minecraft:overworld", "placed:a", 6, 5));
        assertNotEquals(
                SmallDepositAttemptMath.attemptSeed(first, 0, 9L),
                SmallDepositAttemptMath.attemptSeed(first, 1, 9L));
        assertNotEquals(
                SmallDepositAttemptMath.attemptSeed(first, 0, 9L),
                SmallDepositAttemptMath.attemptSeed(first, 0, 10L));

        double lambda = 1.7D;
        boolean[] forward = new boolean[64];
        boolean[] backward = new boolean[64];
        for (int index = 0; index < 64; index++) {
            forward[index] =
                    SmallDepositAttemptMath.decide(
                                    SmallDepositAttemptMath.attemptSeed(first, index, index),
                                    1.0D,
                                    lambda,
                                    0.4D)
                            .tiny();
        }
        for (int index = 63; index >= 0; index--) {
            backward[index] =
                    SmallDepositAttemptMath.decide(
                                    SmallDepositAttemptMath.attemptSeed(first, index, index),
                                    1.0D,
                                    lambda,
                                    0.4D)
                            .tiny();
        }
        assertArrayEquals(forward, backward);
    }

    @Test
    void reservesStayInsideConfiguredRangesAndRichnessScalesThem() {
        SplittableRandom random = new SplittableRandom(11L);
        int[] tinySeen = new int[5];
        for (int index = 0; index < 20_000; index++) {
            int tiny = SmallDepositAttemptMath.sampleReserve(1, 4, 1.0F, random::nextDouble);
            int small = SmallDepositAttemptMath.sampleReserve(8, 16, 1.0F, random::nextDouble);
            assertTrue(tiny >= 1 && tiny <= 4, "TINY reserve " + tiny);
            assertTrue(small >= 8 && small <= 16, "SMALL reserve " + small);
            tinySeen[tiny]++;
        }
        for (int value = 1; value <= 4; value++)
            assertTrue(tinySeen[value] > 4_000, "Uneven TINY reserve " + value);
        assertEquals(1, SmallDepositAttemptMath.sampleReserve(1, 4, 0.1F, () -> 0.99D));
        assertEquals(24, SmallDepositAttemptMath.sampleReserve(1, 4, 6.0F, () -> 0.99D));
        assertEquals(96, SmallDepositAttemptMath.sampleReserve(8, 16, 6.0F, () -> 0.99D));
    }

    @Test
    void rarerOresAreLaidOnCaveWallsLessOften() {
        double common = dev.config.OreDepositConfig.SMALL_DEPOSIT_WALL_CHANCE_COMMON;
        double rare = dev.config.OreDepositConfig.SMALL_DEPOSIT_WALL_CHANCE_RARE;
        double decades = dev.config.OreDepositConfig.SMALL_DEPOSIT_WALL_RARITY_DECADES;
        assertTrue(common > rare, "Common ores must reach cave walls more often than rare ones");
        assertEquals(
                common, SmallDepositAttemptMath.wallChance(1.0D, common, rare, decades), 1.0E-12D);
        assertEquals(
                rare,
                SmallDepositAttemptMath.wallChance(Math.pow(10, -decades), common, rare, decades),
                1.0E-12D);
        assertEquals(
                rare, SmallDepositAttemptMath.wallChance(1.0E-9D, common, rare, decades), 1.0E-12D);
        assertEquals(
                (common + rare) / 2,
                SmallDepositAttemptMath.wallChance(
                        Math.pow(10, -decades / 2), common, rare, decades),
                1.0E-12D);
        assertEquals(
                common,
                SmallDepositAttemptMath.wallChance(Double.NaN, common, rare, decades),
                1.0E-12D,
                "Without scan data an ore is treated as common");
        double previous = 2.0D;
        for (double relative : new double[] {1.0D, 0.5D, 0.1D, 0.03D, 0.01D, 0.001D}) {
            double chance = SmallDepositAttemptMath.wallChance(relative, common, rare, decades);
            assertTrue(chance <= previous, "Wall chance must not grow with rarity at " + relative);
            previous = chance;
        }
        for (double chance : new double[] {0.2D, 0.55D, 0.9D}) {
            int walls = 0, rolls = 200_000;
            for (int index = 0; index < rolls; index++) {
                if (SmallDepositAttemptMath.placesOnWall(SeedMixer.mix(index * 7919L), chance))
                    walls++;
            }
            assertEquals(chance, walls / (double) rolls, 0.005D);
        }
    }

    @Test
    void trimmingKeepsBlocksExposedToCavesFirst() {
        // A straight vein whose far end (index 4) faces a cave: the centroid rule alone would cut
        // it first.
        int[] xs = {0, 1, 2, 3, 6};
        int[] ys = {0, 0, 0, 0, 0};
        int[] zs = {0, 0, 0, 0, 0};
        boolean[] exposed = {false, false, false, false, true};
        int[] kept = SmallDepositAttemptMath.keepForReserve(xs, ys, zs, exposed, 2);
        assertArrayEquals(new int[] {2, 4}, kept, "The exposed face must survive, then the core");
        assertFalse(
                Arrays.stream(SmallDepositAttemptMath.nearestToCentroid(xs, ys, zs, 2))
                        .anyMatch(i -> i == 4));

        boolean[] allExposed = {true, true, true, true, true};
        assertArrayEquals(
                SmallDepositAttemptMath.nearestToCentroid(xs, ys, zs, 3),
                SmallDepositAttemptMath.keepForReserve(xs, ys, zs, allExposed, 3),
                "Among exposed blocks the core still wins");
        assertArrayEquals(
                new int[] {0, 1, 2, 3, 4},
                SmallDepositAttemptMath.keepForReserve(xs, ys, zs, exposed, 9));
        assertThrows(
                IllegalArgumentException.class,
                () -> SmallDepositAttemptMath.keepForReserve(xs, ys, zs, new boolean[2], 1));
    }

    @Test
    void bodyIsTrimmedSoEveryBlockHoldsAPositiveUnit() {
        int[] xs = {0, 1, 2, 3, 10, 1};
        int[] ys = {0, 0, 0, 0, 0, 1};
        int[] zs = {0, 0, 0, 0, 0, 0};
        int[] selected = SmallDepositAttemptMath.nearestToCentroid(xs, ys, zs, 3);
        assertEquals(3, selected.length);
        assertFalse(
                Arrays.stream(selected).anyMatch(index -> index == 4),
                "The far outlier must be trimmed first");
        assertArrayEquals(
                new int[] {0, 1, 2, 3, 4, 5},
                SmallDepositAttemptMath.nearestToCentroid(xs, ys, zs, 10));

        for (int total = 1; total <= 16; total++) {
            for (int blocks = 1; blocks <= total; blocks++) {
                int[] amounts = SmallDepositAttemptMath.splitReserve(total, blocks);
                assertEquals(total, Arrays.stream(amounts).sum());
                assertTrue(Arrays.stream(amounts).allMatch(amount -> amount >= 1));
            }
        }
        assertThrows(
                IllegalArgumentException.class, () -> SmallDepositAttemptMath.splitReserve(2, 3));
    }
}

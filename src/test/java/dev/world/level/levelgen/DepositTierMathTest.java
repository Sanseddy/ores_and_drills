package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Random;
import org.junit.jupiter.api.Test;

class DepositTierMathTest {
    private static final double EPSILON = 1.0E-9D;

    private static final DepositTierMath.Parameters DEFAULTS =
            new DepositTierMath.Parameters(1, 720, 3.0D, 0.5D, 0.5D);

    @Test
    void globalCurvesProduceTheExactRequestedGenerationRangesWithoutTierTables() {
        DepositTierMath.TierDistribution distribution = DepositTierMath.distribution(DEFAULTS);
        DepositTierMath.TierProfile tiny = distribution.profiles()[DepositTier.TINY.ordinal()];
        DepositTierMath.TierProfile small = distribution.profiles()[DepositTier.SMALL.ordinal()];
        DepositTierMath.TierProfile medium = distribution.profiles()[DepositTier.MEDIUM.ordinal()];
        DepositTierMath.TierProfile large = distribution.profiles()[DepositTier.LARGE.ordinal()];

        assertProfileRange(tiny, 1, 2, 1, 4);
        assertProfileRange(small, 2, 6, 8, 16);
        assertProfileRange(medium, 72, 168, 4_000, 10_000);
        assertProfileRange(large, 320, 720, 40_000, 160_000);

        for (DepositTierMath.TierProfile profile : distribution.profiles()) {
            assertTrue(profile.minimumBlockCount() >= DEFAULTS.minimumPossibleDepositBlocks());
            assertTrue(profile.maximumBlockCount() <= DEFAULTS.maximumSafeDepositBlocks());
            assertTrue(profile.minimumBlockCount() <= profile.maximumBlockCount());
            assertTrue(profile.minimumTotalOre() >= Math.round(profile.characteristicBlockCount()));
        }
    }

    @Test
    void densityAndSpawnFrequencyMoveInOppositeDirections() {
        DepositTierMath.TierDistribution distribution = DepositTierMath.distribution(DEFAULTS);
        for (int index = 1; index < DepositTier.values().length; index++) {
            assertTrue(
                    distribution.profiles()[index].characteristicOreDensity()
                            > distribution.profiles()[index - 1].characteristicOreDensity());
            assertTrue(distribution.spawnWeights()[index] < distribution.spawnWeights()[index - 1]);
            assertTrue(distribution.shares()[index] < distribution.shares()[index - 1]);
        }
    }

    @Test
    void actualReserveUsesActualBlockCountAndAlwaysFundsEveryBlock() {
        DepositTierMath.TierProfile medium = DepositTierMath.profile(DepositTier.MEDIUM, DEFAULTS);
        DepositTierMath.OreRange minimumSize = DepositTierMath.oreRange(72, medium);
        DepositTierMath.OreRange maximumSize = DepositTierMath.oreRange(168, medium);

        assertEquals(4_000, minimumSize.minimumTotalOre());
        assertEquals(10_000, minimumSize.maximumTotalOre());
        assertEquals(4_000, maximumSize.minimumTotalOre());
        assertEquals(10_000, maximumSize.maximumTotalOre());
        assertTrue(maximumSize.characteristicTotalOre() > minimumSize.characteristicTotalOre());
    }

    @Test
    void characteristicSamplerStaysInsideCalculatedRange() {
        Random random = new Random(0xC0FFEE);
        for (int sample = 0; sample < 10_000; sample++) {
            long value =
                    DepositTierMath.sampleAroundCharacteristicValue(
                            10, 100, 25.0D, random::nextDouble);
            assertTrue(value >= 10 && value <= 100);
        }
    }

    @Test
    void actualGenerationSamplingStaysInsideEveryRequestedTierContract() {
        Random random = new Random(0x51A7E);
        for (DepositTier tier : DepositTier.values()) {
            DepositTierMath.TierProfile profile = DepositTierMath.profile(tier, DEFAULTS);
            for (int sample = 0; sample < 10_000; sample++) {
                int blocks = DepositTierMath.sampleBlockCount(profile, random::nextDouble);
                DepositTierMath.OreRange range = DepositTierMath.oreRange(blocks, profile);
                long sampledOre = DepositTierMath.sampleTotalOre(range, random::nextDouble);
                long finalOreAfterPerBlockFloor = Math.max(blocks, sampledOre);

                assertTrue(blocks >= profile.minimumBlockCount());
                assertTrue(blocks <= profile.maximumBlockCount());
                assertTrue(finalOreAfterPerBlockFloor >= profile.minimumTotalOre());
                assertTrue(finalOreAfterPerBlockFloor <= profile.maximumTotalOre());
            }
        }
    }

    @Test
    void oreDistributionIsExactAndMakesHigherWeightBlocksRicher() {
        int[] amounts = DepositTierMath.distributeOre(101, new double[] {1.0D, 0.5D, 0.1D});

        assertEquals(101, Arrays.stream(amounts).sum());
        assertTrue(amounts[0] > amounts[1]);
        assertTrue(amounts[1] > amounts[2]);
        assertTrue(Arrays.stream(amounts).allMatch(amount -> amount >= 1));
    }

    @Test
    void dataDrivenSmallTierRetentionLeavesMediumAndLargeUntouched() {
        for (DepositTier tier : DepositTier.values()) {
            for (double original : new double[] {0.0D, 0.01D, 0.5D, 1.0D}) {
                double retained =
                        DepositTierMath.retainedTierCandidateProbability(tier, original, 0.5D);
                assertEquals(
                        original * (tier.ordinal() < DepositTier.MEDIUM.ordinal() ? 0.5D : 1.0D),
                        retained,
                        EPSILON);
            }
        }
    }

    private static void assertProfileRange(
            DepositTierMath.TierProfile profile,
            int minimumBlocks,
            int maximumBlocks,
            long minimumOre,
            long maximumOre) {
        assertEquals(minimumBlocks, profile.minimumBlockCount());
        assertEquals(maximumBlocks, profile.maximumBlockCount());
        assertEquals(minimumOre, profile.minimumTotalOre());
        assertEquals(maximumOre, profile.maximumTotalOre());
    }
}

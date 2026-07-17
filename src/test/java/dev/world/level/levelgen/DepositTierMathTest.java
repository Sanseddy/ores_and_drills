package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepositTierMathTest {
    private static final double EPSILON = 1.0E-9D;

    private static final DepositTierMath.Parameters DEFAULTS = new DepositTierMath.Parameters(
            1, 720,
            3.0D, 0.5D, 0.5D
    );

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
            assertTrue(distribution.profiles()[index].characteristicOreDensity()
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
            long value = DepositTierMath.sampleAroundCharacteristicValue(10, 100, 25.0D, random::nextDouble);
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
    void maxFrequencySaturatesTheSmallTierGateWithoutDuplicatingFeatureCopies() {
        assertEquals(0.16D, DepositTierMath.smallTierGateProbability(0.5D, 0.32D, 1.0D), 1.0E-9D);
        assertEquals(0.96D, DepositTierMath.smallTierGateProbability(0.5D, 0.32D, 6.0D), 1.0E-9D);
        assertEquals(1.0D, DepositTierMath.smallTierGateProbability(1.0D, 0.32D, 6.0D), 1.0E-9D);
    }

    @Test
    void expectedFrequencyBecomesOneSeedCandidatePerTierCell() {
        assertEquals(0.125D, DepositTierMath.cellCandidateProbability(0.125D, 1, 1.0D), 1.0E-9D);
        assertEquals(0.5D, DepositTierMath.cellCandidateProbability(0.125D, 2, 1.0D), 1.0E-9D);
        assertEquals(1.0D, DepositTierMath.cellCandidateProbability(0.5D, 2, 6.0D), 1.0E-9D);
        assertEquals(0.0D, DepositTierMath.cellCandidateProbability(Double.NaN, 2, 1.0D), 1.0E-9D);
    }

    @Test
    void smallTierWorkloadBudgetHasAHardMaximum() {
        assertEquals(8, DepositTierMath.smallAttemptBudget(8.0D, 12));
        assertEquals(12, DepositTierMath.smallAttemptBudget(48.0D, 12));
        assertEquals(1, DepositTierMath.smallAttemptBudget(0.1D, 12));
    }

    private static final DepositTierMath.RarityParameters RARITY_DEFAULTS = new DepositTierMath.RarityParameters(
            0.30D, 0.80D, 1.40D, 1.50D, 0.45D, 0.000001D
    );

    @Test
    void smallTiersRetainMostOfACommonMaterialsOwnOriginalFrequencyButSuppressRarerOnes() {
        DepositTierMath.SmallTierParameters parameters = new DepositTierMath.SmallTierParameters(
                0.80D, 0.0D, 0.75D
        );
        DepositTierMath.TierDistribution distribution = DepositTierMath.distribution(DEFAULTS);
        DepositTierMath.SmallTierPlan common = DepositTierMath.smallTierPlan(
                20.0D, 20.0D, distribution, parameters, RARITY_DEFAULTS
        );
        DepositTierMath.SmallTierPlan rare = DepositTierMath.smallTierPlan(
                0.2D, 20.0D, distribution, parameters, RARITY_DEFAULTS
        );

        assertEquals(0.80D, common.retainedFraction(), EPSILON);
        assertTrue(rare.retainedFraction() < common.retainedFraction());
        assertTrue(common.expectedAttempts()[DepositTier.TINY.ordinal()]
                > common.expectedAttempts()[DepositTier.SMALL.ordinal()]);
        assertTrue(rare.expectedAttempts()[DepositTier.TINY.ordinal()] > 0.0D);
        assertTrue(rare.expectedAttempts()[DepositTier.SMALL.ordinal()] > 0.0D);
    }

    @Test
    void smallTierGateDoesNotDivideFrequencyByRegisteredMaterialCount() {
        DepositTierMath.SmallTierParameters parameters = new DepositTierMath.SmallTierParameters(
                0.80D, 0.65D, 0.75D
        );
        DepositTierMath.SmallTierPlan plan = DepositTierMath.smallTierPlan(
                10.0D, 20.0D, DepositTierMath.distribution(DEFAULTS), parameters, RARITY_DEFAULTS
        );

        assertEquals(10.0D * plan.retainedFraction(), plan.combinedExpectedAttempts(), EPSILON);
        assertTrue(plan.gateProbabilities()[DepositTier.TINY.ordinal()] > 0.0D);
        assertTrue(plan.gateProbabilities()[DepositTier.SMALL.ordinal()] > 0.0D);
    }

    @Test
    void smallTierMinimumGuardStillAppliesWhenRequestedRetentionIsZero() {
        DepositTierMath.SmallTierPlan plan = DepositTierMath.smallTierPlan(
                0.25D,
                16.0D,
                DepositTierMath.distribution(DEFAULTS),
                new DepositTierMath.SmallTierParameters(0.0D, 0.65D, 0.75D),
                RARITY_DEFAULTS
        );

        assertEquals(0.65D, plan.retainedFraction(), EPSILON);
        assertTrue(plan.expectedAttempts()[DepositTier.TINY.ordinal()]
                > plan.expectedAttempts()[DepositTier.SMALL.ordinal()]);
    }

    @Test
    void rarerMaterialRetainsALargerShareOfItsOwnBudgetThanACommonOneInTinyAndSmall() {
        DepositTierMath.SmallTierParameters parameters = new DepositTierMath.SmallTierParameters(
                0.80D, 0.0D, 0.75D
        );
        DepositTierMath.TierDistribution distribution = DepositTierMath.distribution(DEFAULTS);
        DepositTierMath.SmallTierPlan almostCommon = DepositTierMath.smallTierPlan(
                18.0D, 20.0D, distribution, parameters, RARITY_DEFAULTS
        );
        DepositTierMath.SmallTierPlan genuinelyRare = DepositTierMath.smallTierPlan(
                0.5D, 20.0D, distribution, parameters, RARITY_DEFAULTS
        );

        assertTrue(genuinelyRare.retainedFraction() < almostCommon.retainedFraction());
    }

    @Test
    void rareOreRarityCurveIsNotMaskedByTheSmallTierSafetyFloor() {
        DepositTierMath.SmallTierPlan rare = DepositTierMath.smallTierPlan(
                10.0D,
                20.0D,
                DepositTierMath.distribution(DEFAULTS),
                new DepositTierMath.SmallTierParameters(0.85D, 0.0125D, 0.90D),
                new DepositTierMath.RarityParameters(2.5D, 10.0D, 1.0D, 1.0D, 0.45D, 0.000001D)
        );

        assertTrue(rare.retainedFraction() >= 0.0125D);
        assertTrue(rare.retainedFraction() < 0.125D);
    }

    @Test
    void fractionalFrequencyMultiplierIsNotRoundedAway() {
        assertEquals(0, DepositTierMath.splitExpectedMultiplier(0.0D).length);
        assertEquals(0.25D, Arrays.stream(DepositTierMath.splitExpectedMultiplier(0.25D)).sum(), EPSILON);
        assertEquals(1.75D, Arrays.stream(DepositTierMath.splitExpectedMultiplier(1.75D)).sum(), EPSILON);
        assertEquals(3.0D, Arrays.stream(DepositTierMath.splitExpectedMultiplier(3.0D)).sum(), EPSILON);
        assertEquals(2, DepositTierMath.splitExpectedMultiplier(1.75D).length);
    }

    private static void assertProfileRange(
            DepositTierMath.TierProfile profile,
            int minimumBlocks,
            int maximumBlocks,
            long minimumOre,
            long maximumOre
    ) {
        assertEquals(minimumBlocks, profile.minimumBlockCount());
        assertEquals(maximumBlocks, profile.maximumBlockCount());
        assertEquals(minimumOre, profile.minimumTotalOre());
        assertEquals(maximumOre, profile.maximumTotalOre());
    }
}

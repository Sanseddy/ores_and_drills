package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.config.OreDepositConfig;
import org.junit.jupiter.api.Test;

class OreGenerationWeightsTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void adaptiveFormulaSuppressesRareTailInSmallTiersAndBoostsItInLargeTiers() {
        double maximumOriginalFrequency = 20.0D;
        double rareOriginalFrequency = 1.0D;
        DepositTierMath.RarityParameters parameters =
                new DepositTierMath.RarityParameters(0.04D, 4.0D, 0.45D, 0.000001D);

        DepositTierMath.RarityWeight tiny =
                DepositTierMath.adaptiveTierOreWeight(
                        DepositTier.TINY,
                        rareOriginalFrequency,
                        maximumOriginalFrequency,
                        parameters);
        DepositTierMath.RarityWeight small =
                DepositTierMath.adaptiveTierOreWeight(
                        DepositTier.SMALL,
                        rareOriginalFrequency,
                        maximumOriginalFrequency,
                        parameters);
        DepositTierMath.RarityWeight medium =
                DepositTierMath.adaptiveTierOreWeight(
                        DepositTier.MEDIUM,
                        rareOriginalFrequency,
                        maximumOriginalFrequency,
                        parameters);
        DepositTierMath.RarityWeight large =
                DepositTierMath.adaptiveTierOreWeight(
                        DepositTier.LARGE,
                        rareOriginalFrequency,
                        maximumOriginalFrequency,
                        parameters);
        DepositTierMath.RarityWeight common =
                DepositTierMath.adaptiveTierOreWeight(
                        DepositTier.LARGE,
                        maximumOriginalFrequency,
                        maximumOriginalFrequency,
                        parameters);

        assertEquals(tiny, small);
        assertTrue(small.weight() < rareOriginalFrequency);
        assertTrue(medium.weight() > rareOriginalFrequency);
        assertTrue(large.weight() > medium.weight());
        assertTrue(large.weight() <= maximumOriginalFrequency);
        assertEquals(maximumOriginalFrequency, common.weight(), EPSILON);
    }

    @Test
    void productionSettingsGiveRareOresAStrongMediumAndLargeBoost() {
        double maximumOriginalFrequency = 20.0D;
        double rareOriginalFrequency = 1.0D;
        DepositTierMath.RarityParameters parameters = OreGenerationWeights.rarityParameters();

        DepositTierMath.RarityWeight medium =
                DepositTierMath.adaptiveTierOreWeight(
                        DepositTier.MEDIUM,
                        rareOriginalFrequency,
                        maximumOriginalFrequency,
                        parameters);
        DepositTierMath.RarityWeight large =
                DepositTierMath.adaptiveTierOreWeight(
                        DepositTier.LARGE,
                        rareOriginalFrequency,
                        maximumOriginalFrequency,
                        parameters);

        assertTrue(medium.weight() >= rareOriginalFrequency * 3.5D);
        assertTrue(large.weight() >= medium.weight() * 1.9D);
        assertTrue(large.weight() <= maximumOriginalFrequency);
    }

    @Test
    void smallTierPenaltyIsTheManualMiningTimeCurveOfTheRarityRatio() {
        var parameters = OreGenerationWeights.rarityParameters();
        double maximum = 110.0D;
        for (double frequency :
                new double[] {110.0D, 100.0D, 50.0D, 16.0D, 13.111111D, 4.5D, 1.0D, 0.01D}) {
            double ratio = maximum / frequency;
            double expectedPenalty =
                    ratio
                            * Math.min(
                                    OreDepositConfig.SMALL_DEPOSIT_RARITY_MAX_MULTIPLIER,
                                    Math.exp(
                                            OreDepositConfig.SMALL_DEPOSIT_RARITY_GROWTH
                                                    * Math.max(0.0D, ratio - 2.0D)));
            for (DepositTier tier : new DepositTier[] {DepositTier.TINY, DepositTier.SMALL}) {
                var rarity =
                        DepositTierMath.adaptiveTierOreWeight(tier, frequency, maximum, parameters);
                assertEquals(
                        maximum / expectedPenalty,
                        rarity.weight(),
                        1.0E-9D,
                        tier + " frequency=" + frequency);
                assertEquals(expectedPenalty, rarity.penalty(), 1.0E-6D * expectedPenalty);
            }
        }
    }

    @Test
    void smallTierRarityParametersLeaveMediumAndLargeWeightsExactly() {
        var previous = new DepositTierMath.RarityParameters(0.5D, 50.0D, 0.75D, 0.000001D);
        var current = OreGenerationWeights.rarityParameters();
        for (DepositTier tier : new DepositTier[] {DepositTier.MEDIUM, DepositTier.LARGE}) {
            for (double maximum : new double[] {0.01D, 1.0D, 20.0D, 100.0D}) {
                for (double relative :
                        new double[] {0.0D, 1.0E-8D, 0.001D, 0.05D, 0.25D, 0.5D, 1.0D}) {
                    assertEquals(
                            DepositTierMath.adaptiveTierOreWeight(
                                    tier, maximum * relative, maximum, previous),
                            DepositTierMath.adaptiveTierOreWeight(
                                    tier, maximum * relative, maximum, current));
                }
            }
        }
    }

    @Test
    void originalFrequencyUsesAttemptsAndBranchChanceOnly() {
        assertEquals(30.0D, OreSourceFrequencyMath.originalFrequency(30.0D, 1.0D), EPSILON);
        assertEquals(7.5D, OreSourceFrequencyMath.originalFrequency(30.0D, 0.25D), EPSILON);
    }

    @Test
    void spatialAvailabilityIsNotAnInputToOriginalFrequency() {
        double sourceAttemptsAfterRarityFilter = 30.0D / 5.0D;
        assertEquals(
                6.0D,
                OreSourceFrequencyMath.originalFrequency(sourceAttemptsAfterRarityFilter, 1.0D),
                EPSILON);
    }
}

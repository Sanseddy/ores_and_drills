package dev.world.level.levelgen;

import dev.config.OreDepositConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreGenerationWeightsTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void adaptiveFormulaSuppressesRareTailInSmallTiersAndBoostsItInLargeTiers() {
        double maximumOriginalFrequency = 20.0D;
        double rareOriginalFrequency = 1.0D;
        DepositTierMath.RarityParameters parameters = new DepositTierMath.RarityParameters(
                0.30D, 0.80D, 1.40D, 1.50D, 0.45D, 0.000001D
        );

        DepositTierMath.RarityWeight tiny = DepositTierMath.adaptiveTierOreWeight(
                DepositTier.TINY, rareOriginalFrequency, maximumOriginalFrequency, parameters);
        DepositTierMath.RarityWeight small = DepositTierMath.adaptiveTierOreWeight(
                DepositTier.SMALL, rareOriginalFrequency, maximumOriginalFrequency, parameters);
        DepositTierMath.RarityWeight medium = DepositTierMath.adaptiveTierOreWeight(
                DepositTier.MEDIUM, rareOriginalFrequency, maximumOriginalFrequency, parameters);
        DepositTierMath.RarityWeight large = DepositTierMath.adaptiveTierOreWeight(
                DepositTier.LARGE, rareOriginalFrequency, maximumOriginalFrequency, parameters);
        DepositTierMath.RarityWeight common = DepositTierMath.adaptiveTierOreWeight(
                DepositTier.LARGE, maximumOriginalFrequency, maximumOriginalFrequency, parameters);

        assertTrue(tiny.weight() < small.weight());
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

        DepositTierMath.RarityWeight medium = DepositTierMath.adaptiveTierOreWeight(
                DepositTier.MEDIUM, rareOriginalFrequency, maximumOriginalFrequency, parameters);
        DepositTierMath.RarityWeight large = DepositTierMath.adaptiveTierOreWeight(
                DepositTier.LARGE, rareOriginalFrequency, maximumOriginalFrequency, parameters);

        assertTrue(medium.weight() >= rareOriginalFrequency * 3.5D);
        assertTrue(large.weight() >= medium.weight() * 1.9D);
        assertTrue(large.weight() <= maximumOriginalFrequency);
    }

    @Test
    void strongerSmallTierPenaltyReducesRealRareOreChancesDespiteBothSafetyFloors() {
        var distribution = DepositTierMath.distribution(new DepositTierMath.Parameters(1, 720, 3.0D, 0.5D, 0.5D));
        var smallParameters = new DepositTierMath.SmallTierParameters(
                OreDepositConfig.SMALL_DEPOSIT_FREQUENCY_SHARE,
                OreDepositConfig.MIN_SMALL_DEPOSIT_FREQUENCY_SHARE,
                OreDepositConfig.SMALL_TO_TINY_FREQUENCY_RATIO);
        var rarityParameters = OreGenerationWeights.rarityParameters();
        for (double relative : new double[] {0.001D, 0.01D, 0.05D, 0.1D, 0.25D, 0.5D, 0.75D, 1.0D}) {
            double frequency = 20.0D * relative;
            double[] previousAttempts = previousSmallTierAttempts(frequency, 20.0D);
            var plan = DepositTierMath.smallTierPlan(frequency, 20.0D, distribution, smallParameters, rarityParameters);
            for (int index = 0; index < 2; index++) {
                DepositTier tier = DepositTier.byIndex(index);
                double multiplier = index == 0 ? 1.0D : OreDepositConfig.SMALL_DEPOSIT_FREQUENCY_MULTIPLIER;
                double previousChance = smallCellChance(tier, previousAttempts[index], multiplier);
                double currentChance = smallCellChance(tier, plan.expectedAttempts()[index], multiplier);
                assertTrue(currentChance > 0.0D);
                if (relative < 1.0D) {
                    assertTrue(currentChance <= previousChance, tier + " relative frequency=" + relative);
                    if (relative <= 0.5D) {
                        assertTrue(currentChance < previousChance, tier + " relative frequency=" + relative);
                    }
                    assertTrue(DepositTierMath.adaptiveTierOreWeight(tier, frequency, 20.0D, rarityParameters).weight()
                            < previousSmallTierWeight(index, frequency, 20.0D));
                } else {
                    assertEquals(previousChance, currentChance, 0.0D);
                }
                if (relative <= 0.1D) {
                    assertEquals(previousChance * 0.25D, currentChance, 1.0E-12D);
                }
            }
        }
    }

    @Test
    void strongerSmallTierPenaltyPreservesMediumAndLargeWeightsExactly() {
        var previous = new DepositTierMath.RarityParameters(3.5D, 15.0D, 1.0D, 1.0D, 0.75D, 0.000001D);
        var current = OreGenerationWeights.rarityParameters();
        for (DepositTier tier : new DepositTier[] {DepositTier.MEDIUM, DepositTier.LARGE}) {
            for (double maximum : new double[] {0.01D, 1.0D, 20.0D, 100.0D}) {
                for (double relative : new double[] {0.0D, 1.0E-8D, 0.001D, 0.05D, 0.25D, 0.5D, 1.0D}) {
                    assertEquals(
                            DepositTierMath.adaptiveTierOreWeight(tier, maximum * relative, maximum, previous),
                            DepositTierMath.adaptiveTierOreWeight(tier, maximum * relative, maximum, current));
                }
            }
        }
    }

    private static double smallCellChance(DepositTier tier, double attempts, double multiplier) {
        return DepositTierMath.retainedTierCandidateProbability(tier,
                DepositTierMath.cellCandidateProbability(attempts * multiplier, 2, 1.0D),
                OreDepositConfig.TINY_SMALL_SPAWN_RETENTION);
    }

    // Snapshot of the previous small-tier formula, including both floors. Comparing only the new
    // exponents would miss a change that has no effect on actual generation probabilities.
    private static double previousSmallTierWeight(int tier, double frequency, double maximum) {
        double relative = frequency / maximum;
        double influence = tier == 0 ? 1.0D : 0.5D;
        double exponent = 1.0D + 3.5D * influence + 15.0D * influence * (1.0D - relative);
        return maximum * Math.max(0.000001D, Math.pow(relative, exponent));
    }

    private static double[] previousSmallTierAttempts(double frequency, double maximum) {
        double[] attempts = {
                0.65D / 1.75D * previousSmallTierWeight(0, frequency, maximum),
                0.65D * 0.75D / 1.75D * previousSmallTierWeight(1, frequency, maximum)
        };
        double correction = Math.max(1.0D, frequency * 0.005D / (attempts[0] + attempts[1]));
        attempts[0] *= correction;
        attempts[1] *= correction;
        return attempts;
    }

    @Test
    void originalFrequencyUsesAttemptsAndBranchChanceOnly() {
        assertEquals(30.0D, OreSourceFrequencyMath.originalFrequency(30.0D, 1.0D), EPSILON);
        assertEquals(7.5D, OreSourceFrequencyMath.originalFrequency(30.0D, 0.25D), EPSILON);
    }

    @Test
    void spatialAvailabilityIsNotAnInputToOriginalFrequency() {
        double sourceAttemptsAfterRarityFilter = 30.0D / 5.0D;
        assertEquals(6.0D, OreSourceFrequencyMath.originalFrequency(
                sourceAttemptsAfterRarityFilter, 1.0D
        ), EPSILON);
    }
}

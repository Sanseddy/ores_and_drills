package dev.world.level.levelgen;

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
}

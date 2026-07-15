package dev.world.level.levelgen;

import java.util.Arrays;
import java.util.Comparator;
import java.util.function.DoubleSupplier;

/** Pure table-free math for tier size, ore density, reserves, spawn weights and spacing scale. */
final class DepositTierMath {
    /*
     * Four monotone curves replace a per-tier range table. Their global endpoints and sigmoid shape were
     * calibrated so the four currently ordered tiers resolve to the requested generation contract:
     * blocks 1-2, 2-6, 24-56, 100-256 and ore 1-4, 8-16, 400-1000, 2500-10000.
     * A tier is still evaluated only from its normalized ordinal position, so the implementation does not
     * accumulate TINY_MIN_*, SMALL_MAX_* constants and remains well-defined if another tier is inserted.
     */
    private static final RangeCurve MINIMUM_BLOCK_CURVE =
            new RangeCurve(1.0D, 100.0D, 7.04635905875494D, 0.5701611307530895D);
    private static final RangeCurve MAXIMUM_BLOCK_CURVE =
            new RangeCurve(2.0D, 256.0D, 5.004285221965073D, 0.5523119579472633D);
    private static final RangeCurve MINIMUM_TOTAL_ORE_CURVE =
            new RangeCurve(1.0D, 2_500.0D, 5.788809757681745D, 0.4837437023881306D);
    private static final RangeCurve MAXIMUM_TOTAL_ORE_CURVE =
            new RangeCurve(4.0D, 10_000.0D, 6.599956886783624D, 0.5539849271262954D);

    private DepositTierMath() {
    }

    static TierProfile profile(DepositTier tier, Parameters parameters) {
        double tierPosition = tier.normalizedPosition();
        int minimumBlockCount = Math.max(
                parameters.minimumPossibleDepositBlocks(),
                (int) sampleRangeCurve(MINIMUM_BLOCK_CURVE, tierPosition)
        );
        int maximumBlockCount = Math.min(
                parameters.maximumSafeDepositBlocks(),
                (int) sampleRangeCurve(MAXIMUM_BLOCK_CURVE, tierPosition)
        );
        maximumBlockCount = Math.max(minimumBlockCount, maximumBlockCount);
        double characteristicBlockCount = Math.sqrt((double) minimumBlockCount * maximumBlockCount);
        double blockRangeSpread = Math.sqrt(maximumBlockCount / (double) minimumBlockCount);
        double blockScalePosition = normalizeLogarithmically(
                characteristicBlockCount,
                Math.sqrt(MINIMUM_BLOCK_CURVE.minimum() * MAXIMUM_BLOCK_CURVE.minimum()),
                Math.sqrt(MINIMUM_BLOCK_CURVE.maximum() * MAXIMUM_BLOCK_CURVE.maximum())
        );

        long minimumTotalOre = sampleRangeCurve(MINIMUM_TOTAL_ORE_CURVE, tierPosition);
        long maximumTotalOre = Math.max(
                minimumTotalOre, sampleRangeCurve(MAXIMUM_TOTAL_ORE_CURVE, tierPosition)
        );
        double characteristicTotalOre = Math.sqrt((double) minimumTotalOre * maximumTotalOre);
        double characteristicOreDensity = characteristicTotalOre / characteristicBlockCount;
        double oreRangeSpread = Math.sqrt(maximumTotalOre / (double) minimumTotalOre);
        double densityPosition = normalizeLogarithmically(
                characteristicOreDensity,
                Math.sqrt(MINIMUM_TOTAL_ORE_CURVE.minimum() * MAXIMUM_TOTAL_ORE_CURVE.minimum())
                        / Math.sqrt(MINIMUM_BLOCK_CURVE.minimum() * MAXIMUM_BLOCK_CURVE.minimum()),
                Math.sqrt(MINIMUM_TOTAL_ORE_CURVE.maximum() * MAXIMUM_TOTAL_ORE_CURVE.maximum())
                        / Math.sqrt(MINIMUM_BLOCK_CURVE.maximum() * MAXIMUM_BLOCK_CURVE.maximum())
        );

        return new TierProfile(
                tier,
                tierPosition,
                blockScalePosition,
                characteristicBlockCount,
                blockRangeSpread,
                minimumBlockCount,
                maximumBlockCount,
                densityPosition,
                characteristicOreDensity,
                oreRangeSpread,
                characteristicTotalOre,
                minimumTotalOre,
                maximumTotalOre
        );
    }

    static TierDistribution distribution(Parameters parameters) {
        DepositTier[] tiers = DepositTier.values();
        TierProfile[] profiles = new TierProfile[tiers.length];
        double[] rawScales = new double[tiers.length];
        double minimumScale = Double.POSITIVE_INFINITY;
        double maximumScale = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < tiers.length; index++) {
            TierProfile profile = profile(tiers[index], parameters);
            profiles[index] = profile;
            double rawScale = Math.pow(profile.characteristicBlockCount(), nonNegative(parameters.blockCountSpawnInfluence()))
                    * Math.pow(profile.characteristicTotalOre(), nonNegative(parameters.totalOreSpawnInfluence()));
            rawScales[index] = Math.max(Double.MIN_NORMAL, rawScale);
            minimumScale = Math.min(minimumScale, rawScales[index]);
            maximumScale = Math.max(maximumScale, rawScales[index]);
        }

        double[] normalizedScales = new double[tiers.length];
        double[] spawnWeights = new double[tiers.length];
        double[] shares = new double[tiers.length];
        double totalWeight = 0.0D;
        for (int index = 0; index < tiers.length; index++) {
            normalizedScales[index] = normalizeLogarithmically(rawScales[index], minimumScale, maximumScale);
            spawnWeights[index] = Math.exp(-nonNegative(parameters.tierSpawnFalloff()) * normalizedScales[index]);
            totalWeight += spawnWeights[index];
        }
        for (int index = 0; index < tiers.length; index++) {
            shares[index] = totalWeight <= 0.0D ? 0.0D : spawnWeights[index] / totalWeight;
        }
        return new TierDistribution(profiles, rawScales, normalizedScales, spawnWeights, shares);
    }

    static SmallTierPlan smallTierPlan(
            double originalFrequency,
            double maximumOriginalFrequency,
            TierDistribution distribution,
            SmallTierParameters parameters,
            RarityParameters rarityParameters
    ) {
        int smallTierCount = Math.min(DepositTier.MEDIUM.ordinal(), distribution.profiles().length);
        double[] weights = new double[smallTierCount];
        double[] rarityExponents = new double[smallTierCount];
        double[] expectedAttempts = new double[smallTierCount];
        double[] gateProbabilities = new double[smallTierCount];
        if (smallTierCount == 0 || originalFrequency <= 0.0D || maximumOriginalFrequency <= 0.0D) {
            return new SmallTierPlan(weights, rarityExponents, expectedAttempts, gateProbabilities, 0.0D, 0.0D);
        }

        double tierRelativeFrequency = clamp(parameters.relativeFrequency(), 0.0D, 1.0D);
        double totalWeight = 0.0D;
        for (int index = 0; index < smallTierCount; index++) {
            weights[index] = Math.pow(tierRelativeFrequency, index);
            totalWeight += weights[index];
        }

        double retention = clamp(parameters.frequencyRetention(), 0.0D, 1.0D);
        for (int index = 0; index < smallTierCount; index++) {
            RarityWeight rarity = adaptiveTierOreWeight(
                    DepositTier.byIndex(index), originalFrequency, maximumOriginalFrequency, rarityParameters
            );
            rarityExponents[index] = rarity.adaptiveExponent();
            double rarityRatio = clamp(rarity.weight() / originalFrequency, 0.0D, 1.0D);
            double share = totalWeight <= 0.0D ? 1.0D / smallTierCount : weights[index] / totalWeight;
            expectedAttempts[index] = originalFrequency * retention * share * rarityRatio;
        }

        double combined = Arrays.stream(expectedAttempts).sum();
        double minimumCombined = originalFrequency * clamp(parameters.minimumRetention(), 0.0D, 1.0D);
        if (combined < minimumCombined) {
            if (combined > 0.0D) {
                double correction = minimumCombined / combined;
                for (int index = 0; index < expectedAttempts.length; index++) {
                    expectedAttempts[index] *= correction;
                }
            } else {
                for (int index = 0; index < expectedAttempts.length; index++) {
                    double share = totalWeight <= 0.0D ? 1.0D / smallTierCount : weights[index] / totalWeight;
                    expectedAttempts[index] = minimumCombined * share;
                }
            }
            combined = minimumCombined;
        }
        for (int index = 0; index < expectedAttempts.length; index++) {
            gateProbabilities[index] = clamp(expectedAttempts[index] / originalFrequency, 0.0D, 1.0D);
        }
        return new SmallTierPlan(
                weights,
                rarityExponents,
                expectedAttempts,
                gateProbabilities,
                combined,
                combined / originalFrequency
        );
    }

    /**
     * Splits a multiplier into full and fractional source-stream copies. Kept for diagnostics and
     * regression coverage; worldgen no longer materializes these copies as placed features.
     */
    static double[] splitExpectedMultiplier(double expectedMultiplier) {
        double safeMultiplier = Math.max(0.0D, expectedMultiplier);
        int guaranteed = (int) Math.floor(safeMultiplier);
        double fractional = safeMultiplier - guaranteed;
        double[] result = new double[guaranteed + (fractional > 0.0D ? 1 : 0)];
        Arrays.fill(result, 0, guaranteed, 1.0D);
        if (fractional > 0.0D) {
            result[result.length - 1] = fractional;
        }
        return result;
    }

    /** Shared safety bound for TINY/SMALL candidate attempts. */
    static int smallAttemptBudget(double configuredBudget, int hardMaximum) {
        int safeHardMaximum = Math.max(1, hardMaximum);
        if (!Double.isFinite(configuredBudget)) {
            return safeHardMaximum;
        }
        return Math.min(safeHardMaximum, Math.max(1, (int)Math.ceil(configuredBudget)));
    }

    /** Applies an ore Frequency multiplier without expanding the placed-feature graph. */
    static double smallTierGateProbability(
            double sourceProbability,
            double retentionGate,
            double frequencyMultiplier
    ) {
        double source = clamp(sourceProbability, 0.0D, 1.0D);
        double retention = clamp(retentionGate, 0.0D, 1.0D);
        double multiplier = Double.isFinite(frequencyMultiplier)
                ? clamp(frequencyMultiplier, 0.1D, 6.0D)
                : 1.0D;
        return Math.min(1.0D, source * retention * multiplier);
    }

    /**
     * Converts an automatically detected expected frequency per chunk into the single immutable
     * candidate owned by a tier cell. This removes dependence on the surrounding PlacedFeature random
     * stream while retaining the same expected frequency until a cell saturates at one candidate.
     */
    static double cellCandidateProbability(
            double expectedAttemptsPerChunk,
            int cellSizeChunks,
            double frequencyMultiplier
    ) {
        double expected = Double.isFinite(expectedAttemptsPerChunk)
                ? Math.max(0.0D, expectedAttemptsPerChunk)
                : 0.0D;
        int safeCellSize = Math.max(1, cellSizeChunks);
        double multiplier = Double.isFinite(frequencyMultiplier)
                ? clamp(frequencyMultiplier, 0.1D, 6.0D)
                : 1.0D;
        return clamp(expected * safeCellSize * safeCellSize * multiplier, 0.0D, 1.0D);
    }

    static OreRange oreRange(int actualBlockCount, TierProfile profile) {
        int safeBlockCount = Math.max(1, actualBlockCount);
        double sizeRatio = safeBlockCount / profile.characteristicBlockCount();
        double characteristicTotalOre = clamp(
                profile.characteristicTotalOre() * sizeRatio,
                profile.minimumTotalOre(),
                profile.maximumTotalOre()
        );
        return new OreRange(
                characteristicTotalOre,
                profile.minimumTotalOre(),
                profile.maximumTotalOre()
        );
    }

    static int sampleBlockCount(TierProfile profile, DoubleSupplier random) {
        return (int) sampleAroundCharacteristicValue(
                profile.minimumBlockCount(),
                profile.maximumBlockCount(),
                profile.characteristicBlockCount(),
                random
        );
    }

    static long sampleTotalOre(OreRange range, DoubleSupplier random) {
        return sampleAroundCharacteristicValue(
                range.minimumTotalOre(),
                range.maximumTotalOre(),
                range.characteristicTotalOre(),
                random
        );
    }

    /** Samples a triangular distribution whose mode is the characteristic value. */
    static long sampleAroundCharacteristicValue(long minimum, long maximum, double characteristic, DoubleSupplier random) {
        long boundedMaximum = Math.max(minimum, maximum);
        if (boundedMaximum == minimum) {
            return minimum;
        }
        double mode = clamp(characteristic, minimum, boundedMaximum);
        double span = boundedMaximum - (double) minimum;
        double modeFraction = (mode - minimum) / span;
        double unit = clamp(random.getAsDouble(), 0.0D, Math.nextDown(1.0D));
        double sampled = unit < modeFraction
                ? minimum + Math.sqrt(unit * span * (mode - minimum))
                : boundedMaximum - Math.sqrt((1.0D - unit) * span * (boundedMaximum - mode));
        return Math.max(minimum, Math.min(boundedMaximum, Math.round(sampled)));
    }

    /** One and only one adaptive material-weight formula for all tier lotteries. */
    static RarityWeight adaptiveTierOreWeight(
            DepositTier tier,
            double effectiveOriginalFrequency,
            double maximumEffectiveFrequency,
            RarityParameters parameters
    ) {
        if (maximumEffectiveFrequency <= 0.0D) {
            return new RarityWeight(0.0D, 0.0D, 0.0D, 1.0D, 0.0D);
        }
        double relativeFrequency = clamp(
                effectiveOriginalFrequency / maximumEffectiveFrequency,
                parameters.minimumRelativeWeight(), 1.0D
        );
        double smallBoundary = DepositTier.MEDIUM.normalizedPosition();
        double smallInfluence = smallBoundary <= 0.0D
                ? 0.0D
                : clamp(1.0D - tier.normalizedPosition() / smallBoundary, 0.0D, 1.0D);
        double rareTail = Math.pow(1.0D - relativeFrequency, positive(parameters.rareTailCurve()));
        double baseExponent;
        double adaptiveExponent;
        if (smallInfluence > 0.0D) {
            baseExponent = 1.0D + nonNegative(parameters.smallTierBaseRarityStrength()) * smallInfluence;
            double tailStrength = nonNegative(parameters.rareTailStrength())
                    * Math.pow(smallInfluence, positive(parameters.smallTierInfluenceCurve()));
            adaptiveExponent = baseExponent + tailStrength * rareTail;
        } else {
            baseExponent = 1.0D - nonNegative(parameters.largeTierRarityBoost()) * tier.normalizedPosition();
            adaptiveExponent = Math.max(0.01D, baseExponent);
            rareTail = 0.0D;
        }
        double weight = maximumEffectiveFrequency * Math.pow(relativeFrequency, adaptiveExponent);
        weight = Math.min(maximumEffectiveFrequency, weight);
        weight = Math.max(maximumEffectiveFrequency * parameters.minimumRelativeWeight(), weight);
        return new RarityWeight(relativeFrequency, rareTail, baseExponent, adaptiveExponent, weight);
    }

    static int calculateFillStage(long remainingOre, long minimumOre, long maximumOre, int stageCount) {
        if (remainingOre <= 0L) {
            return 0;
        }
        long safeMinimum = Math.max(1L, minimumOre);
        int safeStageCount = Math.max(1, stageCount);
        if (maximumOre <= safeMinimum) {
            return safeStageCount;
        }
        long clampedOre = Math.max(safeMinimum, Math.min(maximumOre, remainingOre));
        double normalized = Math.log(clampedOre / (double) safeMinimum)
                / Math.log(maximumOre / (double) safeMinimum);
        int stage = (int) Math.floor(normalized * safeStageCount) + 1;
        return Math.max(1, Math.min(safeStageCount, stage));
    }

    /** Exact integer allocation with a one-unit floor and rounding residue assigned to the richest blocks. */
    static int[] distributeOre(int finalTotalOre, double[] densityWeights) {
        int blockCount = densityWeights.length;
        if (blockCount == 0) {
            return new int[0];
        }
        int safeTotalOre = Math.max(blockCount, finalTotalOre);
        double[] sanitizedWeights = Arrays.stream(densityWeights)
                .map(weight -> Double.isFinite(weight) && weight > 0.0D ? weight : 0.0D)
                .toArray();
        double totalWeight = Arrays.stream(sanitizedWeights).sum();
        if (totalWeight <= 0.0D) {
            Arrays.fill(sanitizedWeights, 1.0D);
            totalWeight = blockCount;
        }

        int distributableOre = safeTotalOre - blockCount;
        int[] amounts = new int[blockCount];
        int assigned = blockCount;
        for (int index = 0; index < blockCount; index++) {
            double weight = sanitizedWeights[index];
            int proportional = (int) Math.floor(distributableOre * weight / totalWeight);
            amounts[index] = 1 + proportional;
            assigned += proportional;
        }

        Integer[] richestFirst = new Integer[blockCount];
        for (int index = 0; index < blockCount; index++) {
            richestFirst[index] = index;
        }
        Arrays.sort(richestFirst, Comparator.comparingDouble((Integer index) -> sanitizedWeights[index]).reversed());
        for (int remainder = safeTotalOre - assigned, index = 0; remainder > 0; remainder--, index++) {
            amounts[richestFirst[index % blockCount]]++;
        }
        return amounts;
    }

    static double geometricInterpolate(double minimum, double maximum, double position) {
        double safeMinimum = Math.max(Double.MIN_NORMAL, minimum);
        double safeMaximum = Math.max(safeMinimum, maximum);
        return Math.exp(Math.log(safeMinimum) + clamp(position, 0.0D, 1.0D)
                * (Math.log(safeMaximum) - Math.log(safeMinimum)));
    }

    static double normalizeLogarithmically(double value, double minimum, double maximum) {
        if (!Double.isFinite(value) || !Double.isFinite(minimum) || !Double.isFinite(maximum)
                || value <= 0.0D || minimum <= 0.0D || maximum <= minimum) {
            return 0.0D;
        }
        return clamp((Math.log(value) - Math.log(minimum)) / (Math.log(maximum) - Math.log(minimum)), 0.0D, 1.0D);
    }

    private static long sampleRangeCurve(RangeCurve curve, double position) {
        double safePosition = clamp(position, 0.0D, 1.0D);
        if (safePosition <= 0.0D) {
            return Math.round(curve.minimum());
        }
        if (safePosition >= 1.0D) {
            return Math.round(curve.maximum());
        }
        double lower = logistic(-curve.steepness() * curve.midpoint());
        double upper = logistic(curve.steepness() * (1.0D - curve.midpoint()));
        double shapedPosition = (logistic(curve.steepness() * (safePosition - curve.midpoint())) - lower)
                / (upper - lower);
        return Math.round(geometricInterpolate(curve.minimum(), curve.maximum(), shapedPosition));
    }

    private static double logistic(double value) {
        return 1.0D / (1.0D + Math.exp(-value));
    }

    private static int roundToValidBlockCount(double value) {
        return Math.max(1, (int) Math.round(value));
    }

    private static double interpolate(double minimum, double maximum, double position) {
        return minimum + clamp(position, 0.0D, 1.0D) * (maximum - minimum);
    }

    private static double positive(double value) {
        return Math.max(Double.MIN_NORMAL, value);
    }

    private static double nonNegative(double value) {
        return Math.max(0.0D, value);
    }

    private static double clamp(double value, double minimum, double maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    record Parameters(
            int minimumPossibleDepositBlocks,
            int maximumSafeDepositBlocks,
            double tierSpawnFalloff,
            double blockCountSpawnInfluence,
            double totalOreSpawnInfluence
    ) {
        Parameters {
            minimumPossibleDepositBlocks = Math.max(1, minimumPossibleDepositBlocks);
            maximumSafeDepositBlocks = Math.max(minimumPossibleDepositBlocks, maximumSafeDepositBlocks);
        }
    }

    record TierProfile(
            DepositTier tier,
            double tierPosition,
            double blockScalePosition,
            double characteristicBlockCount,
            double blockRangeSpread,
            int minimumBlockCount,
            int maximumBlockCount,
            double densityPosition,
            double characteristicOreDensity,
            double oreRangeSpread,
            double characteristicTotalOre,
            long minimumTotalOre,
            long maximumTotalOre
    ) {
    }

    record OreRange(double characteristicTotalOre, long minimumTotalOre, long maximumTotalOre) {
    }

    record RarityParameters(
            double smallTierBaseRarityStrength,
            double rareTailStrength,
            double rareTailCurve,
            double smallTierInfluenceCurve,
            double largeTierRarityBoost,
            double minimumRelativeWeight
    ) {
        RarityParameters {
            smallTierBaseRarityStrength = nonNegative(smallTierBaseRarityStrength);
            rareTailStrength = nonNegative(rareTailStrength);
            rareTailCurve = positive(rareTailCurve);
            smallTierInfluenceCurve = positive(smallTierInfluenceCurve);
            largeTierRarityBoost = clamp(largeTierRarityBoost, 0.0D, 0.99D);
            minimumRelativeWeight = clamp(minimumRelativeWeight, 1.0E-12D, 1.0D);
        }
    }

    record RarityWeight(
            double relativeFrequency,
            double rareTail,
            double baseExponent,
            double adaptiveExponent,
            double weight
    ) {
    }

    private record RangeCurve(double minimum, double maximum, double steepness, double midpoint) {
    }

    record SmallTierParameters(
            double frequencyRetention,
            double minimumRetention,
            double relativeFrequency
    ) {
    }

    record SmallTierPlan(
            double[] weights,
            double[] rarityExponents,
            double[] expectedAttempts,
            double[] gateProbabilities,
            double combinedExpectedAttempts,
            double retainedFraction
    ) {
        SmallTierPlan {
            weights = Arrays.copyOf(weights, weights.length);
            rarityExponents = Arrays.copyOf(rarityExponents, rarityExponents.length);
            expectedAttempts = Arrays.copyOf(expectedAttempts, expectedAttempts.length);
            gateProbabilities = Arrays.copyOf(gateProbabilities, gateProbabilities.length);
        }
    }

    record TierDistribution(
            TierProfile[] profiles,
            double[] rawScales,
            double[] normalizedScales,
            double[] spawnWeights,
            double[] shares
    ) {
        TierDistribution {
            profiles = Arrays.copyOf(profiles, profiles.length);
            rawScales = Arrays.copyOf(rawScales, rawScales.length);
            normalizedScales = Arrays.copyOf(normalizedScales, normalizedScales.length);
            spawnWeights = Arrays.copyOf(spawnWeights, spawnWeights.length);
            shares = Arrays.copyOf(shares, shares.length);
        }
    }
}

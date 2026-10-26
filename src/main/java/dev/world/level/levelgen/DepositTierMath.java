package dev.world.level.levelgen;

import dev.util.HybridGrowthMath;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.DoubleSupplier;

/** Pure table-free math for tier size, ore density, reserves, spawn weights and spacing scale. */
final class DepositTierMath {
    /*
     * Four monotone curves instead of a per-tier range table. Their global endpoints and sigmoid
     * shape are calibrated so the four tiers resolve to blocks 1-2, 2-6, 72-168, 320-720 and ore
     * 1-4, 8-16, 4000-10000, 40000-160000. MEDIUM/LARGE use these profiles only for spawn
     * calibration and spacing; their physical sizes and reserves come from CompactDepositGeometry.
     * A tier is evaluated only from its normalized ordinal position, so no per-tier constants are
     * needed and inserting another tier stays well-defined.
     */
    private static final RangeCurve MINIMUM_BLOCK_CURVE =
            new RangeCurve(1.0D, 160.0D, 8.72D, 0.54D);
    private static final RangeCurve MAXIMUM_BLOCK_CURVE =
            new RangeCurve(2.0D, 360.0D, 7.13D, 0.506D);
    private static final RangeCurve MINIMUM_TOTAL_ORE_CURVE =
            new RangeCurve(1.0D, 10_000.0D, 7.383989677260686D, 0.48858235138048034D);
    private static final RangeCurve MAXIMUM_TOTAL_ORE_CURVE =
            new RangeCurve(4.0D, 40_000.0D, 8.096885733375872D, 0.5404351532851204D);

    private DepositTierMath() {}

    static TierProfile profile(DepositTier tier, Parameters parameters) {
        double tierPosition = tier.normalizedPosition();
        double footprintScale = rareTierScale(tier, tierPosition, 1.5D, 2.0D);
        int minimumBlockCount =
                Math.min(
                        parameters.maximumSafeDepositBlocks(),
                        Math.max(
                                parameters.minimumPossibleDepositBlocks(),
                                (int)
                                        Math.round(
                                                sampleRangeCurve(MINIMUM_BLOCK_CURVE, tierPosition)
                                                        * footprintScale)));
        int maximumBlockCount =
                Math.min(
                        parameters.maximumSafeDepositBlocks(),
                        (int)
                                Math.round(
                                        sampleRangeCurve(MAXIMUM_BLOCK_CURVE, tierPosition)
                                                * footprintScale));
        maximumBlockCount = Math.max(minimumBlockCount, maximumBlockCount);
        double characteristicBlockCount = Math.sqrt((double) minimumBlockCount * maximumBlockCount);
        double blockRangeSpread = Math.sqrt(maximumBlockCount / (double) minimumBlockCount);
        double blockScalePosition =
                normalizeLogarithmically(
                        characteristicBlockCount,
                        Math.sqrt(MINIMUM_BLOCK_CURVE.minimum() * MAXIMUM_BLOCK_CURVE.minimum()),
                        Math.sqrt(MINIMUM_BLOCK_CURVE.maximum() * MAXIMUM_BLOCK_CURVE.maximum()));

        double reserveScale = rareTierScale(tier, tierPosition, 2.5D, 4.0D);
        long minimumTotalOre =
                Math.round(sampleRangeCurve(MINIMUM_TOTAL_ORE_CURVE, tierPosition) * reserveScale);
        long maximumTotalOre =
                Math.max(
                        minimumTotalOre,
                        Math.round(
                                sampleRangeCurve(MAXIMUM_TOTAL_ORE_CURVE, tierPosition)
                                        * reserveScale));
        double characteristicTotalOre = Math.sqrt((double) minimumTotalOre * maximumTotalOre);
        double characteristicOreDensity = characteristicTotalOre / characteristicBlockCount;
        double oreRangeSpread = Math.sqrt(maximumTotalOre / (double) minimumTotalOre);
        double densityPosition =
                normalizeLogarithmically(
                        characteristicOreDensity,
                        Math.sqrt(
                                        MINIMUM_TOTAL_ORE_CURVE.minimum()
                                                * MAXIMUM_TOTAL_ORE_CURVE.minimum())
                                / Math.sqrt(
                                        MINIMUM_BLOCK_CURVE.minimum()
                                                * MAXIMUM_BLOCK_CURVE.minimum()),
                        Math.sqrt(
                                        MINIMUM_TOTAL_ORE_CURVE.maximum()
                                                * MAXIMUM_TOTAL_ORE_CURVE.maximum())
                                / Math.sqrt(
                                        MINIMUM_BLOCK_CURVE.maximum()
                                                * MAXIMUM_BLOCK_CURVE.maximum()));

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
                maximumTotalOre);
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
            double rawScale =
                    Math.pow(
                                    profile.characteristicBlockCount(),
                                    nonNegative(parameters.blockCountSpawnInfluence()))
                            * Math.pow(
                                    profile.characteristicTotalOre(),
                                    nonNegative(parameters.totalOreSpawnInfluence()));
            rawScales[index] = Math.max(Double.MIN_NORMAL, rawScale);
            minimumScale = Math.min(minimumScale, rawScales[index]);
            maximumScale = Math.max(maximumScale, rawScales[index]);
        }

        double[] normalizedScales = new double[tiers.length];
        double[] spawnWeights = new double[tiers.length];
        double[] shares = new double[tiers.length];
        double totalWeight = 0.0D;
        for (int index = 0; index < tiers.length; index++) {
            normalizedScales[index] =
                    normalizeLogarithmically(rawScales[index], minimumScale, maximumScale);
            spawnWeights[index] =
                    Math.exp(-nonNegative(parameters.tierSpawnFalloff()) * normalizedScales[index]);
            totalWeight += spawnWeights[index];
        }
        for (int index = 0; index < tiers.length; index++) {
            shares[index] = totalWeight <= 0.0D ? 0.0D : spawnWeights[index] / totalWeight;
        }
        return new TierDistribution(profiles, rawScales, normalizedScales, spawnWeights, shares);
    }

    /** Retain fewer small-tier candidates even when their original cell chance saturated at one. */
    static double retainedTierCandidateProbability(
            DepositTier tier, double probability, double smallTierRetention) {
        double chance = clamp(probability, 0.0D, 1.0D);
        return tier.ordinal() < DepositTier.MEDIUM.ordinal()
                ? chance * clamp(smallTierRetention, 0.0D, 1.0D)
                : chance;
    }

    static OreRange oreRange(int actualBlockCount, TierProfile profile) {
        int safeBlockCount = Math.max(1, actualBlockCount);
        double sizeRatio = safeBlockCount / profile.characteristicBlockCount();
        double characteristicTotalOre =
                clamp(
                        profile.characteristicTotalOre() * sizeRatio,
                        profile.minimumTotalOre(),
                        profile.maximumTotalOre());
        return new OreRange(
                characteristicTotalOre, profile.minimumTotalOre(), profile.maximumTotalOre());
    }

    static int sampleBlockCount(TierProfile profile, DoubleSupplier random) {
        return (int)
                sampleAroundCharacteristicValue(
                        profile.minimumBlockCount(),
                        profile.maximumBlockCount(),
                        profile.characteristicBlockCount(),
                        random);
    }

    static long sampleTotalOre(OreRange range, DoubleSupplier random) {
        return sampleAroundCharacteristicValue(
                range.minimumTotalOre(),
                range.maximumTotalOre(),
                range.characteristicTotalOre(),
                random);
    }

    /** Samples a triangular distribution whose mode is the characteristic value. */
    static long sampleAroundCharacteristicValue(
            long minimum, long maximum, double characteristic, DoubleSupplier random) {
        long boundedMaximum = Math.max(minimum, maximum);
        if (boundedMaximum == minimum) {
            return minimum;
        }
        double mode = clamp(characteristic, minimum, boundedMaximum);
        double span = boundedMaximum - (double) minimum;
        double modeFraction = (mode - minimum) / span;
        double unit = clamp(random.getAsDouble(), 0.0D, Math.nextDown(1.0D));
        double sampled =
                unit < modeFraction
                        ? minimum + Math.sqrt(unit * span * (mode - minimum))
                        : boundedMaximum
                                - Math.sqrt((1.0D - unit) * span * (boundedMaximum - mode));
        return Math.max(minimum, Math.min(boundedMaximum, Math.round(sampled)));
    }

    /** One and only one adaptive material-weight formula for all tier lotteries. */
    static RarityWeight adaptiveTierOreWeight(
            DepositTier tier,
            double effectiveOriginalFrequency,
            double maximumEffectiveFrequency,
            RarityParameters parameters) {
        if (maximumEffectiveFrequency <= 0.0D) {
            return new RarityWeight(0.0D, Double.POSITIVE_INFINITY, 1.0D, 0.0D);
        }
        double relativeFrequency =
                clamp(
                        effectiveOriginalFrequency / maximumEffectiveFrequency,
                        parameters.minimumRelativeWeight(),
                        1.0D);
        boolean smallTier = tier.ordinal() < DepositTier.MEDIUM.ordinal();
        double weight;
        if (smallTier) {
            // The manual mining-time curve, counting how many times rarer this ore is than the most
            // common one: a weight proportional to its own frequency, then a bounded exponential
            // penalty on top.
            weight =
                    maximumEffectiveFrequency
                            / HybridGrowthMath.multiplier(
                                    1.0D / relativeFrequency,
                                    parameters.rarityGrowth(),
                                    parameters.rarityMaxExponentialMultiplier());
        } else {
            double exponent =
                    Math.max(
                            0.01D,
                            1.0D
                                    - nonNegative(parameters.largeTierRarityBoost())
                                            * tier.normalizedPosition());
            weight = maximumEffectiveFrequency * Math.pow(relativeFrequency, exponent);
        }
        weight = Math.min(maximumEffectiveFrequency, weight);
        // A constant floor would give every rare small-tier ore the same weight and mask stronger
        // penalties. Keep a positive floor proportional to its source frequency in small tiers
        // only.
        double minimumWeight = maximumEffectiveFrequency * parameters.minimumRelativeWeight();
        if (smallTier) {
            minimumWeight *= relativeFrequency;
        }
        weight = Math.max(minimumWeight, weight);
        double penalty = maximumEffectiveFrequency / weight;
        // The power of relativeFrequency this weight amounts to, so both branches read alike in
        // diagnostics.
        double effectiveExponent =
                relativeFrequency < 1.0D ? Math.log(penalty) / -Math.log(relativeFrequency) : 1.0D;
        return new RarityWeight(relativeFrequency, penalty, effectiveExponent, weight);
    }

    static int calculateFillStage(
            long remainingOre, long minimumOre, long maximumOre, int stageCount) {
        if (remainingOre <= 0L) {
            return 0;
        }
        long safeMinimum = Math.max(1L, minimumOre);
        int safeStageCount = Math.max(1, stageCount);
        if (maximumOre <= safeMinimum) {
            return safeStageCount;
        }
        long clampedOre = Math.max(safeMinimum, Math.min(maximumOre, remainingOre));
        double normalized =
                Math.log(clampedOre / (double) safeMinimum)
                        / Math.log(maximumOre / (double) safeMinimum);
        int stage = (int) Math.floor(normalized * safeStageCount) + 1;
        return Math.max(1, Math.min(safeStageCount, stage));
    }

    /**
     * Exact integer allocation with a one-unit floor and rounding residue assigned to the richest
     * blocks.
     */
    static int[] distributeOre(int finalTotalOre, double[] densityWeights) {
        int blockCount = densityWeights.length;
        if (blockCount == 0) {
            return new int[0];
        }
        int safeTotalOre = Math.max(blockCount, finalTotalOre);
        double[] sanitizedWeights =
                Arrays.stream(densityWeights)
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
        Arrays.sort(
                richestFirst,
                Comparator.comparingDouble((Integer index) -> sanitizedWeights[index]).reversed());
        for (int remainder = safeTotalOre - assigned, index = 0;
                remainder > 0;
                remainder--, index++) {
            amounts[richestFirst[index % blockCount]]++;
        }
        return amounts;
    }

    /**
     * Capacity-aware allocation for compact deposits; small tiers use their dedicated allocator.
     */
    static int[] distributeOre(int totalOre, double[] weights, int maximumPerBlock) {
        int count = weights.length;
        if (count == 0) return new int[0];
        int capacity = Math.max(1, maximumPerBlock);
        int target = (int) Math.max(count, Math.min((long) totalOre, (long) count * capacity));
        int[] amounts = new int[count];
        Arrays.fill(amounts, 1);
        double[] normalized = new double[count];
        double maximumWeight =
                Arrays.stream(weights).filter(w -> Double.isFinite(w) && w > 0).max().orElse(1);
        for (int i = 0; i < count; i++)
            normalized[i] =
                    Double.isFinite(weights[i]) && weights[i] > 0 ? weights[i] / maximumWeight : 0;
        int remainder = target - count;
        while (remainder > 0) {
            double sum = 0;
            for (int i = 0; i < count; i++) if (amounts[i] < capacity) sum += normalized[i];
            boolean equal = sum <= 0;
            if (equal) for (int amount : amounts) if (amount < capacity) sum++;
            int available = remainder;
            boolean saturated = false;
            // Saturated blocks leave the weighting pool; redistribute their unused share
            // afterwards.
            for (int i = 0; i < count; i++) {
                int room = capacity - amounts[i];
                if (room <= 0) continue;
                double share = available * (equal ? 1 : normalized[i]) / sum;
                if (share >= room) {
                    amounts[i] += room;
                    remainder -= room;
                    saturated = true;
                }
            }
            if (saturated) continue;
            double[] fractions = new double[count];
            Integer[] order = new Integer[count];
            for (int i = 0; i < count; i++) {
                order[i] = i;
                if (amounts[i] >= capacity) {
                    fractions[i] = -1;
                    continue;
                }
                double share = available * (equal ? 1 : normalized[i]) / sum;
                int integral = (int) Math.floor(share);
                amounts[i] += integral;
                remainder -= integral;
                fractions[i] = share - integral;
            }
            Arrays.sort(order, Comparator.comparingDouble((Integer i) -> fractions[i]).reversed());
            for (int i : order) {
                if (remainder == 0) break;
                if (amounts[i] < capacity) {
                    amounts[i]++;
                    remainder--;
                }
            }
        }
        return amounts;
    }

    static double geometricInterpolate(double minimum, double maximum, double position) {
        double safeMinimum = Math.max(Double.MIN_NORMAL, minimum);
        double safeMaximum = Math.max(safeMinimum, maximum);
        return Math.exp(
                Math.log(safeMinimum)
                        + clamp(position, 0.0D, 1.0D)
                                * (Math.log(safeMaximum) - Math.log(safeMinimum)));
    }

    static double normalizeLogarithmically(double value, double minimum, double maximum) {
        if (!Double.isFinite(value)
                || !Double.isFinite(minimum)
                || !Double.isFinite(maximum)
                || value <= 0.0D
                || minimum <= 0.0D
                || maximum <= minimum) {
            return 0.0D;
        }
        return clamp(
                (Math.log(value) - Math.log(minimum)) / (Math.log(maximum) - Math.log(minimum)),
                0.0D,
                1.0D);
    }

    /**
     * Lens helper used by the small-tier path and spawn-profile diagnostics. Compact MEDIUM/LARGE
     * placement overrides these dimensions using its independent Y surfaces.
     */
    static int verticalLayersForTier(int tier, double tierPosition, int maximumDepth) {
        int safeMaximumDepth = Math.max(1, maximumDepth);
        if (tier >= DepositTier.MEDIUM.ordinal()) {
            return Math.min(2, safeMaximumDepth);
        }
        int depth = 1 + (int) Math.round(Math.pow(tierPosition, 1.3D) * (safeMaximumDepth - 1));
        return Math.max(1, Math.min(safeMaximumDepth, depth));
    }

    private static double rareTierScale(
            DepositTier tier, double tierPosition, double mediumScale, double largeScale) {
        if (tier.ordinal() < DepositTier.MEDIUM.ordinal()) {
            return 1.0D;
        }
        double mediumPosition = DepositTier.MEDIUM.normalizedPosition();
        double rareTierPosition = (tierPosition - mediumPosition) / (1.0D - mediumPosition);
        return interpolate(mediumScale, largeScale, rareTierPosition);
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
        double shapedPosition =
                (logistic(curve.steepness() * (safePosition - curve.midpoint())) - lower)
                        / (upper - lower);
        return Math.round(geometricInterpolate(curve.minimum(), curve.maximum(), shapedPosition));
    }

    private static double logistic(double value) {
        return 1.0D / (1.0D + Math.exp(-value));
    }

    private static double interpolate(double minimum, double maximum, double position) {
        return minimum + clamp(position, 0.0D, 1.0D) * (maximum - minimum);
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
            double totalOreSpawnInfluence) {
        Parameters {
            minimumPossibleDepositBlocks = Math.max(1, minimumPossibleDepositBlocks);
            maximumSafeDepositBlocks =
                    Math.max(minimumPossibleDepositBlocks, maximumSafeDepositBlocks);
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
            long maximumTotalOre) {}

    record OreRange(double characteristicTotalOre, long minimumTotalOre, long maximumTotalOre) {}

    /**
     * @param rarityGrowth TINY/SMALL: exponential growth of the {@link HybridGrowthMath} penalty
     * @param rarityMaxExponentialMultiplier TINY/SMALL: cap of that penalty's exponential part
     * @param largeTierRarityBoost MEDIUM/LARGE: how far rare ores are lifted towards the common
     *     ones
     */
    record RarityParameters(
            double rarityGrowth,
            double rarityMaxExponentialMultiplier,
            double largeTierRarityBoost,
            double minimumRelativeWeight) {
        RarityParameters {
            rarityGrowth = nonNegative(rarityGrowth);
            rarityMaxExponentialMultiplier = Math.max(1.0D, rarityMaxExponentialMultiplier);
            largeTierRarityBoost = clamp(largeTierRarityBoost, 0.0D, 0.99D);
            minimumRelativeWeight = clamp(minimumRelativeWeight, 1.0E-12D, 1.0D);
        }
    }

    /**
     * @param penalty how many times smaller the weight is than the most common ore's
     * @param effectiveExponent the power of {@code relativeFrequency} that penalty amounts to
     */
    record RarityWeight(
            double relativeFrequency, double penalty, double effectiveExponent, double weight) {}

    private record RangeCurve(double minimum, double maximum, double steepness, double midpoint) {}

    record TierDistribution(
            TierProfile[] profiles,
            double[] rawScales,
            double[] normalizedScales,
            double[] spawnWeights,
            double[] shares) {
        TierDistribution {
            profiles = Arrays.copyOf(profiles, profiles.length);
            rawScales = Arrays.copyOf(rawScales, rawScales.length);
            normalizedScales = Arrays.copyOf(normalizedScales, normalizedScales.length);
            spawnWeights = Arrays.copyOf(spawnWeights, spawnWeights.length);
            shares = Arrays.copyOf(shares, shares.length);
        }
    }
}

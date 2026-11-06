package dev.world.geology;

import java.util.Arrays;

/**
 * Geometry of the receiver spread: which receivers illuminate a target, how many of them carry
 * independent information, from how many directions they look at it and how well their arrival
 * times constrain the velocity (and therefore the depth). The station's built-in sensor is always a
 * zero-offset receiver at the source.
 */
public final class ArrayGeometry {
    /**
     * Receivers closer than this record nearly the same ray paths and ambient noise, so a cluster
     * counts as roughly one sensor.
     */
    public static final double CORRELATION_LENGTH = 24.0D;

    /** Illumination below this weight does not contribute a viewing direction. */
    public static final double DIRECTION_THRESHOLD = 0.25D;

    /** Relative velocity uncertainty when only zero-offset data exist (no moveout to measure). */
    public static final double PRIOR_VELOCITY_ERROR = 0.25D;

    /** Picking precision as a fraction of the dominant period. */
    public static final double TIMING_PERIOD_FRACTION = 0.125D;

    private ArrayGeometry() {}

    /**
     * Weight with which a source–receiver pair images a column at (px, pz). Reflection points of a
     * pair cluster around its common midpoint; the imaged patch widens with the offset.
     */
    public static double illumination(
            double sourceX,
            double sourceZ,
            double receiverX,
            double receiverZ,
            double px,
            double pz) {
        double midX = (sourceX + receiverX) * 0.5D;
        double midZ = (sourceZ + receiverZ) * 0.5D;
        double offset = Math.hypot(receiverX - sourceX, receiverZ - sourceZ);
        double reach = offset * 0.5D + 48.0D;
        double dx = px - midX;
        double dz = pz - midZ;
        return Math.exp(-0.5D * (dx * dx + dz * dz) / (reach * reach));
    }

    /**
     * Effective number of independent receivers, (Σw)² / ΣΣ wᵢwⱼcᵢⱼ with correlation cᵢⱼ =
     * exp(−dᵢⱼ²/2L²). Equals the count for receivers far apart and drops towards 1 for a cluster.
     */
    public static double effectiveReceivers(
            double[] xs, double[] zs, double[] weights, double correlationLength) {
        double sum = 0.0D;
        double correlated = 0.0D;
        double l2 = correlationLength * correlationLength;
        for (int i = 0; i < weights.length; i++) {
            if (weights[i] <= 0.0D) {
                continue;
            }
            sum += weights[i];
            for (int j = 0; j < weights.length; j++) {
                if (weights[j] <= 0.0D) {
                    continue;
                }
                double dx = xs[i] - xs[j];
                double dz = zs[i] - zs[j];
                correlated += weights[i] * weights[j] * Math.exp(-0.5D * (dx * dx + dz * dz) / l2);
            }
        }
        return correlated <= 0.0D ? 0.0D : sum * sum / correlated;
    }

    /**
     * Share of directions around the target that are viewed: 1 − (largest angular gap)/2π among the
     * receivers whose weight reaches {@link #DIRECTION_THRESHOLD}. A single direction gives 0, two
     * opposite ones 0.5, n evenly spaced ones 1 − 1/n.
     */
    public static double azimuthalCoverage(
            double px, double pz, double[] xs, double[] zs, double[] weights) {
        double[] angles = new double[weights.length];
        int count = 0;
        for (int i = 0; i < weights.length; i++) {
            double dx = xs[i] - px;
            double dz = zs[i] - pz;
            if (weights[i] < DIRECTION_THRESHOLD || dx * dx + dz * dz < 1.0D) {
                continue;
            }
            angles[count++] = Math.atan2(dz, dx);
        }
        if (count < 2) {
            return 0.0D;
        }
        Arrays.sort(angles, 0, count);
        double largestGap = angles[0] + 2.0D * Math.PI - angles[count - 1];
        for (int i = 1; i < count; i++) {
            largestGap = Math.max(largestGap, angles[i] - angles[i - 1]);
        }
        return Math.max(0.0D, 1.0D - largestGap / (2.0D * Math.PI));
    }

    /**
     * Relative velocity error from the normal-moveout hyperbola t² = t₀² + x²/V². A weighted
     * least-squares line through (x², t²) has slope 1/V²; its standard error is σ(t²)/√Σw(x²−x̄²)².
     * Zero-offset data alone leave the {@code prior}. The result is combined with the prior as
     * independent estimates.
     */
    public static double velocityRelativeError(
            double[] offsets,
            double[] weights,
            double depth,
            double velocity,
            double frequency,
            double prior) {
        double totalWeight = 0.0D;
        double meanSquare = 0.0D;
        for (int i = 0; i < offsets.length; i++) {
            if (weights[i] <= 0.0D) {
                continue;
            }
            totalWeight += weights[i];
            meanSquare += weights[i] * offsets[i] * offsets[i];
        }
        if (totalWeight <= 0.0D) {
            return prior;
        }
        meanSquare /= totalWeight;
        double spread = 0.0D;
        for (int i = 0; i < offsets.length; i++) {
            if (weights[i] <= 0.0D) {
                continue;
            }
            double deviation = offsets[i] * offsets[i] - meanSquare;
            spread += weights[i] * deviation * deviation;
        }
        if (spread <= 1.0E-9D) {
            return prior;
        }
        double t0 = SeismicMath.twoWayTime(Math.max(4.0D, depth), velocity);
        double timingSigma = TIMING_PERIOD_FRACTION / Math.max(1.0E-6D, frequency);
        double squaredTimeSigma = 2.0D * t0 * timingSigma;
        double slopeSigma = squaredTimeSigma / Math.sqrt(spread);
        double slope = 1.0D / (velocity * velocity);
        double measured = slopeSigma / (2.0D * slope);
        return 1.0D / Math.sqrt(1.0D / (prior * prior) + 1.0D / (measured * measured));
    }

    /**
     * Lateral position uncertainty of a reflector: the Fresnel radius, focused by migration over
     * the receivers that illuminate it (more for well-spread directions), combined with the map's
     * pixel quantisation.
     */
    public static double lateralSigma(
            double fresnelRadius, double geophoneReceivers, double coverage, double pixelSize) {
        double focusing = 1.0D + geophoneReceivers * (0.3D + 0.7D * coverage);
        return Math.sqrt(fresnelRadius * fresnelRadius / focusing + pixelSize * pixelSize / 12.0D);
    }

    /**
     * Depth uncertainty as a fraction of depth: velocity error and the quarter-wavelength vertical
     * resolution.
     */
    public static double depthRelativeError(double velocityError, double wavelength, double depth) {
        double resolution = wavelength / 8.0D / Math.max(8.0D, depth);
        return Math.sqrt(velocityError * velocityError + resolution * resolution);
    }
}

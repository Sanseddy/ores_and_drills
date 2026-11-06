package dev.world.geology;

/**
 * Closed-form approximations of reflection seismics used by the survey model. Every formula is the
 * textbook normal-incidence, small-contrast version: the model never propagates a wavefield through
 * blocks, it sums analytic responses of a bounded set of reflectors (see
 * docs/en/seismic-survey.md).
 */
public final class SeismicMath {
    /**
     * Linearised reflection coefficient of a small void fraction φ: R ≈ ½·Δln Z with ρ ∝ (1−φ) and
     * V ∝ (1−φ)^1.5, an empirical porous-rock velocity law. It keeps a cave zone's response
     * proportional to how much of the volume is open.
     */
    public static final double VOID_REFLECTIVITY_PER_FRACTION = -1.25D;

    /** Attenuation proxy of open voids: scattering removes energy roughly like Q ≈ 20 rock. */
    public static final double VOID_SCATTERING_PER_FRACTION = 6.0D;

    private SeismicMath() {}

    /** Normal-incidence reflection coefficient R = (Z2 − Z1) / (Z2 + Z1). */
    public static double reflectionCoefficient(double upperImpedance, double lowerImpedance) {
        double sum = upperImpedance + lowerImpedance;
        return sum <= 0.0D ? 0.0D : (lowerImpedance - upperImpedance) / sum;
    }

    /** Reflection of a wave travelling from {@code upper} into {@code lower}. */
    public static double reflectionCoefficient(RockProperties upper, RockProperties lower) {
        return reflectionCoefficient(upper.impedance(), lower.impedance());
    }

    /** Dominant wavelength λ = V / f in blocks. */
    public static double wavelength(double velocity, double frequency) {
        return velocity / Math.max(1.0E-6D, frequency);
    }

    /**
     * First Fresnel zone radius for a coincident source and receiver: r = sqrt(λ·d/2 + λ²/16). A
     * reflector smaller than this zone returns only part of the energy of an infinite one.
     */
    public static double fresnelRadius(double wavelength, double depth) {
        double d = Math.max(0.0D, depth);
        return Math.sqrt(wavelength * d * 0.5D + wavelength * wavelength / 16.0D);
    }

    /**
     * Widess thin-bed factor: a layer thinner than a quarter wavelength returns about 4πh/λ of the
     * amplitude of a single interface; thicker layers resolve into separate top and base events.
     */
    public static double thinBedFactor(double thickness, double wavelength) {
        if (thickness <= 0.0D) {
            return 0.0D;
        }
        return Math.min(1.0D, 4.0D * Math.PI * thickness / Math.max(1.0E-6D, wavelength));
    }

    /**
     * Fraction of a lens's amplitude recorded at horizontal offset (dx, dz) from its centre. The
     * lens (semi-axes rx, rz) and the resolution disc (radius {@code resolution}) are each replaced
     * by the Gaussian of equal second moment — a disc of radius r has variance r²/4 per axis — and
     * convolved. The peak is then the lens area over the blurred area: 1 for a lens much wider than
     * the resolution, r²/(r² + r_F²) for a small one, its share of the Fresnel zone.
     */
    public static double footprintWeight(
            double dx, double dz, double radiusX, double radiusZ, double angle, double resolution) {
        double cos = Math.cos(angle);
        double sin = Math.sin(angle);
        double u = dx * cos + dz * sin;
        double v = -dx * sin + dz * cos;
        double sx = radiusX * radiusX * 0.25D;
        double sz = radiusZ * radiusZ * 0.25D;
        double r2 = resolution * resolution * 0.25D;
        double spreadX = sx + r2;
        double spreadZ = sz + r2;
        double peak = Math.sqrt(sx / spreadX * (sz / spreadZ));
        return peak * Math.exp(-0.5D * (u * u / spreadX + v * v / spreadZ));
    }

    /**
     * Amplitude left after constant-Q absorption along the two-way path: exp(−π·f·t/Q) with t =
     * 2d/V.
     */
    public static double absorption(
            double frequency, double depth, double velocity, double quality) {
        double travelTime = 2.0D * Math.max(0.0D, depth) / Math.max(1.0D, velocity);
        return Math.exp(-Math.PI * frequency * travelTime / Math.max(1.0E-3D, quality));
    }

    /** Two-way travel time in seconds to a reflector at {@code depth}. */
    public static double twoWayTime(double depth, double velocity) {
        return 2.0D * Math.max(0.0D, depth) / Math.max(1.0D, velocity);
    }

    /**
     * Noise in reflectivity units after spherical-divergence gain: the recorded signal falls off as
     * 1/(2d), so restoring true amplitudes multiplies the ambient noise by the same factor.
     * Stacking {@code effectiveReceivers} independent traces divides it by √N.
     */
    public static double noiseSigma(
            double baseNoise, double depth, double environment, double effectiveReceivers) {
        double divergence = (Math.max(0.0D, depth) + 20.0D) / 100.0D;
        return baseNoise
                * divergence
                * Math.max(0.05D, environment)
                / Math.sqrt(Math.max(1.0D, effectiveReceivers));
    }

    /** Linearised reflection coefficient of a rock volume with void fraction {@code fraction}. */
    public static double voidReflectivity(double fraction) {
        return VOID_REFLECTIVITY_PER_FRACTION * Math.max(0.0D, Math.min(1.0D, fraction));
    }

    /**
     * Ricker wavelet with peak frequency {@code frequency}, evaluated {@code time} seconds from its
     * centre.
     */
    public static double ricker(double time, double frequency) {
        double a = Math.PI * frequency * time;
        double a2 = a * a;
        return (1.0D - 2.0D * a2) * Math.exp(-a2);
    }
}

package dev.world.geology;

/**
 * Elastic properties of one rock or ore in the survey model. One block is treated as one metre, so
 * velocities are in m/s and distances in blocks.
 *
 * @param density bulk density in g/cm³
 * @param velocity compressional (P-wave) velocity in m/s
 * @param quality seismic quality factor Q; lower values attenuate the wave faster
 */
public record RockProperties(double density, double velocity, double quality) {
    public RockProperties {
        if (!(density > 0.0D) || !Double.isFinite(density)) {
            throw new IllegalArgumentException("Rock density must be positive");
        }
        if (!(velocity > 0.0D) || !Double.isFinite(velocity)) {
            throw new IllegalArgumentException("Rock velocity must be positive");
        }
        if (!(quality > 0.0D) || !Double.isFinite(quality)) {
            throw new IllegalArgumentException("Rock quality factor must be positive");
        }
    }

    /** Acoustic impedance Z = ρ·V, in (g/cm³)·(m/s). */
    public double impedance() {
        return density * velocity;
    }

    /**
     * Rock in which {@code fraction} of the volume is replaced by {@code inclusion}. Density mixes
     * arithmetically and velocity by Wyllie's time average (slownesses add), which is the usual
     * first-order estimate for disseminated minerals; attenuation (1/Q) mixes by volume. The time
     * average is why a dense but slow mineral can change impedance less than its density suggests.
     */
    public RockProperties mixedWith(RockProperties inclusion, double fraction) {
        double share = Math.max(0.0D, Math.min(1.0D, fraction));
        if (share == 0.0D) {
            return this;
        }
        double mixedDensity = density * (1.0D - share) + inclusion.density * share;
        double slowness = (1.0D - share) / velocity + share / inclusion.velocity;
        double inverseQuality = (1.0D - share) / quality + share / inclusion.quality;
        return new RockProperties(mixedDensity, 1.0D / slowness, 1.0D / inverseQuality);
    }

    public RockProperties withQuality(double newQuality) {
        return new RockProperties(density, velocity, newQuality);
    }
}

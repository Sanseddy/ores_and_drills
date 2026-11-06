package dev.world.geology;

import java.util.Locale;

/**
 * The two survey programmes. Regional uses a low source frequency: it penetrates and averages over
 * large volumes but resolves little. Detailed uses a higher frequency and a finer grid over a
 * smaller area, which is what makes depth, polarity and a cross-section meaningful.
 */
public enum SurveyMode {
    REGIONAL(20.0D, 64, 0.0055D, false),
    DETAILED(50.0D, 96, 0.0120D, true);

    private final double frequency;
    private final int resolution;
    private final double baseNoise;
    private final boolean detailedAttributes;

    SurveyMode(double frequency, int resolution, double baseNoise, boolean detailedAttributes) {
        this.frequency = frequency;
        this.resolution = resolution;
        this.baseNoise = baseNoise;
        this.detailedAttributes = detailedAttributes;
    }

    /** Dominant source frequency in Hz. */
    public double frequency() {
        return frequency;
    }

    /** Map pixels along each side. */
    public int resolution() {
        return resolution;
    }

    /**
     * Ambient noise at 80 blocks depth with one receiver before migration, in
     * reflection-coefficient units.
     */
    public double baseNoise() {
        return baseNoise;
    }

    /** Whether the survey resolves polarity, depth and attenuation and allows a cross-section. */
    public boolean detailedAttributes() {
        return detailedAttributes;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SurveyMode byId(int id) {
        SurveyMode[] modes = values();
        return id >= 0 && id < modes.length ? modes[id] : REGIONAL;
    }
}

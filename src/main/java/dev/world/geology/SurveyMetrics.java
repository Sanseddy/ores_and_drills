package dev.world.geology;

/**
 * Survey-wide figures shown on the measurement panel. They describe the measurement, not the
 * subsurface, so they are safe to send to clients.
 *
 * @param effectiveReceivers median count of independent receivers over the area, station included
 * @param azimuthCoverage median share of viewing directions around a target, 0–1
 * @param velocityError relative velocity (and depth) error from moveout or a well tie
 * @param depthError relative depth error at the reference depth
 * @param lateralAccuracy median horizontal position uncertainty at the reference depth, in blocks
 * @param noiseLevel median noise at the reference depth, in reflection-coefficient units
 * @param signalQuality 0–1 summary of noise and illumination
 * @param peakIntensity strongest anomaly as signal-to-noise ratio
 */
public record SurveyMetrics(
        int geophones,
        double effectiveReceivers,
        double azimuthCoverage,
        double velocityError,
        double depthError,
        double lateralAccuracy,
        double noiseLevel,
        double signalQuality,
        double peakIntensity,
        int anomalies) {
    /** Depth at which the panel's accuracy figures are quoted. */
    public static final double REFERENCE_DEPTH = 64.0D;
}

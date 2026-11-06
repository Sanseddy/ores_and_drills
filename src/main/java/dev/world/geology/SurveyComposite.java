package dev.world.geology;

import java.util.List;

/**
 * Stacks several surveys of the same programme onto one grid. Each pixel is the inverse-variance
 * mean of every survey that covers it, so independent measurements — other source positions, other
 * geophone layouts — lower the noise and average out positioning errors. The caller passes each
 * distinct configuration once: an identical repeat carries the same errors and adds nothing.
 */
public final class SurveyComposite {
    private SurveyComposite() {}

    public static SurveyMap merge(SurveyMap target, List<SurveyMap> others) {
        int size = target.size();
        float[] amplitude = new float[size];
        float[] sigma = new float[size];
        short[] depthY = new short[size];
        byte[] attenuation = new byte[size];
        float[] lateral = new float[size];
        int resolution = target.resolution();
        for (int row = 0; row < resolution; row++) {
            for (int column = 0; column < resolution; column++) {
                int index = column + row * resolution;
                if (!target.inside(index)) {
                    continue;
                }
                double x = target.pixelX(column);
                double z = target.pixelZ(row);
                double weightSum = 0.0D;
                double amplitudeSum = 0.0D;
                double depthSum = 0.0D;
                double attenuationSum = 0.0D;
                double lateralPrecision = 0.0D;
                for (int source = -1; source < others.size(); source++) {
                    SurveyMap map = source < 0 ? target : others.get(source);
                    int sourceIndex = source < 0 ? index : map.indexAt(x, z);
                    if (sourceIndex < 0 || !map.inside(sourceIndex)) {
                        continue;
                    }
                    double s = map.sigma()[sourceIndex];
                    double weight = 1.0D / (s * s);
                    weightSum += weight;
                    amplitudeSum += weight * map.amplitude()[sourceIndex];
                    depthSum += weight * map.depthY()[sourceIndex];
                    attenuationSum += weight * (map.attenuation()[sourceIndex] & 0xFF);
                    double l = Math.max(0.5D, map.lateralSigma()[sourceIndex]);
                    lateralPrecision += 1.0D / (l * l);
                }
                amplitude[index] = (float) (amplitudeSum / weightSum);
                sigma[index] = (float) (1.0D / Math.sqrt(weightSum));
                depthY[index] = (short) Math.round(depthSum / weightSum);
                attenuation[index] = (byte) Math.round(attenuationSum / weightSum);
                lateral[index] = (float) (1.0D / Math.sqrt(lateralPrecision));
            }
        }
        SurveyMap merged =
                new SurveyMap(
                        target.mode(),
                        target.centerX(),
                        target.centerZ(),
                        target.radius(),
                        resolution,
                        amplitude,
                        sigma,
                        depthY,
                        attenuation,
                        lateral,
                        target.metrics());
        return merged.withMetrics(metrics(target.metrics(), others, merged));
    }

    private static SurveyMetrics metrics(
            SurveyMetrics base, List<SurveyMap> others, SurveyMap merged) {
        double noisePrecision = precision(base.noiseLevel());
        double lateralPrecision = precision(base.lateralAccuracy());
        double velocityPrecision = precision(base.velocityError());
        double depthPrecision = precision(base.depthError());
        double missingQuality = 1.0D - base.signalQuality();
        double receivers = base.effectiveReceivers();
        double coverage = base.azimuthCoverage();
        int geophones = base.geophones();
        for (SurveyMap map : others) {
            SurveyMetrics metrics = map.metrics();
            noisePrecision += precision(metrics.noiseLevel());
            lateralPrecision += precision(metrics.lateralAccuracy());
            velocityPrecision += precision(metrics.velocityError());
            depthPrecision += precision(metrics.depthError());
            missingQuality *= 1.0D - metrics.signalQuality();
            receivers += metrics.effectiveReceivers();
            coverage = Math.max(coverage, metrics.azimuthCoverage());
            geophones = Math.max(geophones, metrics.geophones());
        }
        double peak = 0.0D;
        for (int index = 0; index < merged.size(); index++) {
            peak = Math.max(peak, merged.intensity(index));
        }
        return new SurveyMetrics(
                geophones,
                receivers,
                coverage,
                1.0D / Math.sqrt(velocityPrecision),
                1.0D / Math.sqrt(depthPrecision),
                1.0D / Math.sqrt(lateralPrecision),
                1.0D / Math.sqrt(noisePrecision),
                1.0D - missingQuality,
                peak,
                SurveyAnomalies.find(merged, null).size());
    }

    private static double precision(double value) {
        double v = Math.max(1.0E-9D, value);
        return 1.0D / (v * v);
    }
}

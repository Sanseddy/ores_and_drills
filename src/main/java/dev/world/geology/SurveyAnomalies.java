package dev.world.geology;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Picks the significant anomalies of a processed map: local intensity maxima above a detection
 * threshold, each with the approximate extent of the area around it that stays above half its peak.
 * Works only on the measured map, so it can reveal nothing the map does not show.
 */
public final class SurveyAnomalies {
    public static final int MAXIMUM_ANOMALIES = 24;
    private static final int SUPPRESSION_RADIUS = 2;

    private SurveyAnomalies() {}

    /**
     * One picked anomaly.
     *
     * @param polarity sign of the reflection, or 0 when the survey does not resolve it
     * @param depthY interpreted height, or {@link Integer#MIN_VALUE} when not resolved
     * @param depthError uncertainty of that height, in blocks
     * @param radius equivalent radius of the area above half the peak, in blocks
     * @param positionError horizontal position uncertainty, in blocks
     * @param attenuation absorption attribute 0–255, or 0 when not resolved
     */
    public record Anomaly(
            double x,
            double z,
            double intensity,
            int polarity,
            int depthY,
            int depthError,
            int radius,
            int positionError,
            int attenuation) {}

    /** Detection threshold in signal-to-noise units for a mode. */
    public static double threshold(SurveyMode mode) {
        return mode == SurveyMode.REGIONAL ? 4.4D : 4.5D;
    }

    /**
     * Anomalies of {@code map}, strongest first.
     *
     * @param context station height and depth error used to quote depth errors; null when only
     *     counting
     */
    public static List<Anomaly> find(SurveyMap map, SurveyMetricsContext context) {
        return find(map, context, MAXIMUM_ANOMALIES);
    }

    /** Up to {@code limit} anomalies of {@code map}, strongest first. */
    public static List<Anomaly> find(SurveyMap map, SurveyMetricsContext context, int limit) {
        int resolution = map.resolution();
        double threshold = threshold(map.mode());
        List<int[]> peaks = new ArrayList<>();
        for (int row = 0; row < resolution; row++) {
            for (int column = 0; column < resolution; column++) {
                int index = column + row * resolution;
                double value = map.intensity(index);
                if (value < threshold || !isLocalMaximum(map, column, row, value)) {
                    continue;
                }
                peaks.add(new int[] {index});
            }
        }
        peaks.sort(
                Comparator.comparingDouble((int[] peak) -> -map.intensity(peak[0]))
                        .thenComparingInt(peak -> peak[0]));
        List<Anomaly> anomalies = new ArrayList<>();
        boolean detailed = map.mode().detailedAttributes();
        for (int[] peak : peaks) {
            if (anomalies.size() >= limit) {
                break;
            }
            int index = peak[0];
            int column = index % resolution;
            int row = index / resolution;
            double intensity = map.intensity(index);
            int area = area(map, column, row, Math.max(threshold * 0.6D, intensity * 0.5D));
            int radius = (int) Math.round(Math.sqrt(area / Math.PI) * map.pixelSize());
            int depthY = detailed ? map.depthY()[index] : Integer.MIN_VALUE;
            int depthError = 0;
            if (detailed && context != null) {
                depthError =
                        (int)
                                Math.round(
                                        Math.max(1.0D, context.datumY() - depthY)
                                                * context.depthError());
            }
            anomalies.add(
                    new Anomaly(
                            map.pixelX(column),
                            map.pixelZ(row),
                            intensity,
                            detailed ? (int) Math.signum(map.amplitude()[index]) : 0,
                            depthY,
                            depthError,
                            Math.max((int) Math.round(map.pixelSize() * 0.5D), radius),
                            (int) Math.round(map.lateralSigma()[index]),
                            detailed ? map.attenuation()[index] & 0xFF : 0));
        }
        return List.copyOf(anomalies);
    }

    /** Station height and relative depth error used to quote anomaly depths. */
    public record SurveyMetricsContext(double datumY, double depthError) {}

    private static boolean isLocalMaximum(SurveyMap map, int column, int row, double value) {
        int resolution = map.resolution();
        for (int dz = -SUPPRESSION_RADIUS; dz <= SUPPRESSION_RADIUS; dz++) {
            for (int dx = -SUPPRESSION_RADIUS; dx <= SUPPRESSION_RADIUS; dx++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int c = column + dx;
                int r = row + dz;
                if (c < 0 || r < 0 || c >= resolution || r >= resolution) {
                    continue;
                }
                double other = map.intensity(c + r * resolution);
                // Ties go to the lower index so a flat plateau yields exactly one peak.
                if (other > value
                        || other == value && c + r * resolution < column + row * resolution) {
                    return false;
                }
            }
        }
        return true;
    }

    private static int area(SurveyMap map, int column, int row, double level) {
        int resolution = map.resolution();
        boolean[] seen = new boolean[map.size()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(column + row * resolution);
        seen[column + row * resolution] = true;
        int count = 0;
        while (!queue.isEmpty() && count < 4096) {
            int index = queue.removeFirst();
            count++;
            int c = index % resolution;
            int r = index / resolution;
            int[][] neighbours = {{c + 1, r}, {c - 1, r}, {c, r + 1}, {c, r - 1}};
            for (int[] neighbour : neighbours) {
                if (neighbour[0] < 0
                        || neighbour[1] < 0
                        || neighbour[0] >= resolution
                        || neighbour[1] >= resolution) {
                    continue;
                }
                int next = neighbour[0] + neighbour[1] * resolution;
                if (!seen[next] && map.intensity(next) >= level) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return count;
    }
}

package dev.client.ore;

import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;

/** Extracts a representative, frequency-weighted color from item texture pixels. */
final class TextureColorAverager {
    private static final int MINIMUM_ALPHA = 128;
    private static final double DARKEST_BRIGHTNESS_FRACTION = 0.15D;

    private TextureColorAverager() {}

    static Analysis analyze(int[] pixels, int maximumPaletteSize) {
        int brightest = 0;
        for (int argb : pixels) {
            if (alpha(argb) >= MINIMUM_ALPHA) {
                brightest = Math.max(brightest, brightness(argb));
            }
        }
        if (brightest == 0) {
            return Analysis.EMPTY;
        }

        int darkCutoff = (int) Math.floor(brightest * DARKEST_BRIGHTNESS_FRACTION);
        Map<Integer, Integer> frequencies = new HashMap<>();
        for (int argb : pixels) {
            if (alpha(argb) < MINIMUM_ALPHA || brightness(argb) <= darkCutoff) {
                continue;
            }
            frequencies.merge(argb & 0xFFFFFF, 1, Integer::sum);
        }
        if (frequencies.isEmpty()) {
            return Analysis.EMPTY;
        }

        long red = 0L;
        long green = 0L;
        long blue = 0L;
        long total = 0L;
        for (Map.Entry<Integer, Integer> entry : frequencies.entrySet()) {
            int rgb = entry.getKey();
            long frequency = entry.getValue();
            red += (long) (rgb >> 16 & 255) * frequency;
            green += (long) (rgb >> 8 & 255) * frequency;
            blue += (long) (rgb & 255) * frequency;
            total += frequency;
        }

        int average = (int) (red / total) << 16 | (int) (green / total) << 8 | (int) (blue / total);
        int[] palette =
                frequencies.entrySet().stream()
                        .sorted(
                                Map.Entry.<Integer, Integer>comparingByValue(
                                                Comparator.reverseOrder())
                                        .thenComparingInt(Map.Entry::getKey))
                        .limit(maximumPaletteSize)
                        .mapToInt(Map.Entry::getKey)
                        .toArray();
        return new Analysis(palette, average);
    }

    private static int alpha(int argb) {
        return argb >>> 24;
    }

    private static int brightness(int argb) {
        return Math.max(argb >> 16 & 255, Math.max(argb >> 8 & 255, argb & 255));
    }

    record Analysis(int[] palette, int averageRgb) {
        static final Analysis EMPTY = new Analysis(new int[0], -1);
    }
}

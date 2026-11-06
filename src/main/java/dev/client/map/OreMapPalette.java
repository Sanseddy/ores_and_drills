package dev.client.map;

import java.util.Locale;

/** Minecraft-free colour and number rules of the Factorio-style ore map. */
final class OreMapPalette {
    private OreMapPalette() {}

    /**
     * Factorio paints resource tiles in one flat colour; a gentle brightness falloff on depleted
     * tiles keeps that look while still showing where drills have already eaten into the patch.
     */
    static int shade(int rgb, long remaining, long initial) {
        double fullness =
                initial <= 0 ? 1.0D : Math.max(0.0D, Math.min(1.0D, remaining / (double) initial));
        double brightness = 0.72D + fullness * 0.28D;
        int red = (int) Math.round((rgb >> 16 & 255) * brightness);
        int green = (int) Math.round((rgb >> 8 & 255) * brightness);
        int blue = (int) Math.round((rgb & 255) * brightness);
        return 0xFF000000 | red << 16 | green << 8 | blue;
    }

    static String formatAmount(long amount) {
        return String.format(Locale.ROOT, "%,d", amount).replace(',', ' ');
    }

    static String compactAmount(long amount) {
        long positive = Math.max(0L, amount);
        if (positive < 1_000L) {
            return Long.toString(positive);
        }
        if (positive < 1_000_000L) {
            return compact(positive / 1_000.0D, "k");
        }
        if (positive < 1_000_000_000L) {
            return compact(positive / 1_000_000.0D, "M");
        }
        return compact(positive / 1_000_000_000.0D, "G");
    }

    private static String compact(double value, String suffix) {
        return (value >= 100.0D || Math.abs(value - Math.rint(value)) < 0.05D
                        ? String.format(Locale.ROOT, "%.0f", value)
                        : String.format(Locale.ROOT, "%.1f", value))
                + suffix;
    }
}

package dev.util;

/** Safe integer progress calculations for menus and HUD elements. */
public final class ProgressMath {
    private ProgressMath() {
    }

    /**
     * Scales {@code current / maximum} into {@code size}. Positive progress is always visible as one
     * pixel, and values received during a partially synchronized menu are clamped to the bar bounds.
     */
    public static int scaled(int current, int maximum, int size) {
        if (current <= 0 || maximum <= 0 || size <= 0) {
            return 0;
        }

        long scaled = (long) current * size / maximum;
        return Math.max(1, (int) Math.min(size, scaled));
    }

    /** Returns a bounded 0..100 percentage without overflowing integer multiplication. */
    public static int percent(int current, int maximum) {
        if (current <= 0 || maximum <= 0) {
            return 0;
        }

        return (int) Math.min(100L, (long) current * 100L / maximum);
    }
}

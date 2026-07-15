package dev.world.level.levelgen;

import java.util.Locale;

/**
 * Ordered deposit tiers. All numeric properties are derived from the ordinal position, so adding a tier
 * here automatically places it on every size, density, rarity, spawn-frequency and spacing curve.
 */
public enum DepositTier {
    TINY,
    SMALL,
    MEDIUM,
    LARGE;

    public double normalizedPosition() {
        int lastIndex = values().length - 1;
        return lastIndex <= 0 ? 0.0D : ordinal() / (double) lastIndex;
    }

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static DepositTier byIndex(int index) {
        DepositTier[] tiers = values();
        if (index < 0 || index >= tiers.length) {
            return tiers[0];
        }
        return tiers[index];
    }
}

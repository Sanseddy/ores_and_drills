package dev.world.level.levelgen;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/** Public, command-facing deposit size. Its ordinal intentionally matches the internal tier curves. */
public enum OreDepositTier {
    TINY,
    SMALL,
    MEDIUM,
    LARGE;

    private static final List<String> NAMES = Arrays.stream(values())
            .map(OreDepositTier::serializedName)
            .toList();

    public String serializedName() {
        return name().toLowerCase(Locale.ROOT);
    }

    public int index() {
        return ordinal();
    }

    public boolean prefersCaveWall() {
        return this == TINY || this == SMALL;
    }

    public static Optional<OreDepositTier> fromName(String name) {
        if (name == null) {
            return Optional.empty();
        }
        String normalized = name.toLowerCase(Locale.ROOT);
        return Arrays.stream(values())
                .filter(tier -> tier.serializedName().equals(normalized))
                .findFirst();
    }

    public static Optional<OreDepositTier> fromIndex(int index) {
        OreDepositTier[] tiers = values();
        return index >= 0 && index < tiers.length ? Optional.of(tiers[index]) : Optional.empty();
    }

    public static List<String> names() {
        return NAMES;
    }
}

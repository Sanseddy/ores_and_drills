package dev.config;

/**
 * Per-world generation mode chosen on the world creation screen and persisted in {@code level.dat}.
 * {@code vanillaLikeGeneration} makes TINY/SMALL deposits follow each original ore feature's own
 * placement (count, height distribution, rarity, biome filters) instead of the per-cell deposit planner.
 */
public final class OreGenerationMode {
    private static volatile boolean vanillaLikeGeneration;

    private OreGenerationMode() {
    }

    public static boolean vanillaLikeGeneration() {
        return vanillaLikeGeneration;
    }

    /** Called while a world is created or its level.dat is read, before biome modifiers are applied. */
    public static void setVanillaLikeGeneration(boolean enabled) {
        vanillaLikeGeneration = enabled;
    }
}

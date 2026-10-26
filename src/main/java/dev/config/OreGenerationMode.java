package dev.config;

/**
 * Per-world generation mode chosen on the world creation screen and persisted in {@code level.dat}.
 * {@code vanillaLikeGeneration} turns every vein of each original ore feature, placed by that
 * feature's own modifiers (count, height distribution, rarity, biome filters), into deposit blocks
 * instead of thinning its attempts into TINY/SMALL candidates.
 */
public final class OreGenerationMode {
    private static volatile boolean vanillaLikeGeneration;

    private OreGenerationMode() {}

    public static boolean vanillaLikeGeneration() {
        return vanillaLikeGeneration;
    }

    /**
     * Called while a world is created or its level.dat is read, before biome modifiers are applied.
     */
    public static void setVanillaLikeGeneration(boolean enabled) {
        vanillaLikeGeneration = enabled;
    }
}

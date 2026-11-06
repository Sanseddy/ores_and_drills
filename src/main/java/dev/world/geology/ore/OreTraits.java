package dev.world.geology.ore;

/**
 * What the game data says about one ore material: how its drops are classified by the common tags,
 * how hard it is to mine, and — when the world records its generation — where and how often it
 * spawns. Nothing here is specific to a particular ore; any mod's ore is described the same way.
 *
 * @param toolTier lowest pickaxe tier that harvests it: 0 any, 1 stone, 2 iron, 3 diamond
 * @param hardness block destroy time
 * @param minY lowest generation height, meaningful only when {@link #generationKnown()}
 * @param maxY highest generation height, meaningful only when {@link #generationKnown()}
 * @param frequency original placement attempts per chunk; NaN when generation is unknown
 */
public record OreTraits(
        String materialKey,
        String oreId,
        Category category,
        int toolTier,
        double hardness,
        int minY,
        int maxY,
        double frequency) {
    /** How the material's products are tagged. */
    public enum Category {
        /** Has raw materials or ingots: a metal ore. */
        METALLIC,
        /** Has gems: a crystalline ore. */
        GEM,
        /** Has dusts: a soft, fine-grained ore. */
        DUST,
        /** None of the above (fuels, mod-specific products). */
        OTHER
    }

    public OreTraits {
        if (materialKey == null || oreId == null || category == null) {
            throw new IllegalArgumentException("Ore traits need a material, an ore and a category");
        }
        hardness = Double.isFinite(hardness) ? Math.max(0.0D, hardness) : 0.0D;
        toolTier = Math.max(0, Math.min(3, toolTier));
    }

    public boolean generationKnown() {
        return Double.isFinite(frequency) && frequency > 0.0D && maxY >= minY;
    }

    public double meanY() {
        return (minY + maxY) * 0.5D;
    }
}

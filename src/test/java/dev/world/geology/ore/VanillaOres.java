package dev.world.geology.ore;

import java.util.List;

/** Ore traits as a plain vanilla overworld records them: tags, tool tiers, heights, attempts. */
public final class VanillaOres {
    private VanillaOres() {}

    public static List<OreTraits> overworld() {
        return List.of(
                ore("coal", OreTraits.Category.OTHER, 0, 0, 192, 30.0D),
                ore("copper", OreTraits.Category.METALLIC, 1, -16, 112, 16.0D),
                ore("iron", OreTraits.Category.METALLIC, 1, -64, 72, 20.0D),
                ore("gold", OreTraits.Category.METALLIC, 2, -64, 32, 4.5D),
                ore("redstone", OreTraits.Category.DUST, 2, -64, 15, 8.0D),
                ore("lapis", OreTraits.Category.GEM, 1, -64, 64, 3.0D),
                ore("diamond", OreTraits.Category.GEM, 2, -64, 16, 2.5D),
                ore("emerald", OreTraits.Category.GEM, 2, -16, 320, 1.5D));
    }

    public static List<OreSignature> table() {
        return OreSignatureMath.build(overworld(), -64, 320);
    }

    public static OreSignature signature(String material) {
        for (OreSignature signature : table()) {
            if (signature.traits().materialKey().equals(material)) {
                return signature;
            }
        }
        throw new IllegalArgumentException(material);
    }

    static OreTraits ore(
            String material,
            OreTraits.Category category,
            int tier,
            int minY,
            int maxY,
            double frequency) {
        return new OreTraits(
                material,
                "minecraft:" + material + "_ore",
                category,
                tier,
                3.0D,
                minY,
                maxY,
                frequency);
    }
}

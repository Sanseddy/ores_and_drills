package dev.client.map;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Minecraft-free colour and number rules of the Factorio-style ore map. */
final class OreMapPalette {
    /**
     * Ordered so specific names win over substrings ("redstone" before "red", "certus_quartz"
     * before "quartz").
     */
    private static final Map<String, Integer> MATERIALS = new LinkedHashMap<>();

    static {
        // Factorio's own map colours for the resources it shares with Minecraft.
        MATERIALS.put("iron", 0x6A8694);
        MATERIALS.put("copper", 0xCD6337);
        MATERIALS.put("coal", 0x101010);
        MATERIALS.put("uranium", 0x00B300);
        MATERIALS.put("uraninite", 0x00B300);
        MATERIALS.put("stone", 0xB09C6D);
        // Everything else follows the same rule: one saturated, instantly recognisable tone per
        // resource.
        MATERIALS.put("ancient_debris", 0x6E4C3C);
        MATERIALS.put("netherite", 0x6E4C3C);
        MATERIALS.put("redstone", 0xC4241B);
        MATERIALS.put("lapis", 0x2F5BC8);
        MATERIALS.put("diamond", 0x4FD8D2);
        MATERIALS.put("emerald", 0x1FBF55);
        MATERIALS.put("certus_quartz", 0xB9D5F0);
        MATERIALS.put("quartz", 0xE8E2D8);
        MATERIALS.put("gold", 0xE5BF3B);
        MATERIALS.put("tin", 0xC2CCD2);
        MATERIALS.put("zinc", 0x9FB3A6);
        MATERIALS.put("lead", 0x58598A);
        MATERIALS.put("galena", 0x6F6E86);
        MATERIALS.put("silver", 0xD3DAE0);
        MATERIALS.put("nickel", 0xC9C29A);
        MATERIALS.put("bauxite", 0xB86F45);
        MATERIALS.put("aluminum", 0xD6B3A0);
        MATERIALS.put("aluminium", 0xD6B3A0);
        MATERIALS.put("osmium", 0x8DB8CB);
        MATERIALS.put("platinum", 0xA9DFF2);
        MATERIALS.put("iridium", 0xD8E3E0);
        MATERIALS.put("cobalt", 0x2E66D6);
        MATERIALS.put("titanium", 0xB0BAC0);
        MATERIALS.put("tungsten", 0x3F4045);
        MATERIALS.put("chromium", 0xB6C1CC);
        MATERIALS.put("thorium", 0x4E7A47);
        MATERIALS.put("monazite", 0x5F7B3A);
        MATERIALS.put("cinnabar", 0xC2394A);
        MATERIALS.put("sulfur", 0xE6D447);
        MATERIALS.put("niter", 0xE3E1D2);
        MATERIALS.put("saltpeter", 0xE3E1D2);
        MATERIALS.put("salt", 0xEDEDED);
        MATERIALS.put("fluorite", 0x5AD1C0);
        MATERIALS.put("apatite", 0x3FA9D6);
        MATERIALS.put("ruby", 0xD0213F);
        MATERIALS.put("sapphire", 0x2742C9);
        MATERIALS.put("peridot", 0x7BC23B);
        MATERIALS.put("amethyst", 0x9A5CC6);
        MATERIALS.put("desh", 0xD07A3F);
        MATERIALS.put("ostrum", 0x8C5C78);
        MATERIALS.put("calorite", 0xB13A3A);
    }

    private OreMapPalette() {}

    /**
     * RGB for a material or ore name such as {@code iron} or {@code deepslate_copper_ore}; null if
     * unknown. Whole words are matched first so "redstone" is never read as "stone", "platinum" as
     * "tin" or "basalt" as "salt"; run-together words such as {@code ironore} are also read as
     * their material.
     */
    static Integer materialColor(String name) {
        String normalized =
                "_" + name.toLowerCase(Locale.ROOT).replace('/', '_').replace('-', '_') + "_";
        for (Map.Entry<String, Integer> material : MATERIALS.entrySet()) {
            if (normalized.contains("_" + material.getKey() + "_")) {
                return material.getValue();
            }
        }
        for (String word : normalized.split("_")) {
            if (word.length() > 3 && word.endsWith("ore")) {
                Integer stem = MATERIALS.get(word.substring(0, word.length() - 3));
                if (stem != null) {
                    return stem;
                }
            }
        }
        return null;
    }

    /** The palette entry with the strongest chroma that is neither near-black nor near-white. */
    static Integer mostVivid(int[] palette) {
        Integer best = null;
        int bestChroma = -1;
        for (int argb : palette) {
            int rgb = argb & 0xFFFFFF;
            int red = rgb >> 16 & 255;
            int green = rgb >> 8 & 255;
            int blue = rgb & 255;
            int max = Math.max(red, Math.max(green, blue));
            int min = Math.min(red, Math.min(green, blue));
            if (max < 40 || min > 235) {
                continue;
            }
            if (max - min > bestChroma) {
                bestChroma = max - min;
                best = rgb;
            }
        }
        return best;
    }

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

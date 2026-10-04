package dev.client.ore.speck;

/** Builds small ore textures from ASCII maps: '.' is host rock, any other character is an ore pixel. */
final class SpeckTestTextures {
    static final int[] IRON_PALETTE = {0xD8AF93, 0xAF8E77, 0x7A5A44};

    /**
     * Spec example: 5 specks of type 1, 3 of type 2, 2 of type 3 and 1 of type 4 (11 in total).
     */
    static final String[] EXAMPLE_MAP = {
            "a.....bb......a.",
            "................",
            "..ccc......d....",
            "...........dd...",
            ".eee............",
            ".ee.......ffff..",
            "..........ffff..",
            "................",
            "....gggg........",
            "....gggg.....h..",
            ".....gg.........",
            "................",
            "..ii.......a....",
            "..ii............",
            "................",
            "................"
    };

    private SpeckTestTextures() {
    }

    /** Grey rock with a little per-pixel variation, like a stone texture. */
    static ArgbImage host() {
        int[] pixels = new int[16 * 16];
        for (int y = 0; y < 16; y++) {
            for (int x = 0; x < 16; x++) {
                int value = 0x80 + ((x * 7 + y * 13) % 25) - 12;
                pixels[y * 16 + x] = 0xFF000000 | value << 16 | value << 8 | value;
            }
        }
        return new ArgbImage(16, 16, pixels);
    }

    /** {@code map} painted over {@code host} with palette colors that vary per pixel. */
    static ArgbImage ore(ArgbImage host, String[] map, int[] palette) {
        int[] pixels = host.pixels().clone();
        for (int y = 0; y < map.length; y++) {
            for (int x = 0; x < map[y].length(); x++) {
                if (map[y].charAt(x) != '.') {
                    pixels[y * host.width() + x] = 0xFF000000 | palette[(x + y) % palette.length];
                }
            }
        }
        return new ArgbImage(host.width(), host.height(), pixels);
    }

    static OreSpeckAnalysis exampleAnalysis() {
        ArgbImage host = host();
        return OreSpeckAnalysis.analyze(ore(host, EXAMPLE_MAP, IRON_PALETTE), host, IRON_PALETTE);
    }
}

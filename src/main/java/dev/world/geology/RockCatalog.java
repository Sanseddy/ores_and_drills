package dev.world.geology;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Built-in elastic properties of rocks and fluids: typical laboratory figures for the real rock
 * each block is named after. Ores have no entries here; their minerals are derived from game data
 * by {@link dev.world.geology.ore.OreSignatureTable}. Datapacks and other mods override or extend
 * these through {@link RockPhysics}.
 */
public final class RockCatalog {
    /** Crystalline basement rock; Minecraft stone is the generic host of overworld deposits. */
    public static final RockProperties STONE = new RockProperties(2.65D, 5500.0D, 150.0D);

    public static final RockProperties DEEPSLATE = new RockProperties(2.80D, 6100.0D, 250.0D);
    public static final RockProperties GRANITE = new RockProperties(2.65D, 5800.0D, 200.0D);
    public static final RockProperties DIORITE = new RockProperties(2.85D, 6300.0D, 220.0D);
    public static final RockProperties ANDESITE = new RockProperties(2.60D, 5300.0D, 120.0D);
    public static final RockProperties TUFF = new RockProperties(1.90D, 2600.0D, 40.0D);
    public static final RockProperties CALCITE = new RockProperties(2.71D, 6400.0D, 150.0D);
    public static final RockProperties DRIPSTONE = new RockProperties(2.70D, 6000.0D, 120.0D);
    public static final RockProperties SANDSTONE = new RockProperties(2.30D, 3000.0D, 40.0D);
    public static final RockProperties SOIL = new RockProperties(1.80D, 800.0D, 20.0D);
    public static final RockProperties GRAVEL = new RockProperties(2.00D, 1500.0D, 25.0D);
    public static final RockProperties CLAY = new RockProperties(1.90D, 1700.0D, 25.0D);
    public static final RockProperties NETHERRACK = new RockProperties(2.20D, 3200.0D, 60.0D);
    public static final RockProperties BASALT = new RockProperties(2.90D, 6000.0D, 200.0D);
    public static final RockProperties BLACKSTONE = new RockProperties(2.75D, 5600.0D, 180.0D);
    public static final RockProperties END_STONE = new RockProperties(2.30D, 3500.0D, 80.0D);
    public static final RockProperties OBSIDIAN = new RockProperties(2.40D, 5800.0D, 300.0D);
    public static final RockProperties BEDROCK = new RockProperties(3.00D, 7000.0D, 400.0D);
    public static final RockProperties WATER = new RockProperties(1.00D, 1480.0D, 1000.0D);
    public static final RockProperties LAVA = new RockProperties(2.60D, 2500.0D, 10.0D);
    public static final RockProperties AIR = new RockProperties(0.0012D, 343.0D, 1000.0D);

    private static final Map<String, RockProperties> BLOCKS = createBlocks();

    private RockCatalog() {}

    /** Properties of a block by its registry id, if the catalog knows it. */
    public static Optional<RockProperties> block(String blockId) {
        return Optional.ofNullable(BLOCKS.get(blockId));
    }

    public static Map<String, RockProperties> blocks() {
        return BLOCKS;
    }

    private static Map<String, RockProperties> createBlocks() {
        Map<String, RockProperties> blocks = new LinkedHashMap<>();
        put(blocks, STONE, "stone", "cobblestone", "mossy_cobblestone", "infested_stone");
        put(blocks, DEEPSLATE, "deepslate", "cobbled_deepslate", "infested_deepslate");
        put(blocks, GRANITE, "granite");
        put(blocks, DIORITE, "diorite");
        put(blocks, ANDESITE, "andesite");
        put(blocks, TUFF, "tuff");
        put(blocks, CALCITE, "calcite", "amethyst_block", "budding_amethyst");
        put(blocks, DRIPSTONE, "dripstone_block", "pointed_dripstone");
        put(blocks, SANDSTONE, "sandstone", "red_sandstone", "smooth_basalt");
        put(
                blocks,
                SOIL,
                "dirt",
                "grass_block",
                "coarse_dirt",
                "rooted_dirt",
                "podzol",
                "mycelium",
                "mud",
                "sand",
                "red_sand",
                "snow_block",
                "moss_block");
        put(blocks, GRAVEL, "gravel");
        put(blocks, CLAY, "clay", "terracotta", "packed_mud");
        put(blocks, NETHERRACK, "netherrack", "soul_sand", "soul_soil", "magma_block");
        put(blocks, BASALT, "basalt", "polished_basalt");
        put(blocks, BLACKSTONE, "blackstone", "gilded_blackstone");
        put(blocks, END_STONE, "end_stone");
        put(blocks, OBSIDIAN, "obsidian", "crying_obsidian");
        put(blocks, BEDROCK, "bedrock");
        put(blocks, WATER, "water", "ice", "packed_ice", "blue_ice");
        put(blocks, LAVA, "lava");
        put(blocks, AIR, "air", "cave_air", "void_air");
        return Map.copyOf(blocks);
    }

    private static void put(
            Map<String, RockProperties> blocks, RockProperties rock, String... ids) {
        for (String id : ids) {
            blocks.put("minecraft:" + id, rock);
        }
    }
}

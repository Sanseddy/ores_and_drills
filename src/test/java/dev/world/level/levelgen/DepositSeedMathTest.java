package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class DepositSeedMathTest {
    private static long seed(
            long worldSeed,
            String dimension,
            String biome,
            String ore,
            long tier,
            int cellX,
            int cellZ,
            int slot,
            long rule
    ) {
        return DepositSeedMath.cellSeed(
                worldSeed, dimension, biome, ore, tier, cellX, cellZ, slot, rule,
                341873128712L, 132897987541L
        );
    }

    @Test
    void identicalInputsAlwaysProduceTheSameCellSeed() {
        long first = seed(42L, "minecraft:overworld", "alexscaves:toxic_caves",
                "mekanism:uranium_ore", 4L, -7, 19, 2, 81L);
        assertEquals(first, seed(42L, "minecraft:overworld", "alexscaves:toxic_caves",
                "mekanism:uranium_ore", 4L, -7, 19, 2, 81L));
    }

    @Test
    void EveryRequiredInputOwnsAnIndependentSeedSpace() {
        long base = seed(42L, "minecraft:overworld", "alexscaves:toxic_caves",
                "mekanism:uranium_ore", 4L, -7, 19, 2, 81L);
        assertNotEquals(base, seed(43L, "minecraft:overworld", "alexscaves:toxic_caves", "mekanism:uranium_ore", 4L, -7, 19, 2, 81L));
        assertNotEquals(base, seed(42L, "minecraft:the_nether", "alexscaves:toxic_caves", "mekanism:uranium_ore", 4L, -7, 19, 2, 81L));
        assertNotEquals(base, seed(42L, "minecraft:overworld", "minecraft:plains", "mekanism:uranium_ore", 4L, -7, 19, 2, 81L));
        assertNotEquals(base, seed(42L, "minecraft:overworld", "alexscaves:toxic_caves", "minecraft:iron_ore", 4L, -7, 19, 2, 81L));
        assertNotEquals(base, seed(42L, "minecraft:overworld", "alexscaves:toxic_caves", "mekanism:uranium_ore", 5L, -7, 19, 2, 81L));
        assertNotEquals(base, seed(42L, "minecraft:overworld", "alexscaves:toxic_caves", "mekanism:uranium_ore", 4L, -6, 19, 2, 81L));
        assertNotEquals(base, seed(42L, "minecraft:overworld", "alexscaves:toxic_caves", "mekanism:uranium_ore", 4L, -7, 20, 2, 81L));
        assertNotEquals(base, seed(42L, "minecraft:overworld", "alexscaves:toxic_caves", "mekanism:uranium_ore", 4L, -7, 19, 3, 81L));
        assertNotEquals(base, seed(42L, "minecraft:overworld", "alexscaves:toxic_caves", "mekanism:uranium_ore", 4L, -7, 19, 2, 82L));
    }
}

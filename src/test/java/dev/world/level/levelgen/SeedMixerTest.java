package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class SeedMixerTest {
    @Test
    void stringHashIsStable() {
        assertEquals(SeedMixer.hash64("minecraft:overworld"), SeedMixer.hash64("minecraft:overworld"));
        assertNotEquals(SeedMixer.hash64("minecraft:overworld"), SeedMixer.hash64("minecraft:the_nether"));
    }

    @Test
    void everyCandidateInputCanPerturbThePlan() {
        long base = SeedMixer.hash64("seed|dimension|biome|ore|tiny|4|-9");
        assertNotEquals(base, SeedMixer.hash64("other-seed|dimension|biome|ore|tiny|4|-9"));
        assertNotEquals(base, SeedMixer.hash64("seed|other-dimension|biome|ore|tiny|4|-9"));
        assertNotEquals(base, SeedMixer.hash64("seed|dimension|other-biome|ore|tiny|4|-9"));
        assertNotEquals(base, SeedMixer.hash64("seed|dimension|biome|other-ore|tiny|4|-9"));
        assertNotEquals(base, SeedMixer.hash64("seed|dimension|biome|ore|large|4|-9"));
        assertNotEquals(base, SeedMixer.hash64("seed|dimension|biome|ore|tiny|5|-9"));
    }
}

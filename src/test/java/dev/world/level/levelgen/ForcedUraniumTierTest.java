package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForcedUraniumTierTest {
    @Test
    void forcedUraniumUsesTheLargeTier() {
        assertEquals(
                3,
                ForcedUraniumTierMath.resolvedTier(2, true, 3),
                "a forced uranium deposit must remain LARGE"
        );
        assertEquals(2, ForcedUraniumTierMath.resolvedTier(2, false, 3));
    }
}

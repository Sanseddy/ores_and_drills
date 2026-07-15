package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreDepositTierTest {
    @Test
    void parsesOnlyTheFourSupportedNamesCaseInsensitively() {
        assertEquals(OreDepositTier.TINY, OreDepositTier.fromName("tiny").orElseThrow());
        assertEquals(OreDepositTier.LARGE, OreDepositTier.fromName("LaRgE").orElseThrow());
        assertTrue(OreDepositTier.fromName("huge").isEmpty());
        assertTrue(OreDepositTier.fromName(null).isEmpty());
        assertEquals(List.of("tiny", "small", "medium", "large"), OreDepositTier.names());
    }

    @Test
    void ordinalContractMatchesWorldgenTierIndices() {
        for (int index = 0; index < OreDepositTier.values().length; index++) {
            assertEquals(index, OreDepositTier.fromIndex(index).orElseThrow().index());
        }
        assertTrue(OreDepositTier.fromIndex(-1).isEmpty());
        assertTrue(OreDepositTier.fromIndex(4).isEmpty());
    }

    @Test
    void onlyTinyAndSmallRequireRealCaveWalls() {
        assertTrue(OreDepositTier.TINY.prefersCaveWall());
        assertTrue(OreDepositTier.SMALL.prefersCaveWall());
        assertTrue(!OreDepositTier.MEDIUM.prefersCaveWall());
        assertTrue(!OreDepositTier.LARGE.prefersCaveWall());
    }
}

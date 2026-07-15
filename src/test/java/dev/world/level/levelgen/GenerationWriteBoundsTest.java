package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GenerationWriteBoundsTest {
    @Test
    void featureRadiusOneProducesExactlyThreeByThreeChunks() {
        GenerationWriteBounds bounds = GenerationWriteBounds.around(10, -4, 1);

        assertEquals(9 << 4, bounds.minimumX());
        assertEquals((11 << 4) + 15, bounds.maximumX());
        assertEquals(-5 << 4, bounds.minimumZ());
        assertEquals((-3 << 4) + 15, bounds.maximumZ());
        assertEquals(48, bounds.maximumX() - bounds.minimumX() + 1);
        assertEquals(48, bounds.maximumZ() - bounds.minimumZ() + 1);
    }

    @Test
    void containsIncludesEdgesAndRejectsTheFirstFarChunkBlocks() {
        GenerationWriteBounds bounds = GenerationWriteBounds.around(0, 0, 1);

        assertTrue(bounds.contains(-16, -16));
        assertTrue(bounds.contains(31, 31));
        assertFalse(bounds.contains(-17, 0));
        assertFalse(bounds.contains(32, 0));
        assertFalse(bounds.contains(0, -17));
        assertFalse(bounds.contains(0, 32));
    }

    @Test
    void negativeChunkCoordinatesRetainCorrectInclusiveBlockBounds() {
        GenerationWriteBounds bounds = GenerationWriteBounds.around(-2, -3, 1);

        assertEquals(-48, bounds.minimumX());
        assertEquals(-1, bounds.maximumX());
        assertEquals(-64, bounds.minimumZ());
        assertEquals(-17, bounds.maximumZ());
    }
}

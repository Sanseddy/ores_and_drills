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
    void squareMustFitOnEverySide() {
        GenerationWriteBounds bounds = GenerationWriteBounds.around(0, 0, 1);

        assertTrue(bounds.containsSquare(-13, 28, 3));
        assertFalse(bounds.containsSquare(-14, 0, 3));
        assertFalse(bounds.containsSquare(29, 0, 3));
        assertFalse(bounds.containsSquare(0, -14, 3));
        assertFalse(bounds.containsSquare(0, 29, 3));
    }

    @Test
    void insetShrinksEverySideAndCanBecomeEmpty() {
        GenerationWriteBounds inset = GenerationWriteBounds.around(0, 0, 1).inset(4);

        assertEquals(-12, inset.minimumX());
        assertEquals(27, inset.maximumX());
        assertEquals(-12, inset.minimumZ());
        assertEquals(27, inset.maximumZ());
        GenerationWriteBounds exhausted = GenerationWriteBounds.around(0, 0, 0).inset(8);
        for (int x = -2; x <= 17; x++) {
            assertFalse(exhausted.contains(x, 8));
        }
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

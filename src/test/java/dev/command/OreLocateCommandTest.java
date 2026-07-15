package dev.command;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OreLocateCommandTest {
    private static final double EPSILON = 1.0E-9D;

    @Test
    void locateDistanceIsHorizontalAndIgnoresHeight() {
        assertEquals(0.0D,
                LocateDistanceMath.horizontalDistanceSqr(10, -4, 10, -4),
                EPSILON);
        assertEquals(25.0D,
                LocateDistanceMath.horizontalDistanceSqr(10, -4, 13, 0),
                EPSILON);
    }

    @Test
    void mediumLargeSearchIncludesRelocationAndLensReach() {
        // 12 blocks from chunk center to corner + 64 tagged-stone relocation + 32 lens radius.
        assertEquals(263, LocateDistanceMath.chunkRadius(4096, 108));
        assertEquals(0.0D, LocateDistanceMath.minimumReachableDistanceForRing(6, 108), EPSILON);
        assertEquals(4.0D, LocateDistanceMath.minimumReachableDistanceForRing(7, 108), EPSILON);

        // A source-chunk center just outside the requested radius can still produce a deposit inside it.
        assertTrue(LocateDistanceMath.centerCanReachRadius(4204, 0, 0, 0, 4096, 108));
        assertFalse(LocateDistanceMath.centerCanReachRadius(4205, 0, 0, 0, 4096, 108));
        assertEquals(1031, LocateDistanceMath.chunkRadius(16384, 108));
        assertEquals(4_255_969, LocateDistanceMath.squareChunkChecks(16384, 108));
    }

    @Test
    void anchorGridRingsIncludeBoundaryRegionsAndUseAConservativeDistanceBound() {
        assertEquals(18, LocateDistanceMath.gridRingRadius(263, 16));
        assertEquals(0.0D,
                LocateDistanceMath.minimumReachableDistanceForGridRing(1, 16, 108), EPSILON);
        assertEquals(148.0D,
                LocateDistanceMath.minimumReachableDistanceForGridRing(2, 16, 108), EPSILON);

        // A one-chunk grid retains the tighter ordinary chunk-ring behavior.
        assertEquals(263, LocateDistanceMath.gridRingRadius(263, 1));
        assertEquals(4.0D,
                LocateDistanceMath.minimumReachableDistanceForGridRing(7, 1, 108), EPSILON);
    }

    @Test
    void knownLargeRecordUsesItsFootprintMarginRatherThanOnlyItsStoredBlock() {
        // The spatial index stores one actual deposit block. A lens may still cross the requested
        // radius when that particular block is just outside it.
        assertTrue(LocateDistanceMath.centerCanReachRadius(4_160, 0, 0, 0, 4_096, 64));
        assertFalse(LocateDistanceMath.centerCanReachRadius(4_161, 0, 0, 0, 4_096, 64));
    }

    @Test
    void ringTraversalVisitsEachPerimeterChunkOnceWithoutObjectOffsets() {
        assertEquals(0, LocateDistanceMath.ringOffsetX(LocateDistanceMath.ringOffset(0, 0)));
        assertEquals(0, LocateDistanceMath.ringOffsetZ(LocateDistanceMath.ringOffset(0, 0)));

        // Ring 2 walks the top, right, bottom, then left side, with no duplicate corners.
        assertEquals(-2, LocateDistanceMath.ringOffsetX(LocateDistanceMath.ringOffset(2, 0)));
        assertEquals(-2, LocateDistanceMath.ringOffsetZ(LocateDistanceMath.ringOffset(2, 0)));
        assertEquals(2, LocateDistanceMath.ringOffsetX(LocateDistanceMath.ringOffset(2, 4)));
        assertEquals(-2, LocateDistanceMath.ringOffsetZ(LocateDistanceMath.ringOffset(2, 4)));
        assertEquals(2, LocateDistanceMath.ringOffsetX(LocateDistanceMath.ringOffset(2, 8)));
        assertEquals(2, LocateDistanceMath.ringOffsetZ(LocateDistanceMath.ringOffset(2, 8)));
        assertEquals(-2, LocateDistanceMath.ringOffsetX(LocateDistanceMath.ringOffset(2, 12)));
        assertEquals(2, LocateDistanceMath.ringOffsetZ(LocateDistanceMath.ringOffset(2, 12)));
        assertEquals(LocateDistanceMath.NO_RING_OFFSET, LocateDistanceMath.ringOffset(2, 16));
    }
}

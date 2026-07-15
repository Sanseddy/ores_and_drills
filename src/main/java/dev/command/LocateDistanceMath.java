package dev.command;

/** Pure horizontal-distance math shared by locate runtime code and dependency-free unit tests. */
final class LocateDistanceMath {
    /** Sentinel returned by {@link #ringOffset(int, int)} for an invalid ring position. */
    static final long NO_RING_OFFSET = Long.MIN_VALUE;

    private LocateDistanceMath() {
    }

    static double horizontalDistanceSqr(int firstX, int firstZ, int secondX, int secondZ) {
        long deltaX = (long) firstX - secondX;
        long deltaZ = (long) firstZ - secondZ;
        return (double) deltaX * deltaX + (double) deltaZ * deltaZ;
    }

    static int chunkRadius(int blockRadius, int horizontalAllowance) {
        long expandedRadius = Math.max(0L, (long) blockRadius) + Math.max(0, horizontalAllowance);
        return (int) Math.min(Integer.MAX_VALUE, (expandedRadius + 15L) / 16L);
    }

    static int squareChunkChecks(int blockRadius, int horizontalAllowance) {
        long radius = chunkRadius(blockRadius, horizontalAllowance);
        long side = radius * 2L + 1L;
        return (int) Math.min(Integer.MAX_VALUE, side * side);
    }

    static boolean centerCanReachRadius(
            int centerX,
            int centerZ,
            int originX,
            int originZ,
            int blockRadius,
            int horizontalAllowance
    ) {
        double expandedRadius = Math.max(0L, (long) blockRadius) + Math.max(0, horizontalAllowance);
        return horizontalDistanceSqr(centerX, centerZ, originX, originZ) <= expandedRadius * expandedRadius;
    }

    static double minimumReachableDistanceForRing(int ring, int horizontalAllowance) {
        return Math.max(0.0D, Math.max(0, ring) * 16.0D - Math.max(0, horizontalAllowance));
    }

    /**
     * Conservative lower bound for a ring whose offsets are measured in multi-chunk grid cells. The
     * origin may sit at either edge of its own cell and an anchor may sit at the near edge of the target
     * cell, so one complete grid-cell span must be deducted for grids wider than one chunk.
     */
    static double minimumReachableDistanceForGridRing(int ring, int gridStepChunks, int horizontalAllowance) {
        int step = Math.max(1, gridStepChunks);
        if (step == 1) {
            return minimumReachableDistanceForRing(ring, horizontalAllowance);
        }
        long guaranteedChunkDistance = (long) Math.max(0, ring - 1) * step;
        return Math.max(0.0D, guaranteedChunkDistance * 16.0D - Math.max(0, horizontalAllowance));
    }

    /** Number of grid-cell rings required to cover a chunk radius without losing boundary cells. */
    static int gridRingRadius(int chunkRadius, int gridStepChunks) {
        int radius = Math.max(0, chunkRadius);
        int step = Math.max(1, gridStepChunks);
        if (step == 1) {
            return radius;
        }
        return (radius + step - 1) / step + 1;
    }

    /**
     * Returns one perimeter position of a square chunk ring, packed as X in the high 32 bits and Z in
     * the low 32 bits. Keeping this primitive avoids allocating an {@code int[2]} for every predicted
     * chunk during a large /locate search.
     */
    static long ringOffset(int ring, int perimeterIndex) {
        if (ring < 0 || perimeterIndex < 0) {
            return NO_RING_OFFSET;
        }
        if (ring == 0) {
            return perimeterIndex == 0 ? packOffset(0, 0) : NO_RING_OFFSET;
        }

        int sideLength = ring * 2;
        int perimeter = ring * 8;
        if (perimeterIndex >= perimeter) {
            return NO_RING_OFFSET;
        }
        if (perimeterIndex < sideLength) {
            return packOffset(-ring + perimeterIndex, -ring);
        }
        perimeterIndex -= sideLength;
        if (perimeterIndex < sideLength) {
            return packOffset(ring, -ring + perimeterIndex);
        }
        perimeterIndex -= sideLength;
        if (perimeterIndex < sideLength) {
            return packOffset(ring - perimeterIndex, ring);
        }
        perimeterIndex -= sideLength;
        return packOffset(-ring, ring - perimeterIndex);
    }

    static int ringOffsetX(long packedOffset) {
        return (int) (packedOffset >> 32);
    }

    static int ringOffsetZ(long packedOffset) {
        return (int) packedOffset;
    }

    private static long packOffset(int x, int z) {
        return ((long) x << 32) | (z & 0xFFFFFFFFL);
    }
}

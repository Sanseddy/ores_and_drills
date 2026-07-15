package dev.world.level.levelgen;

/** Inclusive block-coordinate bounds writable by one chunk-generation step. */
record GenerationWriteBounds(int minimumX, int maximumX, int minimumZ, int maximumZ) {
    static GenerationWriteBounds around(int centerChunkX, int centerChunkZ, int chunkRadius) {
        int radius = Math.max(0, chunkRadius);
        int minimumChunkX = centerChunkX - radius;
        int maximumChunkX = centerChunkX + radius;
        int minimumChunkZ = centerChunkZ - radius;
        int maximumChunkZ = centerChunkZ + radius;
        return new GenerationWriteBounds(
                minimumChunkX << 4,
                (maximumChunkX << 4) + 15,
                minimumChunkZ << 4,
                (maximumChunkZ << 4) + 15
        );
    }

    boolean contains(int x, int z) {
        return x >= minimumX && x <= maximumX && z >= minimumZ && z <= maximumZ;
    }
}

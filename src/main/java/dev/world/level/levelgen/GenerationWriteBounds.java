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

    /** True when the whole square of {@code radius} blocks around {@code (x, z)} is inside. */
    boolean containsSquare(int x, int z, int radius) {
        return contains(x - radius, z - radius) && contains(x + radius, z + radius);
    }

    /** These bounds shrunk by {@code blocks} on every side; an exhausted area contains nothing. */
    GenerationWriteBounds inset(int blocks) {
        int margin = Math.max(0, blocks);
        return new GenerationWriteBounds(minimumX + margin, maximumX - margin, minimumZ + margin, maximumZ - margin);
    }
}

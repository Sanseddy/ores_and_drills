package dev.world.level.levelgen;

/** Pure immutable seed composition shared by worldgen and locate planners. */
final class DepositSeedMath {
    private DepositSeedMath() {
    }

    static long cellSeed(
            long worldSeed,
            String dimension,
            String biome,
            String ore,
            long tierSalt,
            int cellX,
            int cellZ,
            int slot,
            long ruleSalt,
            long coordinateSaltX,
            long coordinateSaltZ
    ) {
        long seed = worldSeed;
        seed ^= SeedMixer.hash64(dimension);
        seed ^= Long.rotateLeft(SeedMixer.hash64(biome), 7);
        seed ^= Long.rotateLeft(SeedMixer.hash64(ore), 17);
        seed ^= tierSalt;
        seed ^= (long) cellX * coordinateSaltX;
        seed ^= (long) cellZ * coordinateSaltZ;
        seed ^= (long) slot * 0x9E3779B97F4A7C15L;
        seed ^= ruleSalt;
        return SeedMixer.mix(seed);
    }
}

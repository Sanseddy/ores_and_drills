package dev.world.level.levelgen;

/** Seed-to-unit-interval conversion isolated for deterministic TINY/SMALL gate decisions. */
final class SmallTierSeedMath {
    private SmallTierSeedMath() {
    }

    static double unitDouble(long seed) {
        long mixed = mix64(seed);
        return (mixed >>> 11) * 0x1.0p-53;
    }

    private static long mix64(long value) {
        value = (value ^ (value >>> 30)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 27)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 31);
    }
}

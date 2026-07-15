package dev.world.level.levelgen;

/** Stable hashing helpers; deliberately independent from Java object identity and iteration order. */
final class SeedMixer {
    private SeedMixer() {
    }

    static long hash64(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return mix(hash);
    }

    static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }
}

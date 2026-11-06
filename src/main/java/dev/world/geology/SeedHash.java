package dev.world.geology;

/**
 * Stable hashing for the survey model. Everything random in a survey (natural bodies, noise,
 * positioning errors) is a pure function of the world seed, coordinates and the survey
 * configuration, never of time, iteration order or object identity.
 */
public final class SeedHash {
    private SeedHash() {}

    /** SplitMix64 finaliser. */
    public static long mix(long value) {
        value ^= value >>> 30;
        value *= 0xbf58476d1ce4e5b9L;
        value ^= value >>> 27;
        value *= 0x94d049bb133111ebL;
        return value ^ value >>> 31;
    }

    public static long combine(long first, long second) {
        return mix(first * 0x9E3779B97F4A7C15L + second);
    }

    public static long combine(long first, long second, long third) {
        return combine(combine(first, second), third);
    }

    public static long combine(long first, long second, long third, long fourth) {
        return combine(combine(first, second, third), fourth);
    }

    /** Uniform value in [0, 1). */
    public static double unit(long seed, long salt) {
        return (combine(seed, salt) >>> 11) * 0x1.0p-53;
    }

    /** Standard normal value (Box–Muller from two independent uniforms). */
    public static double gaussian(long seed, long salt) {
        double first = Math.max(1.0E-12D, unit(seed, salt));
        double second = unit(seed, salt ^ 0x5DEECE66DL);
        return Math.sqrt(-2.0D * Math.log(first)) * Math.cos(2.0D * Math.PI * second);
    }

    public static long hash(String value) {
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < value.length(); index++) {
            hash ^= value.charAt(index);
            hash *= 0x100000001b3L;
        }
        return mix(hash);
    }
}

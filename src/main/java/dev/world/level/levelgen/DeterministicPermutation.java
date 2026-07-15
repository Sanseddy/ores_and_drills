package dev.world.level.levelgen;

/** Small allocation-free permutation used to spread deterministic biome probes over a generation cell. */
final class DeterministicPermutation {
    private DeterministicPermutation() {
    }

    static int start(int size, long seed) {
        return size <= 1 ? 0 : Math.floorMod(seed, size);
    }

    static int coprimeStride(int size, long seed) {
        if (size <= 1) {
            return 0;
        }
        int stride = 1 + Math.floorMod(seed, size - 1);
        while (greatestCommonDivisor(stride, size) != 1) {
            stride = stride == size - 1 ? 1 : stride + 1;
        }
        return stride;
    }

    static int index(int size, int start, int stride, int offset) {
        if (size <= 1) {
            return 0;
        }
        return (int) Math.floorMod(start + (long) stride * offset, size);
    }

    private static int greatestCommonDivisor(int first, int second) {
        int a = Math.abs(first);
        int b = Math.abs(second);
        while (b != 0) {
            int remainder = a % b;
            a = b;
            b = remainder;
        }
        return a;
    }
}

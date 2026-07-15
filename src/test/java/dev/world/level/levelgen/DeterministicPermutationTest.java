package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DeterministicPermutationTest {
    @Test
    void visitsEveryCompositeCellExactlyOnce() {
        assertCompletePermutation(47 * 47, 0x1234_5678_9ABCL);
        assertCompletePermutation(16, -71L);
        assertCompletePermutation(9, 42L);
    }

    private static void assertCompletePermutation(int size, long seed) {
        int start = DeterministicPermutation.start(size, seed);
        int stride = DeterministicPermutation.coprimeStride(size, seed >>> 17);
        Set<Integer> visited = new HashSet<>();
        for (int index = 0; index < size; index++) {
            visited.add(DeterministicPermutation.index(size, start, stride, index));
        }
        assertEquals(size, visited.size());
    }
}

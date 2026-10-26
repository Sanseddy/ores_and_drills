package dev.world.block.entity.drill;

import java.util.ArrayList;
import java.util.List;

/** Pure, game-independent square-spiral ordering for drill mining columns. */
final class SpiralColumnOrder {
    record Offset(int x, int z) {}

    private SpiralColumnOrder() {}

    static List<Offset> between(int minInclusive, int maxExclusive) {
        int side = maxExclusive - minInclusive;
        if (side <= 0) return List.of();

        int total = side * side;
        List<Offset> result = new ArrayList<>(total);
        int x = minInclusive + (side - 1) / 2;
        int z = minInclusive + (side - 1) / 2;
        result.add(new Offset(x, z));

        int[][] directions = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        int direction = 0;
        int segmentLength = 1;
        while (result.size() < total) {
            for (int repeatedSegment = 0;
                    repeatedSegment < 2 && result.size() < total;
                    repeatedSegment++) {
                int[] step = directions[direction++ & 3];
                for (int distance = 0;
                        distance < segmentLength && result.size() < total;
                        distance++) {
                    x += step[0];
                    z += step[1];
                    if (x >= minInclusive
                            && x < maxExclusive
                            && z >= minInclusive
                            && z < maxExclusive) {
                        result.add(new Offset(x, z));
                    }
                }
            }
            segmentLength++;
        }
        return result;
    }
}

package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ForcedUraniumPlacementMathTest {
    @Test
    void overworldTraversalCoversEverySafeHeightWithoutAnUpperCap() {
        int minimumY = ForcedUraniumPlacementMath.minimumOriginY(-64, 5, 5);
        int maximumY = ForcedUraniumPlacementMath.maximumOriginY(320);

        assertEquals(-55, minimumY);
        assertEquals(319, maximumY);
        assertEquals(94, ForcedUraniumPlacementMath.quartCount(minimumY, maximumY));
    }

    @Test
    void cyclicTraversalVisitsEveryVerticalQuartExactlyOnce() {
        int count = 94;
        Set<Integer> visited = new HashSet<>();
        for (int offset = 0; offset < count; offset++) {
            visited.add(ForcedUraniumPlacementMath.cyclicIndex(73, offset, count));
        }

        assertEquals(count, visited.size());
        assertEquals(73, ForcedUraniumPlacementMath.cyclicIndex(73, 0, count));
        assertEquals(72, ForcedUraniumPlacementMath.cyclicIndex(73, count - 1, count));
    }

    @Test
    void dimensionsTooShortForTheProtectedDepthHaveNoCandidateHeight() {
        int minimumY = ForcedUraniumPlacementMath.minimumOriginY(0, 5, 5);
        int maximumY = ForcedUraniumPlacementMath.maximumOriginY(9);

        assertEquals(0, ForcedUraniumPlacementMath.quartCount(minimumY, maximumY));
    }
}

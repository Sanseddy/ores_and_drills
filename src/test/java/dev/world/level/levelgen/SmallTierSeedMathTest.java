package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SmallTierSeedMathTest {
    @Test
    void producesTheSameDecisionValueForTheSameSeed() {
        assertEquals(
                SmallTierSeedMath.unitDouble(0x1234_5678_9ABCDEFL),
                SmallTierSeedMath.unitDouble(0x1234_5678_9ABCDEFL)
        );
    }

    @Test
    void nearbySeedsProduceIndependentValuesInsideUnitInterval() {
        double first = SmallTierSeedMath.unitDouble(123_456_789L);
        double second = SmallTierSeedMath.unitDouble(123_456_790L);

        assertTrue(first >= 0.0D && first < 1.0D);
        assertTrue(second >= 0.0D && second < 1.0D);
        assertNotEquals(first, second);
    }
}

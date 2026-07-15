package dev.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ProgressMathTest {
    @Test
    void scaledProgressHandlesEmptyAndInvalidValues() {
        assertEquals(0, ProgressMath.scaled(0, 100, 20));
        assertEquals(0, ProgressMath.scaled(5, 0, 20));
        assertEquals(0, ProgressMath.scaled(5, 100, 0));
    }

    @Test
    void scaledProgressIsVisibleAndBounded() {
        assertEquals(1, ProgressMath.scaled(1, 100, 20));
        assertEquals(10, ProgressMath.scaled(50, 100, 20));
        assertEquals(20, ProgressMath.scaled(200, 100, 20));
    }

    @Test
    void percentagesAreBoundedWithoutIntegerOverflow() {
        assertEquals(0, ProgressMath.percent(0, 100));
        assertEquals(50, ProgressMath.percent(50, 100));
        assertEquals(100, ProgressMath.percent(Integer.MAX_VALUE, 1));
    }
}

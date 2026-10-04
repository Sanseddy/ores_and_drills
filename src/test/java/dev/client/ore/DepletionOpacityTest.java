package dev.client.ore;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DepletionOpacityTest {
    @Test
    void opacityFallsSmoothlyAsBaseTextureGetsBrighter() {
        assertEquals(150, DepletionOpacity.vertexAlpha(0));
        assertEquals(150, DepletionOpacity.vertexAlpha(64));
        assertEquals(144, DepletionOpacity.vertexAlpha(80));
        assertEquals(125, DepletionOpacity.vertexAlpha(128));
        assertEquals(107, DepletionOpacity.vertexAlpha(192));
        assertEquals(90, DepletionOpacity.vertexAlpha(255));

        int previous = DepletionOpacity.vertexAlpha(0);
        for (int brightness = 1; brightness <= 255; brightness++) {
            int current = DepletionOpacity.vertexAlpha(brightness);
            assertTrue(current <= previous);
            previous = current;
        }
    }

    @Test
    void hostRockAlwaysShowsThrough() {
        for (int brightness = 0; brightness <= 255; brightness++) {
            assertTrue(DepletionOpacity.vertexAlpha(brightness) < 255);
        }
    }

    @Test
    void brightnessOutsideTextureRangeIsClamped() {
        assertEquals(150, DepletionOpacity.vertexAlpha(-1));
        assertEquals(90, DepletionOpacity.vertexAlpha(256));
    }
}

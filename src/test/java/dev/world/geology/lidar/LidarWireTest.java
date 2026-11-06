package dev.world.geology.lidar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class LidarWireTest {
    @Test
    void positionsRoundTripWithinAnEighthOfABlock() {
        for (float relative = 0.0F; relative < LidarMap.BUCKET; relative += 0.37F) {
            float back = LidarWire.position(LidarWire.offset(relative));
            assertEquals(relative, back, 0.0625F);
        }
        assertEquals(0, LidarWire.offset(-3.0F));
        assertEquals(255, LidarWire.offset(LidarMap.BUCKET + 5.0F));
    }

    @Test
    void spreadsRoundTripOnTheirScale() {
        assertEquals(1.25F, LidarWire.spread(LidarWire.spreadCode(1.3F)), 0.125F);
        assertEquals(40.0F, LidarWire.spread(LidarWire.spreadCode(40.0F)), 1.0F);
        assertEquals(286.0F, LidarWire.spread(LidarWire.spreadCode(10_000.0F)), 0.0F);
    }

    @Test
    void spreadCodesAreMonotonic() {
        int previous = -1;
        for (float spread = 0.0F; spread < 280.0F; spread += 0.25F) {
            int code = LidarWire.spreadCode(spread);
            assertTrue(code >= previous);
            previous = code;
        }
    }
}

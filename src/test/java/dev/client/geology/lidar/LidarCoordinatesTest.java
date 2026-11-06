package dev.client.geology.lidar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.world.geology.lidar.LidarObject;
import java.util.List;
import org.junit.jupiter.api.Test;

class LidarCoordinatesTest {
    private static LidarObject object(
            LidarObject.Kind kind, float y, float sizeY, float error, float depthError) {
        return new LidarObject(
                1,
                kind,
                -10.4F,
                y,
                33.9F,
                4,
                sizeY,
                4,
                0,
                error,
                depthError,
                0,
                1,
                -1,
                0,
                -1,
                0,
                0,
                0,
                0,
                1,
                0L,
                List.of());
    }

    @Test
    void theClipboardFormatIsFixed() {
        LidarCoordinates at = new LidarCoordinates(-11, 40, 33, false, 0, 0);
        assertEquals("X: -11, Y: 40, Z: 33", at.clipboard());
    }

    @Test
    void aBoreholeGivesTheFirstOreBlockExactly() {
        // An interval from Y 10 to Y 14: centre 12.5, five blocks thick.
        LidarCoordinates at =
                LidarCoordinates.of(object(LidarObject.Kind.CORE_ORE, 12.5F, 5.0F, 0, 0), 0.0D);
        assertEquals(14, at.y());
        assertEquals(-11, at.x());
        assertFalse(at.approximate());
    }

    @Test
    void anUnresolvedDepthIsApproximateAndSaysSo() {
        LidarCoordinates at =
                LidarCoordinates.of(
                        object(LidarObject.Kind.TARGET, Float.NaN, Float.NaN, 9.6F, Float.NaN),
                        -20.0D);
        assertTrue(at.approximate());
        assertEquals(-20, at.y());
        assertEquals(10, at.error());
        assertEquals(-1, at.depthError());
    }

    @Test
    void surveyErrorsMakeCoordinatesApproximate() {
        assertTrue(
                LidarCoordinates.of(object(LidarObject.Kind.TARGET, -30, 4, 6, 3), 0.0D)
                        .approximate());
        assertFalse(
                LidarCoordinates.of(object(LidarObject.Kind.CAVE, -30, 4, 0.5F, 0.5F), 0.0D)
                        .approximate());
    }
}

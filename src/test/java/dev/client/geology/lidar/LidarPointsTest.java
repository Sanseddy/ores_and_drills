package dev.client.geology.lidar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.world.geology.lidar.LidarObject;
import dev.world.geology.lidar.LidarSample;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LidarPointsTest {
    private static LidarSample anomaly(float confidence, int flags) {
        return new LidarSample(
                LidarSample.Kind.ANOMALY, 100, -20, 40, 8, 4, confidence, 0, 0, flags);
    }

    private static List<float[]> points(LidarSample sample, float quality) {
        List<float[]> points = new ArrayList<>();
        LidarPoints.sample(
                sample,
                0.0F,
                quality,
                0,
                (x, y, z, argb, size, born) -> points.add(new float[] {x, y, z, argb}));
        return points;
    }

    @Test
    void theSameSampleAlwaysGivesTheSamePoints() {
        List<float[]> first = points(anomaly(0.7F, 0), 1.0F);
        List<float[]> second = points(anomaly(0.7F, 0), 1.0F);
        assertEquals(first.size(), second.size());
        for (int i = 0; i < first.size(); i++) {
            for (int c = 0; c < 4; c++) {
                assertEquals(first.get(i)[c], second.get(i)[c]);
            }
        }
    }

    @Test
    void certainReadingsAreDenserThanUncertainOnes() {
        assertTrue(points(anomaly(0.95F, 0), 1.0F).size() > points(anomaly(0.1F, 0), 1.0F).size());
        assertTrue(points(anomaly(0.5F, 0), 1.6F).size() > points(anomaly(0.5F, 0), 0.5F).size());
    }

    @Test
    void pointsStayWithinTheSampleSpread() {
        for (float[] p : points(anomaly(0.9F, 0), 1.6F)) {
            assertTrue(Math.abs(p[0] - 100) <= 8.0F);
            assertTrue(Math.abs(p[1] + 20) <= 4.0F);
            assertTrue(Math.abs(p[2] - 40) <= 8.0F);
        }
    }

    @Test
    void polarityColoursUnattachedReadings() {
        assertEquals(
                LidarPoints.NEGATIVE_COLOR,
                LidarPoints.color(anomaly(0.5F, LidarSample.FLAG_NEGATIVE), 0));
        assertEquals(LidarPoints.POSITIVE_COLOR, LidarPoints.color(anomaly(0.5F, 0), 0));
        assertEquals(0x123456, LidarPoints.color(anomaly(0.5F, 0), 0x123456));
    }

    @Test
    void aTargetWithoutDepthFillsTheColumn() {
        LidarObject column =
                new LidarObject(
                        1,
                        LidarObject.Kind.TARGET,
                        0,
                        Float.NaN,
                        0,
                        20,
                        Float.NaN,
                        20,
                        0,
                        5,
                        Float.NaN,
                        6,
                        0.8F,
                        0,
                        1,
                        -1,
                        0,
                        0,
                        0,
                        0,
                        1,
                        0L,
                        List.of());
        float[] range = {Float.MAX_VALUE, -Float.MAX_VALUE};
        LidarPoints.body(
                column,
                60.0F,
                -60.0F,
                1.0F,
                0xFFFFFF,
                0.0F,
                (x, y, z, argb, size, born) -> {
                    range[0] = Math.min(range[0], y);
                    range[1] = Math.max(range[1], y);
                });
        assertTrue(range[0] < -40.0F && range[1] > 40.0F);
    }
}

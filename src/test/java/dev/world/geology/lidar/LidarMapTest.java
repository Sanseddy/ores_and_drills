package dev.world.geology.lidar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LidarMapTest {
    private static final long DAY = 24_000L;

    private static LidarMap.Keyed anomaly(long key, float x, float intensity) {
        LidarSample sample =
                new LidarSample(
                        LidarSample.Kind.ANOMALY, x, 10.0F, 5.0F, 4.0F, 4.0F, 0.5F, 0, 0, 0);
        return new LidarMap.Keyed(key, sample, intensity, 8.0F);
    }

    @Test
    void bucketKeysRoundTripNegativeCoordinates() {
        long key = LidarMap.bucketKey(-1.0D, -65.0D, 100.0D);
        assertEquals(-1, LidarMap.bucketX(key));
        assertEquals(-3, LidarMap.bucketY(key));
        assertEquals(3, LidarMap.bucketZ(key));
    }

    @Test
    void independentSurveysAverageACellInsteadOfAddingPoints() {
        LidarMap map = new LidarMap(1_000, 16);
        long bucket = LidarMap.bucketKey(4, 10, 5);
        map.mergeAnomalies(bucket, List.of(anomaly(1L, 4, 8.0F)), s -> true, 8.0F, 11L, 0L, DAY);
        map.mergeAnomalies(bucket, List.of(anomaly(1L, 4, 4.0F)), s -> true, 8.0F, 22L, 0L, DAY);
        assertEquals(1, map.samples(bucket).size());
        // Mean of 8 and 4 with equal weights: confidence of 6.
        assertEquals(
                LidarMap.confidence(6.0D), map.samples(bucket).getFirst().confidence(), 1.0E-6D);
    }

    @Test
    void anIdenticalRepeatReplacesRatherThanAverages() {
        LidarMap map = new LidarMap(1_000, 16);
        long bucket = LidarMap.bucketKey(4, 10, 5);
        map.mergeAnomalies(bucket, List.of(anomaly(1L, 4, 8.0F)), s -> true, 8.0F, 11L, 0L, DAY);
        map.mergeAnomalies(bucket, List.of(anomaly(1L, 4, 3.0F)), s -> true, 8.0F, 11L, 0L, DAY);
        assertEquals(
                LidarMap.confidence(3.0D), map.samples(bucket).getFirst().confidence(), 1.0E-6D);
    }

    @Test
    void coveredCellsThatAreNoLongerSeenFade() {
        LidarMap map = new LidarMap(1_000, 16);
        long bucket = LidarMap.bucketKey(4, 10, 5);
        map.mergeAnomalies(bucket, List.of(anomaly(1L, 4, 5.0F)), s -> true, 8.0F, 11L, 0L, DAY);
        map.mergeAnomalies(bucket, List.of(), s -> true, 8.0F, 22L, 0L, DAY);
        // 5 averaged with a reading of nothing is 2.5: still kept, but fainter.
        assertEquals(1, map.samples(bucket).size());
        map.mergeAnomalies(bucket, List.of(), s -> true, 8.0F, 33L, 0L, DAY);
        assertTrue(map.samples(bucket).isEmpty());
    }

    @Test
    void cellsOutsideTheNewSurveyAreLeftAlone() {
        LidarMap map = new LidarMap(1_000, 16);
        long bucket = LidarMap.bucketKey(4, 10, 5);
        map.mergeAnomalies(bucket, List.of(anomaly(1L, 4, 5.0F)), s -> true, 8.0F, 11L, 0L, DAY);
        map.mergeAnomalies(bucket, List.of(), s -> false, 8.0F, 22L, 0L, DAY);
        map.mergeAnomalies(bucket, List.of(), s -> false, 8.0F, 33L, 0L, DAY);
        assertEquals(1, map.samples(bucket).size());
    }

    @Test
    void olderReadingsWeighLess() {
        assertEquals(1.0D, LidarMap.decay(0L, DAY), 0.0D);
        assertEquals(0.5D, LidarMap.decay(DAY, DAY), 1.0E-12D);
        assertTrue(LidarMap.decay(3 * DAY, DAY) < LidarMap.decay(DAY, DAY));
    }

    @Test
    void aBetterCurrentCaveMapIsNotReplacedByAWeakerOne() {
        LidarMap map = new LidarMap(1_000, 16);
        long bucket = LidarMap.bucketKey(1, 1, 1);
        LidarSample wall =
                new LidarSample(LidarSample.Kind.CAVE, 1, 1, 1, 0.5F, 0.5F, 1, 0x5FD8FF, 0, 0);
        assertTrue(map.replaceCaves(bucket, List.of(wall, wall), 3, 0L, DAY));
        assertFalse(map.replaceCaves(bucket, List.of(wall), 1, 10L, DAY));
        assertEquals(2, map.samples(bucket).size());
        // Once outdated, any new map replaces it.
        assertTrue(map.replaceCaves(bucket, List.of(wall), 1, 2 * DAY, DAY));
        assertEquals(1, map.samples(bucket).size());
    }

    @Test
    void changesAreSentOldestFirstAndOnlyOnce() {
        LidarMap map = new LidarMap(1_000, 16);
        long a = LidarMap.bucketKey(1, 1, 1);
        long b = LidarMap.bucketKey(100, 1, 1);
        map.mergeAnomalies(a, List.of(anomaly(1L, 1, 6.0F)), s -> true, 8.0F, 1L, 0L, DAY);
        map.mergeAnomalies(b, List.of(anomaly(2L, 100, 6.0F)), s -> true, 8.0F, 1L, 0L, DAY);
        List<LidarMap.BucketView> all = map.bucketsAfter(0, 100);
        assertEquals(List.of(a, b), all.stream().map(LidarMap.BucketView::key).toList());
        int seen = all.getLast().revision();
        assertTrue(map.bucketsAfter(seen, 100).isEmpty());
        map.mergeAnomalies(a, List.of(anomaly(1L, 1, 9.0F)), s -> true, 8.0F, 2L, 0L, DAY);
        assertEquals(
                List.of(a),
                map.bucketsAfter(seen, 100).stream().map(LidarMap.BucketView::key).toList());
    }

    @Test
    void theMapKeepsItsBudgetByDroppingFarBuckets() {
        LidarMap map = new LidarMap(3, 16);
        for (int i = 0; i < 5; i++) {
            long bucket = LidarMap.bucketKey(i * 64, 1, 1);
            map.mergeAnomalies(
                    bucket, List.of(anomaly(i, i * 64, 6.0F)), s -> true, 8.0F, 1L, 0L, DAY);
        }
        assertTrue(map.sampleCount() <= 3);
    }

    @Test
    void clearingStartsANewEpoch() {
        LidarMap map = new LidarMap(1_000, 16);
        long epoch = map.epoch();
        map.clear();
        assertNotEquals(epoch, map.epoch());
        assertEquals(0, map.revision());
    }
}

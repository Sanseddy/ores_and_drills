package dev.world.geology.lidar;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.world.geology.SurveyTarget;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreTraits;
import java.util.List;
import org.junit.jupiter.api.Test;

class LidarSweepTest {
    private static LidarSweep.Context context(long config) {
        return new LidarSweep.Context(
                0, 0, 64, -60, 200, 0, 2, 0, 8.0F, config, 100L, 120_000L, c -> 0.3D);
    }

    private static LidarMap.Keyed cell(long key, float x) {
        return cell(key, x, 0);
    }

    private static LidarMap.Keyed cell(long key, float x, int target) {
        return new LidarMap.Keyed(
                key,
                new LidarSample(LidarSample.Kind.ANOMALY, x, 0, 0, 4, 4, 0.9F, 0, target, 0),
                8.0F,
                8.0F);
    }

    private static LidarObject target(float x, float error) {
        return new LidarObject(
                0,
                LidarObject.Kind.TARGET,
                x,
                0,
                0,
                20,
                4,
                20,
                0,
                error,
                4,
                8,
                0.9F,
                1,
                2,
                -1,
                0,
                0,
                0,
                0,
                1,
                100L,
                List.of(
                        new SurveyTarget.Candidate(
                                OreIdentification.Source.ORE,
                                OreTraits.Category.METALLIC,
                                "minecraft:iron_ore",
                                0.7D)));
    }

    @Test
    void nothingBeyondTheFrontIsRevealed() {
        LidarMap map = new LidarMap(10_000, 64);
        LidarSweep sweep =
                new LidarSweep(
                        map,
                        context(1L),
                        List.of(cell(1L, 10), cell(2L, 150, 1)),
                        List.of(target(150, 10)),
                        List.of(),
                        List.of());
        assertFalse(sweep.advance(map, 50, 0));
        assertEquals(1, map.sampleCount());
        assertTrue(map.objects().isEmpty());
        assertTrue(sweep.advance(map, Double.MAX_VALUE, Double.MAX_VALUE));
        assertEquals(2, map.sampleCount());
        assertEquals(1, map.objects().size());
        assertEquals(1, map.coverage().size());
        // The far sample belongs to the target and now carries its id.
        int id = map.objects().getFirst().id();
        long far = LidarMap.bucketKey(150, 0, 0);
        assertEquals(id, map.samples(far).getFirst().object());
    }

    @Test
    void aTargetAppearsWithTheFirstOfItsSamples() {
        LidarMap map = new LidarMap(10_000, 64);
        LidarSweep sweep =
                new LidarSweep(
                        map,
                        context(1L),
                        List.of(cell(1L, 10, 1)),
                        List.of(target(150, 10)),
                        List.of(),
                        List.of());
        sweep.advance(map, 50, 0);
        assertEquals(1, map.objects().size());
    }

    @Test
    void sweepingTheSameSurveyAgainChangesNothing() {
        LidarMap map = new LidarMap(10_000, 64);
        for (int run = 0; run < 2; run++) {
            new LidarSweep(
                            map,
                            context(1L),
                            List.of(cell(1L, 10)),
                            List.of(target(10, 10)),
                            List.of(),
                            List.of())
                    .advance(map, Double.MAX_VALUE, Double.MAX_VALUE);
        }
        assertEquals(1, map.sampleCount());
        assertEquals(1, map.objects().size());
        assertEquals(1, map.objects().getFirst().surveys());
    }

    @Test
    void anIndependentSurveyRefinesTheSameTarget() {
        LidarMap map = new LidarMap(10_000, 64);
        new LidarSweep(map, context(1L), List.of(), List.of(target(10, 10)), List.of(), List.of())
                .advance(map, Double.MAX_VALUE, Double.MAX_VALUE);
        new LidarSweep(map, context(2L), List.of(), List.of(target(14, 10)), List.of(), List.of())
                .advance(map, Double.MAX_VALUE, Double.MAX_VALUE);
        assertEquals(1, map.objects().size());
        LidarObject refined = map.objects().getFirst();
        assertEquals(2, refined.surveys());
        assertEquals(12.0F, refined.x(), 1.0E-3F);
        assertTrue(refined.positionError() < 10.0F);
        assertTrue(refined.candidates().getFirst().probability() > 0.7D);
    }

    @Test
    void aTargetTheSameSurveyNoLongerSeesIsRemoved() {
        LidarMap map = new LidarMap(10_000, 64);
        new LidarSweep(map, context(1L), List.of(), List.of(target(10, 10)), List.of(), List.of())
                .advance(map, Double.MAX_VALUE, Double.MAX_VALUE);
        new LidarSweep(map, context(1L), List.of(), List.of(), List.of(), List.of())
                .advance(map, Double.MAX_VALUE, Double.MAX_VALUE);
        assertTrue(map.objects().isEmpty());
    }
}

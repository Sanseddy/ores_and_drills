package dev.world.block.entity.drill;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MiningFrontTest {
    private static final List<String> TARGETS = List.of("a", "b", "c", "d", "e");

    @Test
    void cyclesTakeTurnsAcrossTheFace() {
        assertEquals("a", MiningFront.order(TARGETS, 3, 0).getFirst());
        assertEquals("b", MiningFront.order(TARGETS, 3, 1).getFirst());
        assertEquals("c", MiningFront.order(TARGETS, 3, 2).getFirst());
        assertEquals("a", MiningFront.order(TARGETS, 3, 3).getFirst());
    }

    @Test
    void restOfTheOrderFollowsTheFaceAsFallback() {
        assertEquals(List.of("c", "a", "b", "d", "e"), MiningFront.order(TARGETS, 3, 2));
    }

    @Test
    void faceWearsDownEvenly() {
        Map<String, Integer> mined = new HashMap<>();
        for (int turn = 0; turn < 300; turn++) {
            mined.merge(MiningFront.order(TARGETS, 3, turn).getFirst(), 1, Integer::sum);
        }

        assertEquals(Map.of("a", 100, "b", 100, "c", 100), mined);
    }

    @Test
    void faceLargerThanTheTargetsUsesAllOfThem() {
        assertEquals(List.of("b", "a"), MiningFront.order(List.of("a", "b"), 9, 1));
        assertEquals(List.of(), MiningFront.order(List.<String>of(), 9, 4));
    }

    @Test
    void faceIsEveryTargetOfTheLowestRemainingStage() {
        List<Integer> stages = List.of(2, 2, 2, 3, 3, 4);

        assertEquals(3, MiningFront.currentStageSize(stages, Integer::intValue));
        assertEquals(1, MiningFront.currentStageSize(List.of(0, 1, 1), Integer::intValue));
        assertEquals(0, MiningFront.currentStageSize(List.<Integer>of(), Integer::intValue));
    }

    @Test
    void singleBlockFaceKeepsTheOrder() {
        assertEquals(TARGETS, MiningFront.order(TARGETS, 1, 7));
    }
}

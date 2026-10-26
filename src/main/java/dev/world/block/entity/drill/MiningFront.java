package dev.world.block.entity.drill;

import java.util.ArrayList;
import java.util.List;
import java.util.function.ToIntFunction;

/**
 * The working face of a drill: every target of the current funnel stage, which the bit grinds at together.
 * Successive cycles take turns between them instead of draining one deposit block before touching the next, so the
 * face wears down evenly. The funnel only grows to its next stage once the current one is worked out.
 */
public final class MiningFront {
    private MiningFront() {
    }

    /**
     * How many leading targets share the first target's funnel stage. Targets come ordered by stage, and earlier
     * stages are already worked out, so this is the face the drill works now.
     */
    public static <T> int currentStageSize(List<T> targets, ToIntFunction<? super T> stage) {
        if (targets.isEmpty()) {
            return 0;
        }

        int current = stage.applyAsInt(targets.getFirst());
        int size = 1;
        while (size < targets.size() && stage.applyAsInt(targets.get(size)) == current) {
            size++;
        }
        return size;
    }

    /**
     * Targets in the order a cycle tries them: the face of {@code faceSize} targets rotated to start at
     * {@code turn}, then the remaining targets as fallbacks for when no face block can be mined right now.
     */
    public static <T> List<T> order(List<T> targets, int faceSize, int turn) {
        int face = Math.min(Math.max(1, faceSize), targets.size());
        if (face <= 1) {
            return targets;
        }

        int start = Math.floorMod(turn, face);
        List<T> ordered = new ArrayList<>(targets.size());
        for (int i = 0; i < face; i++) {
            ordered.add(targets.get((start + i) % face));
        }
        ordered.addAll(targets.subList(face, targets.size()));
        return ordered;
    }
}

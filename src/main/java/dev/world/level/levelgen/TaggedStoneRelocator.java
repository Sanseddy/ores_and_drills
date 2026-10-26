package dev.world.level.levelgen;

import java.util.function.Predicate;
import net.minecraft.core.BlockPos;

/** Exact Manhattan-shell host search shared by generation and non-loading locate prediction. */
final class TaggedStoneRelocator {
    private TaggedStoneRelocator() {}

    static BlockPos findNearest(
            BlockPos origin,
            int horizontalRadius,
            int verticalRadius,
            int maximumChecks,
            int minimumY,
            int maximumY,
            Predicate<BlockPos> positionAllowed,
            Predicate<BlockPos> usableStone) {
        Search search =
                new Search(
                        origin,
                        horizontalRadius,
                        verticalRadius,
                        maximumChecks,
                        minimumY,
                        maximumY,
                        positionAllowed,
                        usableStone);
        while (!search.isDone()) search.advance(Long.MAX_VALUE, Integer.MAX_VALUE);
        return search.result();
    }

    /** Resumable exact traversal of the same Manhattan shells used by generation. */
    static final class Search {
        private final BlockPos origin;
        private final int horizontalRadius;
        private final int verticalRadius;
        private final int maximumChecks;
        private final int minimumY;
        private final int maximumY;
        private final Predicate<BlockPos> positionAllowed;
        private final Predicate<BlockPos> usableStone;
        private final BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        private boolean originPending = true;
        private boolean negativeZPending;
        private boolean done;
        private int distance = 1;
        private int dy;
        private int dx;
        private int currentDz;
        private int checks;
        private BlockPos result;

        Search(
                BlockPos origin,
                int horizontalRadius,
                int verticalRadius,
                int maximumChecks,
                int minimumY,
                int maximumY,
                Predicate<BlockPos> positionAllowed,
                Predicate<BlockPos> usableStone) {
            this.origin = origin.immutable();
            this.horizontalRadius = horizontalRadius;
            this.verticalRadius = verticalRadius;
            this.maximumChecks = maximumChecks;
            this.minimumY = minimumY;
            this.maximumY = maximumY;
            this.positionAllowed = positionAllowed;
            this.usableStone = usableStone;
            resetDistance();
        }

        void advance(long deadlineNanos, int maximumOperations) {
            int operations = 0;
            while (!done && operations++ < maximumOperations && System.nanoTime() < deadlineNanos) {
                boolean checkingOrigin = originPending;
                BlockPos pos = nextPosition();
                if (pos == null) {
                    done = true;
                    return;
                }
                if (pos.getY() < minimumY || pos.getY() >= maximumY || !positionAllowed.test(pos))
                    continue;
                if (!checkingOrigin && checks >= maximumChecks) {
                    done = true;
                    return;
                }
                if (!checkingOrigin) checks++;
                if (usableStone.test(pos)) {
                    result = pos.immutable();
                    done = true;
                }
            }
        }

        boolean isDone() {
            return done;
        }

        BlockPos result() {
            if (!done) throw new IllegalStateException("Tagged-stone search has not completed");
            return result;
        }

        private BlockPos nextPosition() {
            if (originPending) {
                originPending = false;
                return origin;
            }
            while (distance <= horizontalRadius + verticalRadius) {
                if (negativeZPending) {
                    negativeZPending = false;
                    mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() - currentDz);
                    advanceDx();
                    return mutable;
                }
                int horizontalDistance = distance - Math.abs(dy);
                if (horizontalDistance > horizontalRadius) {
                    advanceDy();
                    continue;
                }
                int maxDx = Math.min(horizontalRadius, horizontalDistance);
                if (dx > maxDx) {
                    advanceDy();
                    continue;
                }
                currentDz = horizontalDistance - Math.abs(dx);
                if (currentDz > horizontalRadius) {
                    advanceDx();
                    continue;
                }
                mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + currentDz);
                if (currentDz != 0) negativeZPending = true;
                else advanceDx();
                return mutable;
            }
            return null;
        }

        private void advanceDx() {
            dx++;
        }

        private void advanceDy() {
            dy++;
            int maxDy = Math.min(verticalRadius, distance);
            if (dy > maxDy) {
                distance++;
                resetDistance();
                return;
            }
            resetDx();
        }

        private void resetDistance() {
            if (distance > horizontalRadius + verticalRadius) return;
            dy = -Math.min(verticalRadius, distance);
            resetDx();
        }

        private void resetDx() {
            int horizontalDistance = distance - Math.abs(dy);
            dx = -Math.min(horizontalRadius, Math.max(0, horizontalDistance));
        }
    }
}

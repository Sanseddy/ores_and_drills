package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;

import java.util.function.Predicate;

/** Exact Manhattan-shell host search shared by generation and non-loading locate prediction. */
final class TaggedStoneRelocator {
    private TaggedStoneRelocator() {
    }

    static BlockPos findNearest(
            BlockPos origin,
            int horizontalRadius,
            int verticalRadius,
            int maximumChecks,
            int minimumY,
            int maximumY,
            Predicate<BlockPos> positionAllowed,
            Predicate<BlockPos> usableStone
    ) {
        if (isUsable(origin, minimumY, maximumY, positionAllowed, usableStone)) {
            return origin;
        }

        int maximumDistance = horizontalRadius + verticalRadius;
        int checks = 0;
        BlockPos.MutableBlockPos mutable = new BlockPos.MutableBlockPos();
        for (int distance = 1; distance <= maximumDistance; distance++) {
            int minDy = -Math.min(verticalRadius, distance);
            int maxDy = Math.min(verticalRadius, distance);
            for (int dy = minDy; dy <= maxDy; dy++) {
                int horizontalDistance = distance - Math.abs(dy);
                if (horizontalDistance > horizontalRadius) {
                    continue;
                }
                int minDx = -Math.min(horizontalRadius, horizontalDistance);
                int maxDx = Math.min(horizontalRadius, horizontalDistance);
                for (int dx = minDx; dx <= maxDx; dx++) {
                    int dz = horizontalDistance - Math.abs(dx);
                    if (dz > horizontalRadius) {
                        continue;
                    }

                    mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    SearchResult result = check(
                            mutable, checks, maximumChecks, minimumY, maximumY,
                            positionAllowed, usableStone
                    );
                    checks = result.checks();
                    if (result.found()) {
                        return mutable.immutable();
                    }
                    if (result.exhausted()) {
                        return null;
                    }
                    if (dz != 0) {
                        mutable.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() - dz);
                        result = check(
                                mutable, checks, maximumChecks, minimumY, maximumY,
                                positionAllowed, usableStone
                        );
                        checks = result.checks();
                        if (result.found()) {
                            return mutable.immutable();
                        }
                        if (result.exhausted()) {
                            return null;
                        }
                    }
                }
            }
        }
        return null;
    }

    private static SearchResult check(
            BlockPos pos,
            int checks,
            int maximumChecks,
            int minimumY,
            int maximumY,
            Predicate<BlockPos> positionAllowed,
            Predicate<BlockPos> usableStone
    ) {
        if (pos.getY() < minimumY || pos.getY() >= maximumY || !positionAllowed.test(pos)) {
            return new SearchResult(checks, false, false);
        }
        if (checks >= maximumChecks) {
            return new SearchResult(checks, false, true);
        }
        return new SearchResult(checks + 1, usableStone.test(pos), false);
    }

    private static boolean isUsable(
            BlockPos pos,
            int minimumY,
            int maximumY,
            Predicate<BlockPos> positionAllowed,
            Predicate<BlockPos> usableStone
    ) {
        return pos.getY() >= minimumY && pos.getY() < maximumY
                && positionAllowed.test(pos) && usableStone.test(pos);
    }

    private record SearchResult(int checks, boolean found, boolean exhausted) {
    }
}

package dev.world.level.levelgen;

/** Pure placement-priority rules shared by runtime branching and dependency-free tests. */
final class SmallDepositPlacementPriority {
    enum Placement {
        CAVE_WALL,
        UNDERGROUND,
        CANCELLED
    }

    private SmallDepositPlacementPriority() {
    }

    static boolean shouldTryUnderground(boolean usableWallShape) {
        return !usableWallShape;
    }

    static Placement resolve(boolean usableWallShape, boolean usableUndergroundShape) {
        if (usableWallShape) {
            return Placement.CAVE_WALL;
        }
        return usableUndergroundShape ? Placement.UNDERGROUND : Placement.CANCELLED;
    }
}

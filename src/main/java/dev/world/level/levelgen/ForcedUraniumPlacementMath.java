package dev.world.level.levelgen;

/** Pure full-height traversal math for guaranteed Toxic Caves uranium placement. */
final class ForcedUraniumPlacementMath {
    private ForcedUraniumPlacementMath() {
    }

    static int minimumOriginY(int minimumBuildHeight, int bedrockClearance, int maximumDepositDepth) {
        return minimumBuildHeight + Math.max(0, bedrockClearance) + Math.max(1, maximumDepositDepth) - 1;
    }

    static int maximumOriginY(int maximumBuildHeight) {
        return maximumBuildHeight - 1;
    }

    static int quartCount(int minimumY, int maximumY) {
        if (minimumY > maximumY) {
            return 0;
        }
        return Math.floorDiv(maximumY, 4) - Math.floorDiv(minimumY, 4) + 1;
    }

    static int cyclicIndex(int firstIndex, int offset, int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("count must be positive");
        }
        return Math.floorMod(firstIndex + offset, count);
    }
}

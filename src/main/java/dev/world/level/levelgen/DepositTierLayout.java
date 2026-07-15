package dev.world.level.levelgen;

/** Complete generation and locate layout for one independent deposit tier. */
public record DepositTierLayout(
        int cellSizeBlocks,
        int maximumRadius,
        int candidatesPerCell,
        int minimumSamples,
        int maximumSamples,
        double requiredStoneCoverage
) {
    public DepositTierLayout {
        if (cellSizeBlocks < 16 || cellSizeBlocks % 16 != 0) {
            throw new IllegalArgumentException("Deposit cells must be positive whole chunks");
        }
        if (maximumRadius < 1 || candidatesPerCell < 1) {
            throw new IllegalArgumentException("Deposit radius and candidate count must be positive");
        }
        if (minimumSamples < 1 || maximumSamples < minimumSamples) {
            throw new IllegalArgumentException("Invalid deposit sample range");
        }
        if (!(requiredStoneCoverage > 0.0D && requiredStoneCoverage <= 1.0D)) {
            throw new IllegalArgumentException("Stone coverage must be in (0, 1]");
        }
    }

    public int cellSizeChunks() {
        return cellSizeBlocks / 16;
    }
}

package dev.world.level.levelgen;

/** Pure confirmation predicates shared with unit tests without requiring a Minecraft test runtime. */
final class DepositConfirmationMath {
    private DepositConfirmationMath() {
    }

    static boolean meetsThreshold(
            int placedBlocks,
            int minimumPlacedBlocks,
            int occupiedSections,
            boolean wallPreferred,
            boolean exposedToCave
    ) {
        return placedBlocks >= minimumPlacedBlocks
                && occupiedSections >= 1
                && (!wallPreferred || exposedToCave);
    }
}

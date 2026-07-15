package dev.world.level.levelgen;

import dev.config.OreDepositConfig;

/** Tier layouts shared by generation, prediction, validation and {@code /locate}. */
public final class DepositLayouts {
    public static final long TINY_SALT = 0x54494E595F4F5245L;
    public static final long SMALL_SALT = 0x534D414C4C4F5245L;
    public static final long MEDIUM_SALT = 0x4D454449554D4F52L;
    public static final long LARGE_SALT = 0x4C415247455F4F52L;

    private DepositLayouts() {
    }

    public static DepositTierLayout forTier(OreDepositTier tier) {
        int largeChunks = configuredLargeSpacingChunks();
        int mediumChunks = Math.max(4, (largeChunks + 1) / 2);
        return switch (tier) {
            case TINY -> new DepositTierLayout(16, 8, 1, 16, 32, 0.50D);
            case SMALL -> new DepositTierLayout(32, 14, 1, 32, 56, 0.55D);
            case MEDIUM -> new DepositTierLayout(mediumChunks * 16, 24, 1, 56, 96, 0.60D);
            case LARGE -> new DepositTierLayout(largeChunks * 16, 32, 1, 96, 160, 0.65D);
        };
    }

    public static long tierSalt(OreDepositTier tier) {
        return switch (tier) {
            case TINY -> TINY_SALT;
            case SMALL -> SMALL_SALT;
            case MEDIUM -> MEDIUM_SALT;
            case LARGE -> LARGE_SALT;
        };
    }

    public static long tierSalt(int tier) {
        return tierSalt(OreDepositTier.fromIndex(tier)
                .orElseThrow(() -> new IllegalArgumentException("Unknown deposit tier index: " + tier)));
    }

    public static long anchorChunkKey(OreDepositTier tier, int cellX, int cellZ) {
        int sizeChunks = forTier(tier).cellSizeChunks();
        return packChunk(
                cellX * sizeChunks + sizeChunks / 2,
                cellZ * sizeChunks + sizeChunks / 2
        );
    }

    public static boolean isAnchorChunk(OreDepositTier tier, int chunkX, int chunkZ) {
        DepositTierLayout layout = forTier(tier);
        int cellX = Math.floorDiv(chunkX, layout.cellSizeChunks());
        int cellZ = Math.floorDiv(chunkZ, layout.cellSizeChunks());
        long anchor = anchorChunkKey(tier, cellX, cellZ);
        return chunkX == chunkX(anchor) && chunkZ == chunkZ(anchor);
    }

    public static int chunkX(long packedChunk) {
        return (int) (packedChunk >> 32);
    }

    public static int chunkZ(long packedChunk) {
        return (int) packedChunk;
    }

    private static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    private static int configuredLargeSpacingChunks() {
        int spacingBlocks;
        try {
            spacingBlocks = OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING.get();
        } catch (LinkageError | RuntimeException unavailableRuntime) {
            // Plain unit tests intentionally run without the NeoForge runtime. The compile-time upper
            // bound is also the config default, so pure layout tests still exercise the production grid.
            spacingBlocks = OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING_LIMIT;
        }
        spacingBlocks = Math.max(
                OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING_LIMIT,
                Math.min(OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING_LIMIT, spacingBlocks)
        );
        return Math.max(1, (spacingBlocks + 15) / 16);
    }
}

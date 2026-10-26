package dev.world.level.levelgen;

import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.WorldGenRegion;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelReader;

/**
 * The part of a decoration region whose terrain really exists.
 *
 * <p>During {@code minecraft:features} a {@link WorldGenRegion} answers block reads up to eight
 * chunks away, but only the chunks inside its write radius are guaranteed to have reached {@code
 * CARVERS}. Farther chunks may still be at {@code STRUCTURE_STARTS}: their blocks read as air,
 * their heightmaps are empty, and any request for a later status (structure references, biomes)
 * throws "Requested chunk unavailable during world generation". Deposit planning reads only inside
 * this area, and every vanilla feature it runs keeps its whole footprint inside it, so mods hooking
 * that feature see the same guarantees as for a vanilla call.
 */
final class WorldgenReadArea {
    private WorldgenReadArea() {}

    /** Block bounds of the area, or {@code null} when {@code level} is not a decoration region. */
    @Nullable
    static GenerationWriteBounds of(LevelReader level) {
        if (!(level instanceof WorldGenRegion region)) {
            return null;
        }
        ChunkPos center = region.getCenter();
        return GenerationWriteBounds.around(
                center.x, center.z, OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS);
    }

    /** Allocation-free {@code of(level).contains(pos)}; always true outside a decoration region. */
    static boolean contains(LevelReader level, BlockPos pos) {
        if (!(level instanceof WorldGenRegion region)) {
            return true;
        }
        ChunkPos center = region.getCenter();
        return Math.abs((pos.getX() >> 4) - center.x)
                        <= OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS
                && Math.abs((pos.getZ() >> 4) - center.z)
                        <= OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS;
    }
}

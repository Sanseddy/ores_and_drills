package dev.world.geology;

import dev.compat.sable.SableSubLevels;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;

/**
 * The point a station or drill measures from. A block carried by a Sable sub-level is stored in
 * plot chunks far from the terrain the body rests on or hovers over, so it measures from the ground
 * under its world position; any other block measures from where it stands.
 */
public final class SurveySite {
    private static final boolean SABLE = ModList.get().isLoaded("sable");

    private SurveySite() {}

    /** The block above the ground under {@code pos}, or {@code pos} itself on solid ground. */
    public static BlockPos of(Level level, BlockPos pos) {
        Vec3 projected = projected(level, pos);
        if (projected == null) {
            return pos;
        }
        BlockPos start = BlockPos.containing(projected);
        BlockPos.MutableBlockPos cursor =
                new BlockPos.MutableBlockPos(
                        start.getX(),
                        Math.min(start.getY(), level.getMaxBuildHeight() - 1),
                        start.getZ());
        // A survey never loads chunks, so unloaded ground is not searched for.
        if (!level.isLoaded(cursor) || !level.getBlockState(cursor).isAir()) {
            return cursor.immutable();
        }
        // Water counts as ground: a body floating on it surveys like a marine seismic vessel.
        while (cursor.getY() > level.getMinBuildHeight()) {
            cursor.move(0, -1, 0);
            if (!level.getBlockState(cursor).isAir()) {
                return cursor.above().immutable();
            }
        }
        return cursor.immutable();
    }

    /**
     * Whether a carried block has left its site sideways by more than {@code tolerance} blocks.
     * Only horizontal travel counts: a body rising or settling still measures the same ground.
     */
    public static boolean moved(Level level, BlockPos pos, BlockPos site, double tolerance) {
        Vec3 projected = projected(level, pos);
        if (projected == null) {
            return false;
        }
        return Math.hypot(projected.x - (site.getX() + 0.5D), projected.z - (site.getZ() + 0.5D))
                > tolerance;
    }

    private static Vec3 projected(Level level, BlockPos pos) {
        return SABLE ? SableSubLevels.worldPosition(level, pos) : null;
    }
}

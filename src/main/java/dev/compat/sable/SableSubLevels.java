package dev.compat.sable;

import dev.ryanhcode.sable.companion.SableCompanion;
import dev.ryanhcode.sable.companion.SubLevelAccess;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/** Where blocks carried by a Sable sub-level are in the world; they are stored in plot chunks. */
public final class SableSubLevels {
    private SableSubLevels() {}

    /** World position of the centre of {@code pos}, or null when no sub-level carries it. */
    public static Vec3 worldPosition(Level level, BlockPos pos) {
        SubLevelAccess subLevel = SableCompanion.INSTANCE.getContaining(level, pos);
        if (subLevel == null) {
            return null;
        }
        return subLevel.logicalPose().transformPosition(Vec3.atCenterOf(pos));
    }
}

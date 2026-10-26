package dev.compat.sable;

import dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer;
import dev.ryanhcode.sable.api.sublevel.SubLevelContainer;
import dev.ryanhcode.sable.sublevel.ServerSubLevel;
import dev.ryanhcode.sable.sublevel.SubLevel;
import dev.ryanhcode.sable.sublevel.plot.LevelPlot;
import dev.ryanhcode.sable.sublevel.plot.PlotChunkHolder;
import dev.world.block.OreDepositImpactDamage;
import dev.world.block.OreDepositImpactMath;
import dev.world.level.levelgen.OreDepositData;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.LevelTickEvent;
import org.joml.Vector3d;

/**
 * Detects impacts of Sable sub-levels the way R.E.P.O. does for valuables: by sudden braking of the
 * body between two ticks. Sable updates {@link ServerSubLevel#latestLinearVelocity} once per tick
 * in blocks per second; a crash loses the speed it ends, while free fall, throws and pushes only
 * gain speed.
 *
 * <p>Everything runs after the level tick, outside Sable's native physics step: per-block collision
 * callbacks are invoked from inside that step, where touching chunks or changing blocks stalls the
 * server, and they make every deposit in the world a costlier collider.
 */
public final class SableOreDepositImpacts {
    /** Larger jumps are teleports or pose recoveries, not impacts. */
    private static final double MAX_IMPACT_SPEED = 150.0D;

    private static final Map<ServerSubLevel, Vector3d> LAST_VELOCITIES = new WeakHashMap<>();

    private SableOreDepositImpacts() {}

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SableOreDepositImpacts::onLevelTick);
    }

    private static void onLevelTick(LevelTickEvent.Post event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        ServerSubLevelContainer container = SubLevelContainer.getContainer(level);
        if (container == null) {
            return;
        }
        for (SubLevel subLevel : container.getAllSubLevels()) {
            if (subLevel instanceof ServerSubLevel body && !body.isRemoved()) {
                checkImpact(level, body);
            }
        }
    }

    private static void checkImpact(ServerLevel level, ServerSubLevel body) {
        Vector3d velocity = body.latestLinearVelocity;
        Vector3d last = LAST_VELOCITIES.get(body);
        if (last == null) {
            // A freshly assembled or loaded body has no previous velocity to compare with.
            LAST_VELOCITIES.put(body, new Vector3d(velocity));
            return;
        }
        // Speed lost, not the change of velocity: thrown, pushed or falling, a body speeds up or
        // turns, and only a collision brakes it.
        double impactSpeed = last.length() - velocity.length();
        // Where the body was heading before it hit, turned into its own block coordinates.
        Vector3d heading = body.logicalPose().orientation().transformInverse(new Vector3d(last));
        last.set(velocity);
        if (impactSpeed > MAX_IMPACT_SPEED || !OreDepositImpactMath.isDamaging(impactSpeed)) {
            return;
        }
        LevelPlot plot = body.getPlot();
        OreDepositImpactDamage.onBodyImpact(
                level,
                plot.plotPos.toLong(),
                impactSpeed,
                new Vec3(heading.x, heading.y, heading.z),
                () -> OreDepositData.physicalVeins(level, plotChunks(plot)));
    }

    private static List<LevelChunk> plotChunks(LevelPlot plot) {
        return plot.getLoadedChunks().stream()
                .map(PlotChunkHolder::getChunk)
                .filter(Objects::nonNull)
                .toList();
    }
}

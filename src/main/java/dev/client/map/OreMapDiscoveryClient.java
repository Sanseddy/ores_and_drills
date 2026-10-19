package dev.client.map;

import dev.network.OreDepositDiscoveryRequestPayload;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositTier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.HashMap;
import java.util.Map;

/**
 * Charts deposits the player actually sees or walks on, the way Factorio charts what is around the player.
 * Only the block underfoot and real sight rays are reported - never hidden chunk data - and the server
 * re-checks line of sight before it answers.
 */
public final class OreMapDiscoveryClient {
    /** Must stay within the server's {@code ModNetworking.DISCOVERY_SIGHT_DISTANCE}. */
    public static final double SIGHT_DISTANCE = 64.0D;
    /** Yaw/pitch offsets (degrees) of the sight rays; together they sweep the central part of the view. */
    private static final float[][] SIGHT_RAYS = {
            {0, 0}, {-14, 0}, {14, 0}, {0, -10}, {0, 10},
            {-28, -6}, {28, -6}, {-28, 8}, {28, 8},
            {-14, -18}, {14, -18}, {-14, 18}, {14, 18},
    };
    private static final int REPORT_COOLDOWN_TICKS = 40;
    private static final Map<Long, Integer> LAST_REPORT_TICK = new HashMap<>();
    private static Object lastLevel;
    private static int ticks;
    private static int nextRay;

    private OreMapDiscoveryClient() {
    }

    public static void tick() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || minecraft.player == null) {
            LAST_REPORT_TICK.clear();
            lastLevel = null;
            return;
        }
        if (lastLevel != minecraft.level) {
            LAST_REPORT_TICK.clear();
            lastLevel = minecraft.level;
        }
        ticks++;
        if (LAST_REPORT_TICK.size() > 512) {
            LAST_REPORT_TICK.values().removeIf(tick -> ticks - tick >= REPORT_COOLDOWN_TICKS);
        }

        // The crosshair ray every other tick, the rest of the fan one ray per tick in between.
        int ray = ticks % 2 == 0 ? 0 : 1 + nextRay++ % (SIGHT_RAYS.length - 1);
        Entity eye = minecraft.getCameraEntity() == null ? minecraft.player : minecraft.getCameraEntity();
        BlockPos seen = sightRay(minecraft, eye, SIGHT_RAYS[ray][0], SIGHT_RAYS[ray][1]);
        if (seen != null) {
            reportIfDeposit(seen);
        }
        if (ticks % 4 == 0) {
            reportIfDeposit(BlockPos.containing(
                    minecraft.player.getX(), minecraft.player.getBoundingBox().minY - 0.05D, minecraft.player.getZ()
            ));
        }
    }

    private static BlockPos sightRay(Minecraft minecraft, Entity eye, float yawOffset, float pitchOffset) {
        Vec3 from = eye.getEyePosition();
        Vec3 direction = Vec3.directionFromRotation(eye.getXRot() + pitchOffset, eye.getYRot() + yawOffset);
        Vec3 to = from.add(direction.scale(SIGHT_DISTANCE));
        BlockHitResult hit = minecraft.level.clip(new ClipContext(
                from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, eye
        ));
        return hit.getType() == HitResult.Type.BLOCK ? hit.getBlockPos() : null;
    }

    private static void reportIfDeposit(BlockPos pos) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null || !minecraft.level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }
        // Only medium and large deposits are charted. The client knows the tier, so the server never hears of the rest.
        if (OreDepositData.readTierAt(minecraft.level, pos) < OreDepositTier.MEDIUM.index()) {
            return;
        }
        // A charted patch is kept current from client chunk data; the server only describes new ones.
        if (OreMapMarkers.isCharted(minecraft.level.dimension().location(), pos)) {
            return;
        }
        long key = pos.asLong();
        int lastTick = LAST_REPORT_TICK.getOrDefault(key, Integer.MIN_VALUE / 2);
        if (ticks - lastTick >= REPORT_COOLDOWN_TICKS) {
            LAST_REPORT_TICK.put(key, ticks);
            PacketDistributor.sendToServer(new OreDepositDiscoveryRequestPayload(pos));
        }
    }
}

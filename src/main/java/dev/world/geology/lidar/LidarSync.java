package dev.world.geology.lidar;

import dev.config.SeismicSurveyConfig;
import dev.network.LidarSyncRequestPayload;
import dev.network.LidarUpdatePayload;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Streams 3D maps to the players whose screens show them. Each player has a cursor: the map epoch
 * and the highest bucket revision and object revision it was sent. Every tick each viewer gets the
 * buckets changed past its cursor, oldest change first, within a per-tick sample budget, so a
 * survey's growing cloud arrives in small slices and nothing unchanged is sent twice.
 */
public final class LidarSync {
    private static final Map<ServerPlayer, Cursor> CURSORS = new WeakHashMap<>();

    private LidarSync() {}

    /** A menu whose screen shows a 3D map. */
    public interface Source {
        BlockPos lidarOwner();

        /** The map, if the player may see it; null otherwise. */
        @Nullable
        LidarMap lidarMap(ServerPlayer player);
    }

    private static final class Cursor {
        private final BlockPos owner;
        private long epoch;
        private int revision;
        private int objectRevision;

        private Cursor(BlockPos owner, long epoch, int revision, int objectRevision) {
            this.owner = owner;
            this.epoch = epoch;
            this.revision = revision;
            this.objectRevision = objectRevision;
        }
    }

    public static synchronized void request(ServerPlayer player, LidarSyncRequestPayload payload) {
        if (!(player.containerMenu instanceof Source source)
                || !source.lidarOwner().equals(payload.owner())) {
            return;
        }
        CURSORS.put(
                player,
                new Cursor(
                        payload.owner().immutable(),
                        payload.epoch(),
                        Math.max(0, payload.revision()),
                        payload.objectRevision()));
    }

    public static synchronized void onServerTick(ServerTickEvent.Post event) {
        if (CURSORS.isEmpty()) {
            return;
        }
        int budget = SeismicSurveyConfig.LIDAR_SYNC_SAMPLES_PER_TICK.get();
        List<ServerPlayer> gone = new ArrayList<>();
        for (Map.Entry<ServerPlayer, Cursor> entry : CURSORS.entrySet()) {
            ServerPlayer player = entry.getKey();
            Cursor cursor = entry.getValue();
            if (player.hasDisconnected()
                    || !(player.containerMenu instanceof Source source)
                    || !source.lidarOwner().equals(cursor.owner)) {
                gone.add(player);
                continue;
            }
            LidarMap map = source.lidarMap(player);
            if (map != null) {
                send(player, cursor, map, budget);
            }
        }
        gone.forEach(CURSORS::remove);
    }

    private static void send(ServerPlayer player, Cursor cursor, LidarMap map, int budget) {
        boolean reset = cursor.epoch != map.epoch();
        if (reset) {
            cursor.epoch = map.epoch();
            cursor.revision = 0;
            cursor.objectRevision = -1;
        }
        List<LidarCodec.WireBucket> buckets = new ArrayList<>();
        if (map.revision() > cursor.revision) {
            for (LidarMap.BucketView view : map.bucketsAfter(cursor.revision, budget)) {
                if (buckets.size() >= LidarUpdatePayload.MAXIMUM_BUCKETS) {
                    break;
                }
                buckets.add(new LidarCodec.WireBucket(view.key(), view.revision(), view.samples()));
                cursor.revision = view.revision();
            }
        }
        boolean objectsChanged = map.objectRevision() != cursor.objectRevision;
        if (!reset && buckets.isEmpty() && !objectsChanged) {
            return;
        }
        List<LidarObject> objects = null;
        List<LidarCoverage> coverage = List.of();
        int objectRevision = map.objectRevision();
        if (objectsChanged) {
            objects = map.objects();
            if (objects.size() > LidarCodec.MAXIMUM_OBJECTS) {
                objects =
                        objects.subList(
                                objects.size() - LidarCodec.MAXIMUM_OBJECTS, objects.size());
            }
            coverage = map.coverage();
            cursor.objectRevision = objectRevision;
        }
        PacketDistributor.sendToPlayer(
                player,
                new LidarUpdatePayload(
                        cursor.owner,
                        cursor.epoch,
                        reset,
                        buckets,
                        objectRevision,
                        objects,
                        coverage));
    }
}

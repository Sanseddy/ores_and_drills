package dev.client.geology.lidar;

import dev.network.LidarSyncRequestPayload;
import dev.network.LidarUpdatePayload;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Maps the client received this session, per dimension and equipment position. Reopening a screen
 * restores its map at once and asks the server only for what changed meanwhile. A few maps are
 * kept; the least recently opened go first. Cleared when leaving the world.
 */
public final class LidarClientCache {
    private static final int CAPACITY = 6;
    private static final Map<Key, ClientLidarMap> MAPS = new LinkedHashMap<>(8, 0.75F, true);
    private static float ticks;

    private record Key(ResourceLocation dimension, BlockPos owner) {}

    private LidarClientCache() {}

    /** Client ticks plus the partial tick, the clock new points fade in by. */
    public static float now() {
        return ticks + Minecraft.getInstance().getTimer().getGameTimeDeltaPartialTick(false);
    }

    public static void tick() {
        ticks += 1.0F;
    }

    private static ResourceLocation dimension() {
        Minecraft minecraft = Minecraft.getInstance();
        return minecraft.level == null ? null : minecraft.level.dimension().location();
    }

    /** The map of the equipment at {@code owner}, created empty if not seen before. */
    public static ClientLidarMap map(BlockPos owner) {
        ResourceLocation dimension = dimension();
        Key key = new Key(dimension, owner.immutable());
        ClientLidarMap map = MAPS.computeIfAbsent(key, ignored -> new ClientLidarMap());
        Iterator<Key> oldest = MAPS.keySet().iterator();
        while (MAPS.size() > CAPACITY && oldest.hasNext()) {
            Key candidate = oldest.next();
            if (!candidate.equals(key)) {
                oldest.remove();
            }
        }
        return map;
    }

    /** Asks the server to continue the map from what this client already holds. */
    public static void requestSync(BlockPos owner) {
        ClientLidarMap map = map(owner);
        PacketDistributor.sendToServer(
                new LidarSyncRequestPayload(
                        owner, map.epoch(), map.revision(), map.objectRevision()));
    }

    public static void accept(LidarUpdatePayload payload) {
        if (dimension() == null) {
            return;
        }
        map(payload.owner()).accept(payload, now());
    }

    public static void clear() {
        MAPS.clear();
    }
}

package dev.network;

import dev.OresAndDrillsMod;
import dev.world.geology.lidar.LidarCodec;
import dev.world.geology.lidar.LidarCoverage;
import dev.world.geology.lidar.LidarObject;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * A slice of a 3D map for the screen that shows it: changed buckets, oldest change first, and the
 * object list when it changed. Only what the equipment has already revealed is ever in a map, so
 * nothing here goes beyond the survey fronts or names a deposit; samples are processed measurements
 * and objects are interpretations with their errors.
 *
 * @param reset the client's copy belongs to another epoch and must be dropped first
 * @param objects null when the objects did not change
 */
public record LidarUpdatePayload(
        BlockPos owner,
        long epoch,
        boolean reset,
        List<LidarCodec.WireBucket> buckets,
        int objectRevision,
        List<LidarObject> objects,
        List<LidarCoverage> coverage)
        implements CustomPacketPayload {
    public static final int MAXIMUM_BUCKETS = 4_096;

    public static final Type<LidarUpdatePayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "lidar_update"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LidarUpdatePayload> STREAM_CODEC =
            StreamCodec.of(LidarUpdatePayload::write, LidarUpdatePayload::read);

    public boolean hasObjects() {
        return objects != null;
    }

    private static void write(RegistryFriendlyByteBuf buffer, LidarUpdatePayload payload) {
        buffer.writeBlockPos(payload.owner());
        buffer.writeLong(payload.epoch());
        buffer.writeBoolean(payload.reset());
        buffer.writeVarInt(payload.buckets().size());
        for (LidarCodec.WireBucket bucket : payload.buckets()) {
            LidarCodec.writeBucket(buffer, bucket.key(), bucket.revision(), bucket.samples());
        }
        buffer.writeBoolean(payload.hasObjects());
        if (payload.hasObjects()) {
            buffer.writeVarInt(payload.objectRevision());
            buffer.writeVarInt(payload.objects().size());
            for (LidarObject object : payload.objects()) {
                LidarCodec.writeObject(buffer, object);
            }
            buffer.writeVarInt(payload.coverage().size());
            for (LidarCoverage c : payload.coverage()) {
                LidarCodec.writeCoverage(buffer, c);
            }
        }
    }

    private static LidarUpdatePayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos owner = buffer.readBlockPos();
        long epoch = buffer.readLong();
        boolean reset = buffer.readBoolean();
        int bucketCount = checked(buffer.readVarInt(), MAXIMUM_BUCKETS);
        List<LidarCodec.WireBucket> buckets = new ArrayList<>(bucketCount);
        for (int i = 0; i < bucketCount; i++) {
            buckets.add(LidarCodec.readBucket(buffer));
        }
        if (!buffer.readBoolean()) {
            return new LidarUpdatePayload(owner, epoch, reset, buckets, 0, null, List.of());
        }
        int objectRevision = buffer.readVarInt();
        int objectCount = checked(buffer.readVarInt(), LidarCodec.MAXIMUM_OBJECTS);
        List<LidarObject> objects = new ArrayList<>(objectCount);
        for (int i = 0; i < objectCount; i++) {
            objects.add(LidarCodec.readObject(buffer));
        }
        int coverageCount = checked(buffer.readVarInt(), LidarCodec.MAXIMUM_COVERAGE);
        List<LidarCoverage> coverage = new ArrayList<>(coverageCount);
        for (int i = 0; i < coverageCount; i++) {
            coverage.add(LidarCodec.readCoverage(buffer));
        }
        return new LidarUpdatePayload(
                owner, epoch, reset, buckets, objectRevision, objects, coverage);
    }

    private static int checked(int count, int maximum) {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Invalid LiDAR payload size " + count);
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

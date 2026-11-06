package dev.network;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Sent when a station or drill screen opens: the state of the 3D map the client still holds from
 * earlier, so the server sends only what changed since. A different epoch makes the server start
 * from an empty map. Accepted only from a player who has that equipment's menu open.
 */
public record LidarSyncRequestPayload(BlockPos owner, long epoch, int revision, int objectRevision)
        implements CustomPacketPayload {
    public static final Type<LidarSyncRequestPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "lidar_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, LidarSyncRequestPayload> STREAM_CODEC =
            StreamCodec.of(
                    (buffer, payload) -> {
                        buffer.writeBlockPos(payload.owner());
                        buffer.writeLong(payload.epoch());
                        buffer.writeVarInt(payload.revision());
                        buffer.writeVarInt(payload.objectRevision());
                    },
                    buffer ->
                            new LidarSyncRequestPayload(
                                    buffer.readBlockPos(),
                                    buffer.readLong(),
                                    buffer.readVarInt(),
                                    buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

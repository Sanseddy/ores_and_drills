package dev.network;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Station screen request for a survey's report. The server accepts it only from a player who has
 * that station's menu open.
 */
public record SeismicStationRequestPayload(BlockPos station, int surveyId)
        implements CustomPacketPayload {
    public static final Type<SeismicStationRequestPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            OresAndDrillsMod.MOD_ID, "seismic_station_request"));

    public static final StreamCodec<RegistryFriendlyByteBuf, SeismicStationRequestPayload>
            STREAM_CODEC =
                    StreamCodec.of(
                            (buffer, payload) -> {
                                buffer.writeBlockPos(payload.station());
                                buffer.writeVarInt(payload.surveyId());
                            },
                            buffer ->
                                    new SeismicStationRequestPayload(
                                            buffer.readBlockPos(), buffer.readVarInt()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

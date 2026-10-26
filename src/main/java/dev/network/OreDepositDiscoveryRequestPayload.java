package dev.network;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** A position the client can legitimately see or is standing on. The server validates it. */
public record OreDepositDiscoveryRequestPayload(BlockPos pos) implements CustomPacketPayload {
    public static final Type<OreDepositDiscoveryRequestPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            OresAndDrillsMod.MOD_ID, "discover_ore_deposit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OreDepositDiscoveryRequestPayload>
            STREAM_CODEC =
                    StreamCodec.of(
                            (buffer, payload) -> buffer.writeBlockPos(payload.pos()),
                            buffer -> new OreDepositDiscoveryRequestPayload(buffer.readBlockPos()));

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

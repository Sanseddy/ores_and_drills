package dev.network;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Complete entry sync used to recover client plot data when Sable rebuilds a chunk on a block update. */
public record OreDepositRemainingPayload(
        BlockPos pos,
        int baseIndex,
        int oreIndex,
        int remainingOre,
        int initialOre,
        int initialRichness,
        int richnessReferenceAmount,
        int tier
) implements CustomPacketPayload {
    public static final Type<OreDepositRemainingPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "ore_deposit_remaining")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, OreDepositRemainingPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OreDepositRemainingPayload decode(RegistryFriendlyByteBuf buffer) {
            return new OreDepositRemainingPayload(
                    buffer.readBlockPos(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt(),
                    buffer.readVarInt()
            );
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OreDepositRemainingPayload payload) {
            buffer.writeBlockPos(payload.pos());
            buffer.writeVarInt(payload.baseIndex());
            buffer.writeVarInt(payload.oreIndex());
            buffer.writeVarInt(payload.remainingOre());
            buffer.writeVarInt(payload.initialOre());
            buffer.writeVarInt(payload.initialRichness());
            buffer.writeVarInt(payload.richnessReferenceAmount());
            buffer.writeVarInt(payload.tier());
        }
    };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

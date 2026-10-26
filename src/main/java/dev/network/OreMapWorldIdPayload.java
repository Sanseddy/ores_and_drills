package dev.network;

import dev.OresAndDrillsMod;
import java.util.UUID;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server-owned persistent world identity for selecting the correct client ore-map file. */
public record OreMapWorldIdPayload(UUID worldId) implements CustomPacketPayload {
    public static final Type<OreMapWorldIdPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            OresAndDrillsMod.MOD_ID, "ore_map_world_id"));
    public static final StreamCodec<RegistryFriendlyByteBuf, OreMapWorldIdPayload> STREAM_CODEC =
            new StreamCodec<>() {
                @Override
                public OreMapWorldIdPayload decode(RegistryFriendlyByteBuf buffer) {
                    return new OreMapWorldIdPayload(buffer.readUUID());
                }

                @Override
                public void encode(RegistryFriendlyByteBuf buffer, OreMapWorldIdPayload payload) {
                    buffer.writeUUID(payload.worldId());
                }
            };

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

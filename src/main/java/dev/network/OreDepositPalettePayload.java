package dev.network;

import dev.FactoryExpansionMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.List;

public record OreDepositPalettePayload(
        List<ResourceLocation> bases,
        List<ResourceLocation> ores,
        List<ResourceLocation> drops
) implements CustomPacketPayload {
    public static final Type<OreDepositPalettePayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(FactoryExpansionMod.MOD_ID, "ore_deposit_palette")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, OreDepositPalettePayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OreDepositPalettePayload decode(RegistryFriendlyByteBuf buffer) {
            return new OreDepositPalettePayload(readIds(buffer), readIds(buffer), readIds(buffer));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OreDepositPalettePayload payload) {
            writeIds(buffer, payload.bases());
            writeIds(buffer, payload.ores());
            writeIds(buffer, payload.drops());
        }
    };

    public OreDepositPalettePayload {
        bases = List.copyOf(bases);
        ores = List.copyOf(ores);
        drops = List.copyOf(drops);
    }

    private static List<ResourceLocation> readIds(RegistryFriendlyByteBuf buffer) {
        int size = buffer.readVarInt();
        List<ResourceLocation> ids = new ArrayList<>(size);
        for (int index = 0; index < size; index++) {
            ids.add(buffer.readResourceLocation());
        }
        return List.copyOf(ids);
    }

    private static void writeIds(RegistryFriendlyByteBuf buffer, List<ResourceLocation> ids) {
        buffer.writeVarInt(ids.size());
        for (ResourceLocation id : ids) {
            buffer.writeResourceLocation(id);
        }
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

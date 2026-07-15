package dev.network;

import dev.FactoryExpansionMod;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

public record FluidTankClickPayload(int containerId) implements CustomPacketPayload {
    public static final Type<FluidTankClickPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(FactoryExpansionMod.MOD_ID, "fluid_tank_click"));

    public static final StreamCodec<RegistryFriendlyByteBuf, FluidTankClickPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, FluidTankClickPayload::containerId,
            FluidTankClickPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

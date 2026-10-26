package dev.network;

import dev.OresAndDrillsMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Authoritative map marker returned after a server-side discovery check. */
public record DiscoveredOreDepositPayload(
        long depositId,
        ResourceLocation dimension,
        BlockPos center,
        ResourceLocation oreId,
        int tier,
        int color,
        long remainingOre,
        long initialOre,
        List<Cell> cells)
        implements CustomPacketPayload {
    public static final Type<DiscoveredOreDepositPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            OresAndDrillsMod.MOD_ID, "discovered_ore_deposit"));
    public static final StreamCodec<RegistryFriendlyByteBuf, DiscoveredOreDepositPayload>
            STREAM_CODEC =
                    new StreamCodec<>() {
                        @Override
                        public DiscoveredOreDepositPayload decode(RegistryFriendlyByteBuf buffer) {
                            long depositId = buffer.readLong();
                            ResourceLocation dimension = buffer.readResourceLocation();
                            BlockPos center = buffer.readBlockPos();
                            ResourceLocation oreId = buffer.readResourceLocation();
                            int tier = buffer.readVarInt();
                            int color = buffer.readInt();
                            long remainingOre = buffer.readVarLong();
                            long initialOre = buffer.readVarLong();
                            int size = buffer.readVarInt();
                            if (size < 0 || size > 4_096) {
                                throw new IllegalArgumentException(
                                        "Invalid ore map cell count: " + size);
                            }
                            List<Cell> cells = new ArrayList<>(size);
                            for (int index = 0; index < size; index++) {
                                cells.add(
                                        new Cell(
                                                buffer.readByte(),
                                                buffer.readByte(),
                                                buffer.readVarInt(),
                                                buffer.readVarInt()));
                            }
                            return new DiscoveredOreDepositPayload(
                                    depositId,
                                    dimension,
                                    center,
                                    oreId,
                                    tier,
                                    color,
                                    remainingOre,
                                    initialOre,
                                    cells);
                        }

                        @Override
                        public void encode(
                                RegistryFriendlyByteBuf buffer,
                                DiscoveredOreDepositPayload payload) {
                            buffer.writeLong(payload.depositId());
                            buffer.writeResourceLocation(payload.dimension());
                            buffer.writeBlockPos(payload.center());
                            buffer.writeResourceLocation(payload.oreId());
                            buffer.writeVarInt(payload.tier());
                            buffer.writeInt(payload.color());
                            buffer.writeVarLong(payload.remainingOre());
                            buffer.writeVarLong(payload.initialOre());
                            buffer.writeVarInt(payload.cells().size());
                            for (Cell cell : payload.cells()) {
                                buffer.writeByte(cell.offsetX());
                                buffer.writeByte(cell.offsetZ());
                                buffer.writeVarInt(cell.remainingOre());
                                buffer.writeVarInt(cell.initialOre());
                            }
                        }
                    };

    public DiscoveredOreDepositPayload {
        cells = List.copyOf(cells);
    }

    public record Cell(int offsetX, int offsetZ, int remainingOre, int initialOre) {}

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

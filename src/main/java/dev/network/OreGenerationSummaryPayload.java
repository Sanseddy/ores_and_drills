package dev.network;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.OreGenerationSummary;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** {@link OreGenerationSummary} for client recipe viewers. Biome ids repeat across ores, so they go once in a table. */
public record OreGenerationSummaryPayload(
        List<OreGenerationSummary.Entry> entries,
        List<ResourceLocation> replacedFeatures
) implements CustomPacketPayload {
    public static final Type<OreGenerationSummaryPayload> TYPE = new Type<>(
            ResourceLocation.fromNamespaceAndPath(OresAndDrillsMod.MOD_ID, "ore_generation_summary")
    );
    public static final StreamCodec<RegistryFriendlyByteBuf, OreGenerationSummaryPayload> STREAM_CODEC = new StreamCodec<>() {
        @Override
        public OreGenerationSummaryPayload decode(RegistryFriendlyByteBuf buffer) {
            List<ResourceLocation> biomeTable = readIds(buffer);
            int size = buffer.readVarInt();
            List<OreGenerationSummary.Entry> entries = new ArrayList<>(size);
            for (int index = 0; index < size; index++) {
                ResourceLocation oreId = buffer.readResourceLocation();
                int biomeCount = buffer.readVarInt();
                List<ResourceLocation> biomes = new ArrayList<>(biomeCount);
                for (int biome = 0; biome < biomeCount; biome++) {
                    biomes.add(biomeTable.get(buffer.readVarInt()));
                }
                entries.add(new OreGenerationSummary.Entry(
                        oreId, biomes, buffer.readVarInt(), buffer.readVarInt(), buffer.readBoolean()
                ));
            }
            return new OreGenerationSummaryPayload(entries, readIds(buffer));
        }

        @Override
        public void encode(RegistryFriendlyByteBuf buffer, OreGenerationSummaryPayload payload) {
            Map<ResourceLocation, Integer> biomeIndexes = new LinkedHashMap<>();
            payload.entries().forEach(entry -> entry.biomes()
                    .forEach(biome -> biomeIndexes.putIfAbsent(biome, biomeIndexes.size())));
            writeIds(buffer, List.copyOf(biomeIndexes.keySet()));
            buffer.writeVarInt(payload.entries().size());
            for (OreGenerationSummary.Entry entry : payload.entries()) {
                buffer.writeResourceLocation(entry.oreId());
                buffer.writeVarInt(entry.biomes().size());
                entry.biomes().forEach(biome -> buffer.writeVarInt(biomeIndexes.get(biome)));
                buffer.writeVarInt(entry.minY());
                buffer.writeVarInt(entry.maxY());
                buffer.writeBoolean(entry.deposit());
            }
            writeIds(buffer, payload.replacedFeatures());
        }
    };

    public OreGenerationSummaryPayload {
        entries = List.copyOf(entries);
        replacedFeatures = List.copyOf(replacedFeatures);
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

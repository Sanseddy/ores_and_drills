package dev.network;

import dev.OresAndDrillsMod;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Summary of a station's saved surveys plus the field equipment around it and the ore table of the
 * dimension, sent when its screen opens and whenever a survey finishes. Boreholes are the player's
 * own results; geophones are blocks the player placed; the ore table holds only what the game's
 * generation data says about each ore and how well the station tells it apart.
 */
public record SeismicSurveyListPayload(
        BlockPos station,
        List<Entry> surveys,
        List<BlockPos> geophones,
        List<Hole> boreholes,
        List<OreRow> ores)
        implements CustomPacketPayload {
    public static final int MAXIMUM_ENTRIES = 64;
    public static final int MAXIMUM_MARKS = 512;
    public static final int MAXIMUM_ORES = 512;

    public static final Type<SeismicSurveyListPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            OresAndDrillsMod.MOD_ID, "seismic_survey_list"));

    public record Entry(
            int id,
            int mode,
            long gameTime,
            int centerX,
            int centerZ,
            int radius,
            int geophones,
            float quality,
            int anomalies,
            float peak,
            int level) {}

    /**
     * One ore of the dimension's table.
     *
     * @param category {@code OreTraits.Category} ordinal
     * @param prevalence share of this ore among the dimension's ores (0–1)
     * @param generationKnown false when no generation was recorded; heights are then meaningless
     * @param identification chance the station names the ore correctly at its current level
     * @param identificationMax the same at the highest level
     */
    public record OreRow(
            String oreId,
            int category,
            float prevalence,
            int minY,
            int maxY,
            boolean generationKnown,
            float identification,
            float identificationMax) {}

    public record Hole(
            BlockPos top,
            int bottomY,
            Optional<ResourceLocation> ore,
            int oreTopY,
            int oreBottomY,
            int units,
            boolean industrial) {}

    public static final StreamCodec<RegistryFriendlyByteBuf, SeismicSurveyListPayload>
            STREAM_CODEC =
                    StreamCodec.of(SeismicSurveyListPayload::write, SeismicSurveyListPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, SeismicSurveyListPayload payload) {
        buffer.writeBlockPos(payload.station());
        buffer.writeVarInt(payload.surveys().size());
        for (Entry entry : payload.surveys()) {
            buffer.writeVarInt(entry.id());
            buffer.writeVarInt(entry.mode());
            buffer.writeVarLong(entry.gameTime());
            buffer.writeInt(entry.centerX());
            buffer.writeInt(entry.centerZ());
            buffer.writeVarInt(entry.radius());
            buffer.writeVarInt(entry.geophones());
            buffer.writeFloat(entry.quality());
            buffer.writeVarInt(entry.anomalies());
            buffer.writeFloat(entry.peak());
            buffer.writeVarInt(entry.level());
        }
        buffer.writeVarInt(payload.geophones().size());
        for (BlockPos pos : payload.geophones()) {
            buffer.writeBlockPos(pos);
        }
        buffer.writeVarInt(payload.boreholes().size());
        for (Hole hole : payload.boreholes()) {
            buffer.writeBlockPos(hole.top());
            buffer.writeInt(hole.bottomY());
            buffer.writeOptional(hole.ore(), (buf, id) -> buf.writeResourceLocation(id));
            buffer.writeInt(hole.oreTopY());
            buffer.writeInt(hole.oreBottomY());
            buffer.writeVarInt(hole.units());
            buffer.writeBoolean(hole.industrial());
        }
        buffer.writeVarInt(payload.ores().size());
        for (OreRow row : payload.ores()) {
            buffer.writeUtf(row.oreId(), 256);
            buffer.writeByte(row.category());
            buffer.writeFloat(row.prevalence());
            buffer.writeInt(row.minY());
            buffer.writeInt(row.maxY());
            buffer.writeBoolean(row.generationKnown());
            buffer.writeFloat(row.identification());
            buffer.writeFloat(row.identificationMax());
        }
    }

    private static SeismicSurveyListPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos station = buffer.readBlockPos();
        int count = checked(buffer.readVarInt(), MAXIMUM_ENTRIES);
        List<Entry> entries = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            entries.add(
                    new Entry(
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readVarLong(),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readVarInt(),
                            buffer.readVarInt(),
                            buffer.readFloat(),
                            buffer.readVarInt(),
                            buffer.readFloat(),
                            buffer.readVarInt()));
        }
        int geophoneCount = checked(buffer.readVarInt(), MAXIMUM_MARKS);
        List<BlockPos> geophones = new ArrayList<>(geophoneCount);
        for (int i = 0; i < geophoneCount; i++) {
            geophones.add(buffer.readBlockPos());
        }
        int holeCount = checked(buffer.readVarInt(), MAXIMUM_MARKS);
        List<Hole> holes = new ArrayList<>(holeCount);
        for (int i = 0; i < holeCount; i++) {
            holes.add(
                    new Hole(
                            buffer.readBlockPos(),
                            buffer.readInt(),
                            buffer.readOptional(buf -> buf.readResourceLocation()),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readVarInt(),
                            buffer.readBoolean()));
        }
        int oreCount = checked(buffer.readVarInt(), MAXIMUM_ORES);
        List<OreRow> ores = new ArrayList<>(oreCount);
        for (int i = 0; i < oreCount; i++) {
            ores.add(
                    new OreRow(
                            buffer.readUtf(256),
                            buffer.readByte(),
                            buffer.readFloat(),
                            buffer.readInt(),
                            buffer.readInt(),
                            buffer.readBoolean(),
                            buffer.readFloat(),
                            buffer.readFloat()));
        }
        return new SeismicSurveyListPayload(station, entries, geophones, holes, ores);
    }

    private static int checked(int count, int maximum) {
        if (count < 0 || count > maximum) {
            throw new IllegalArgumentException("Invalid survey list size " + count);
        }
        return count;
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

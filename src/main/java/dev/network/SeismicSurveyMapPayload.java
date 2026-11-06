package dev.network;

import dev.OresAndDrillsMod;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * The report of one finished survey for the station's survey log: its programme, equipment, quality
 * figures and how many targets it interpreted. Its spatial results live in the 3D map; this payload
 * carries no map grid, and its targets are interpretations — never a deposit's identity or true
 * position.
 *
 * @param metrics see {@link #metric}
 * @param level upgrade level of the equipment that ran the survey
 * @param minY lowest height the survey modelled
 */
public record SeismicSurveyMapPayload(
        BlockPos station,
        int surveyId,
        int mode,
        int level,
        double centerX,
        double centerZ,
        double radius,
        int datumY,
        int minY,
        int resolution,
        float[] metrics,
        List<BlockPos> geophones,
        List<Target> targets)
        implements CustomPacketPayload {
    public static final int MAXIMUM_RESOLUTION = 256;
    public static final int MAXIMUM_TARGETS = 128;
    public static final int MAXIMUM_CANDIDATES = 8;

    public static final int METRIC_RECEIVERS = 0;
    public static final int METRIC_COVERAGE = 1;
    public static final int METRIC_VELOCITY_ERROR = 2;
    public static final int METRIC_DEPTH_ERROR = 3;
    public static final int METRIC_LATERAL = 4;
    public static final int METRIC_NOISE = 5;
    public static final int METRIC_QUALITY = 6;
    public static final int METRIC_PEAK = 7;
    public static final int METRIC_GEOPHONES = 8;
    public static final int METRIC_COUNT = 9;

    public static final Type<SeismicSurveyMapPayload> TYPE =
            new Type<>(
                    ResourceLocation.fromNamespaceAndPath(
                            OresAndDrillsMod.MOD_ID, "seismic_survey_map"));

    /**
     * One explanation of a target.
     *
     * @param source {@code OreIdentification.Source} ordinal
     * @param category {@code OreTraits.Category} ordinal, or -1
     * @param oreId ore block id, or empty when only the class is known
     */
    public record Candidate(int source, int category, String oreId, float probability) {}

    /**
     * An interpreted anomaly. {@code y}, {@code thickness} and {@code depthError} are NaN when the
     * survey did not resolve them.
     *
     * @param detail {@code SurveyTarget.Detail} ordinal
     */
    public record Target(
            float x,
            float y,
            float z,
            float radiusX,
            float radiusZ,
            float angle,
            float thickness,
            float positionError,
            float depthError,
            float intensity,
            int polarity,
            int level,
            int detail,
            float oreProbability,
            List<Candidate> candidates) {
        public boolean depthKnown() {
            return Float.isFinite(y);
        }
    }

    public float metric(int index) {
        return index >= 0 && index < metrics.length ? metrics[index] : 0.0F;
    }

    public static final StreamCodec<RegistryFriendlyByteBuf, SeismicSurveyMapPayload> STREAM_CODEC =
            StreamCodec.of(SeismicSurveyMapPayload::write, SeismicSurveyMapPayload::read);

    private static void write(RegistryFriendlyByteBuf buffer, SeismicSurveyMapPayload payload) {
        buffer.writeBlockPos(payload.station());
        buffer.writeVarInt(payload.surveyId());
        buffer.writeVarInt(payload.mode());
        buffer.writeVarInt(payload.level());
        buffer.writeDouble(payload.centerX());
        buffer.writeDouble(payload.centerZ());
        buffer.writeDouble(payload.radius());
        buffer.writeInt(payload.datumY());
        buffer.writeInt(payload.minY());
        buffer.writeVarInt(payload.resolution());
        buffer.writeVarInt(payload.metrics().length);
        for (float value : payload.metrics()) {
            buffer.writeFloat(value);
        }
        buffer.writeVarInt(payload.geophones().size());
        for (BlockPos pos : payload.geophones()) {
            buffer.writeBlockPos(pos);
        }
        buffer.writeVarInt(payload.targets().size());
        for (Target target : payload.targets()) {
            buffer.writeFloat(target.x());
            buffer.writeFloat(target.y());
            buffer.writeFloat(target.z());
            buffer.writeFloat(target.radiusX());
            buffer.writeFloat(target.radiusZ());
            buffer.writeFloat(target.angle());
            buffer.writeFloat(target.thickness());
            buffer.writeFloat(target.positionError());
            buffer.writeFloat(target.depthError());
            buffer.writeFloat(target.intensity());
            buffer.writeByte(target.polarity());
            buffer.writeByte(target.level());
            buffer.writeByte(target.detail());
            buffer.writeFloat(target.oreProbability());
            buffer.writeVarInt(target.candidates().size());
            for (Candidate candidate : target.candidates()) {
                buffer.writeByte(candidate.source());
                buffer.writeByte(candidate.category());
                buffer.writeUtf(candidate.oreId(), 256);
                buffer.writeFloat(candidate.probability());
            }
        }
    }

    private static SeismicSurveyMapPayload read(RegistryFriendlyByteBuf buffer) {
        BlockPos station = buffer.readBlockPos();
        int surveyId = buffer.readVarInt();
        int mode = buffer.readVarInt();
        int level = buffer.readVarInt();
        double centerX = buffer.readDouble();
        double centerZ = buffer.readDouble();
        double radius = buffer.readDouble();
        int datumY = buffer.readInt();
        int minY = buffer.readInt();
        int resolution = buffer.readVarInt();
        if (resolution < 1 || resolution > MAXIMUM_RESOLUTION) {
            throw new IllegalArgumentException("Invalid survey resolution " + resolution);
        }
        int metricCount = Math.min(64, buffer.readVarInt());
        float[] metrics = new float[metricCount];
        for (int i = 0; i < metricCount; i++) {
            metrics[i] = buffer.readFloat();
        }
        int geophoneCount = Math.min(1024, buffer.readVarInt());
        List<BlockPos> geophones = new ArrayList<>(geophoneCount);
        for (int i = 0; i < geophoneCount; i++) {
            geophones.add(buffer.readBlockPos());
        }
        int targetCount = buffer.readVarInt();
        if (targetCount > MAXIMUM_TARGETS) {
            throw new IllegalArgumentException("Too many survey targets");
        }
        List<Target> targets = new ArrayList<>(targetCount);
        for (int i = 0; i < targetCount; i++) {
            float x = buffer.readFloat();
            float y = buffer.readFloat();
            float z = buffer.readFloat();
            float radiusX = buffer.readFloat();
            float radiusZ = buffer.readFloat();
            float angle = buffer.readFloat();
            float thickness = buffer.readFloat();
            float positionError = buffer.readFloat();
            float depthError = buffer.readFloat();
            float targetIntensity = buffer.readFloat();
            int targetPolarity = buffer.readByte();
            int targetLevel = buffer.readByte();
            int detail = buffer.readByte();
            float oreProbability = buffer.readFloat();
            int candidateCount = buffer.readVarInt();
            if (candidateCount > MAXIMUM_CANDIDATES) {
                throw new IllegalArgumentException("Too many target candidates");
            }
            List<Candidate> candidates = new ArrayList<>(candidateCount);
            for (int c = 0; c < candidateCount; c++) {
                candidates.add(
                        new Candidate(
                                buffer.readByte(),
                                buffer.readByte(),
                                buffer.readUtf(256),
                                buffer.readFloat()));
            }
            targets.add(
                    new Target(
                            x,
                            y,
                            z,
                            radiusX,
                            radiusZ,
                            angle,
                            thickness,
                            positionError,
                            depthError,
                            targetIntensity,
                            targetPolarity,
                            targetLevel,
                            detail,
                            oreProbability,
                            List.copyOf(candidates)));
        }
        return new SeismicSurveyMapPayload(
                station,
                surveyId,
                mode,
                level,
                centerX,
                centerZ,
                radius,
                datumY,
                minY,
                resolution,
                metrics,
                geophones,
                List.copyOf(targets));
    }

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}

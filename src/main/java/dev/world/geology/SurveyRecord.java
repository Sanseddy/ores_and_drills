package dev.world.geology;

import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreTraits;
import java.util.List;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * A finished survey as the station archive keeps it: the processed map, the configuration that
 * produced it and the interpreted targets. Targets are stored as interpreted when the survey ran:
 * deposits change as the world is mined, so an old survey keeps showing what it measured then.
 *
 * @param configHash identity of the configuration; equal hashes carry equal errors and are not
 *     stacked twice
 * @param level upgrade level of the equipment that ran the survey
 */
public record SurveyRecord(
        int id,
        ResourceLocation dimension,
        long gameTime,
        long configHash,
        SurveyMode mode,
        double centerX,
        double datumY,
        double centerZ,
        double radius,
        int minY,
        NaturalGeology.Setting setting,
        double environment,
        boolean wellTie,
        long noiseSeed,
        double[] geophoneX,
        double[] geophoneZ,
        int level,
        SurveyMap map,
        List<SurveyTarget> targets) {
    public static final int FORMAT = 2;

    private static final double SNR_SCALE = 64.0D;
    private static final double SIGMA_LOG_SCALE = 2048.0D;
    private static final double SIGMA_LOG_OFFSET = 16.0D;
    private static final double LATERAL_SCALE = 10.0D;

    public SurveyRecord {
        geophoneX = geophoneX.clone();
        geophoneZ = geophoneZ.clone();
        targets = List.copyOf(targets);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", FORMAT);
        tag.putInt("Id", id);
        tag.putString("Dimension", dimension.toString());
        tag.putLong("GameTime", gameTime);
        tag.putLong("ConfigHash", configHash);
        tag.putInt("Mode", mode.ordinal());
        tag.putDouble("CenterX", centerX);
        tag.putDouble("DatumY", datumY);
        tag.putDouble("CenterZ", centerZ);
        tag.putDouble("Radius", radius);
        tag.putInt("MinY", minY);
        tag.putInt("Setting", setting.ordinal());
        tag.putDouble("Environment", environment);
        tag.putBoolean("WellTie", wellTie);
        tag.putLong("NoiseSeed", noiseSeed);
        tag.putLongArray("GeophoneX", toBits(geophoneX));
        tag.putLongArray("GeophoneZ", toBits(geophoneZ));
        tag.putInt("Level", level);
        tag.put("Map", saveMap(map));
        ListTag targetList = new ListTag();
        for (SurveyTarget target : targets) {
            targetList.add(saveTarget(target));
        }
        tag.put("Targets", targetList);
        return tag;
    }

    public static SurveyRecord load(CompoundTag tag) {
        if (tag.getInt("Format") != FORMAT) {
            throw new IllegalArgumentException("Unsupported survey record format");
        }
        ResourceLocation dimension = ResourceLocation.tryParse(tag.getString("Dimension"));
        if (dimension == null) {
            throw new IllegalArgumentException("Survey record without a dimension");
        }
        SurveyMode mode = SurveyMode.byId(tag.getInt("Mode"));
        double centerX = tag.getDouble("CenterX");
        double centerZ = tag.getDouble("CenterZ");
        double radius = tag.getDouble("Radius");
        SurveyMap map = loadMap(tag.getCompound("Map"), mode, centerX, centerZ, radius);
        ListTag targetList = tag.getList("Targets", Tag.TAG_COMPOUND);
        java.util.ArrayList<SurveyTarget> targets = new java.util.ArrayList<>(targetList.size());
        for (int i = 0; i < targetList.size(); i++) {
            targets.add(loadTarget(targetList.getCompound(i)));
        }
        return new SurveyRecord(
                tag.getInt("Id"),
                dimension,
                tag.getLong("GameTime"),
                tag.getLong("ConfigHash"),
                mode,
                centerX,
                tag.getDouble("DatumY"),
                centerZ,
                radius,
                tag.getInt("MinY"),
                NaturalGeology.Setting.byId(tag.getInt("Setting")),
                tag.getDouble("Environment"),
                tag.getBoolean("WellTie"),
                tag.getLong("NoiseSeed"),
                fromBits(tag.getLongArray("GeophoneX")),
                fromBits(tag.getLongArray("GeophoneZ")),
                ScannerUpgrade.clamp(tag.getInt("Level")),
                map,
                targets);
    }

    static CompoundTag saveTarget(SurveyTarget target) {
        CompoundTag tag = new CompoundTag();
        tag.putLong("Key", target.key());
        tag.putDouble("X", target.x());
        tag.putDouble("Y", target.y());
        tag.putDouble("Z", target.z());
        tag.putDouble("RadiusX", target.radiusX());
        tag.putDouble("RadiusZ", target.radiusZ());
        tag.putDouble("Angle", target.angle());
        tag.putDouble("Thickness", target.thickness());
        tag.putDouble("PositionError", target.positionError());
        tag.putDouble("DepthError", target.depthError());
        tag.putDouble("Intensity", target.intensity());
        tag.putInt("Polarity", target.polarity());
        tag.putInt("Level", target.level());
        tag.putInt("Detail", target.detail().ordinal());
        tag.putDouble("Ore", target.oreProbability());
        ListTag list = new ListTag();
        for (SurveyTarget.Candidate candidate : target.candidates()) {
            CompoundTag entry = new CompoundTag();
            entry.putInt("Source", candidate.source().ordinal());
            entry.putInt(
                    "Category", candidate.category() == null ? -1 : candidate.category().ordinal());
            entry.putString("Ore", candidate.oreId());
            entry.putDouble("P", candidate.probability());
            list.add(entry);
        }
        tag.put("Candidates", list);
        return tag;
    }

    static SurveyTarget loadTarget(CompoundTag tag) {
        ListTag list = tag.getList("Candidates", Tag.TAG_COMPOUND);
        List<SurveyTarget.Candidate> candidates = new java.util.ArrayList<>(list.size());
        for (int i = 0; i < list.size(); i++) {
            CompoundTag entry = list.getCompound(i);
            int category = entry.getInt("Category");
            candidates.add(
                    new SurveyTarget.Candidate(
                            OreIdentification.Source.byId(entry.getInt("Source")),
                            category < 0
                                    ? null
                                    : OreTraits.Category.values()[
                                            Math.min(
                                                    category,
                                                    OreTraits.Category.values().length - 1)],
                            entry.getString("Ore"),
                            entry.getDouble("P")));
        }
        double radiusX = tag.getDouble("RadiusX");
        double radiusZ = tag.getDouble("RadiusZ");
        if (!(radiusX > 0.0D && radiusZ > 0.0D)) {
            throw new IllegalArgumentException("Invalid survey target");
        }
        return new SurveyTarget(
                tag.getLong("Key"),
                tag.getDouble("X"),
                tag.getDouble("Y"),
                tag.getDouble("Z"),
                radiusX,
                radiusZ,
                tag.getDouble("Angle"),
                tag.getDouble("Thickness"),
                tag.getDouble("PositionError"),
                tag.getDouble("DepthError"),
                tag.getDouble("Intensity"),
                tag.getInt("Polarity"),
                tag.getInt("Level"),
                SurveyTarget.Detail.byId(tag.getInt("Detail")),
                candidates,
                tag.getDouble("Ore"));
    }

    /**
     * Stores intensity, not amplitude: {@code |a|/σ} at 1/64 resolution and σ on a log scale keep
     * every displayed figure exact to well under a percent in two bytes each.
     */
    private static CompoundTag saveMap(SurveyMap map) {
        int size = map.size();
        short[] snr = new short[size];
        short[] sigma = new short[size];
        short[] lateral = new short[size];
        for (int i = 0; i < size; i++) {
            if (!map.inside(i)) {
                continue;
            }
            snr[i] = clampShort(Math.round(map.amplitude()[i] / map.sigma()[i] * SNR_SCALE));
            sigma[i] =
                    clampShort(
                            Math.max(
                                    1L,
                                    Math.round(
                                            (Math.log(map.sigma()[i]) + SIGMA_LOG_OFFSET)
                                                    * SIGMA_LOG_SCALE)));
            lateral[i] = clampShort(Math.round(map.lateralSigma()[i] * LATERAL_SCALE));
        }
        CompoundTag tag = new CompoundTag();
        tag.putInt("Resolution", map.resolution());
        tag.putIntArray("Snr", pack(snr));
        tag.putIntArray("Sigma", pack(sigma));
        tag.putIntArray("Lateral", pack(lateral));
        tag.putIntArray("Depth", pack(map.depthY()));
        tag.putByteArray("Attenuation", map.attenuation().clone());
        SurveyMetrics m = map.metrics();
        tag.putInt("Geophones", m.geophones());
        tag.putLongArray(
                "Metrics",
                toBits(
                        new double[] {
                            m.effectiveReceivers(),
                            m.azimuthCoverage(),
                            m.velocityError(),
                            m.depthError(),
                            m.lateralAccuracy(),
                            m.noiseLevel(),
                            m.signalQuality(),
                            m.peakIntensity()
                        }));
        tag.putInt("Anomalies", m.anomalies());
        return tag;
    }

    private static SurveyMap loadMap(
            CompoundTag tag, SurveyMode mode, double centerX, double centerZ, double radius) {
        int resolution = tag.getInt("Resolution");
        if (resolution < 1 || resolution > 512) {
            throw new IllegalArgumentException("Invalid survey resolution");
        }
        int size = resolution * resolution;
        short[] snr = unpack(tag.getIntArray("Snr"), size);
        short[] sigmaCode = unpack(tag.getIntArray("Sigma"), size);
        short[] lateralCode = unpack(tag.getIntArray("Lateral"), size);
        short[] depth = unpack(tag.getIntArray("Depth"), size);
        byte[] attenuation = tag.getByteArray("Attenuation");
        if (attenuation.length != size) {
            throw new IllegalArgumentException("Invalid survey attenuation grid");
        }
        float[] amplitude = new float[size];
        float[] sigma = new float[size];
        float[] lateral = new float[size];
        for (int i = 0; i < size; i++) {
            if (sigmaCode[i] <= 0) {
                continue;
            }
            sigma[i] = (float) Math.exp(sigmaCode[i] / SIGMA_LOG_SCALE - SIGMA_LOG_OFFSET);
            amplitude[i] = (float) (snr[i] / SNR_SCALE * sigma[i]);
            lateral[i] = (float) (lateralCode[i] / LATERAL_SCALE);
        }
        double[] m = fromBits(tag.getLongArray("Metrics"));
        if (m.length != 8) {
            throw new IllegalArgumentException("Invalid survey metrics");
        }
        SurveyMetrics metrics =
                new SurveyMetrics(
                        tag.getInt("Geophones"),
                        m[0],
                        m[1],
                        m[2],
                        m[3],
                        m[4],
                        m[5],
                        m[6],
                        m[7],
                        tag.getInt("Anomalies"));
        return new SurveyMap(
                mode,
                centerX,
                centerZ,
                radius,
                resolution,
                amplitude,
                sigma,
                depth,
                attenuation.clone(),
                lateral,
                metrics);
    }

    private static short clampShort(long value) {
        return (short) Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, value));
    }

    static int[] pack(short[] values) {
        int[] packed = new int[(values.length + 1) / 2];
        for (int i = 0; i < values.length; i++) {
            int shift = (i & 1) * 16;
            packed[i >> 1] |= (values[i] & 0xFFFF) << shift;
        }
        return packed;
    }

    static short[] unpack(int[] packed, int size) {
        if (packed.length != (size + 1) / 2) {
            throw new IllegalArgumentException("Invalid packed survey grid");
        }
        short[] values = new short[size];
        for (int i = 0; i < size; i++) {
            values[i] = (short) (packed[i >> 1] >>> ((i & 1) * 16));
        }
        return values;
    }

    private static long[] toBits(double[] values) {
        long[] bits = new long[values.length];
        for (int i = 0; i < values.length; i++) {
            bits[i] = Double.doubleToLongBits(values[i]);
        }
        return bits;
    }

    private static double[] fromBits(long[] bits) {
        double[] values = new double[bits.length];
        for (int i = 0; i < bits.length; i++) {
            values[i] = Double.longBitsToDouble(bits[i]);
        }
        return values;
    }
}

package dev.world.geology.lidar;

import dev.world.geology.SurveyTarget;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreTraits;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.FriendlyByteBuf;

/**
 * Wire format of the map: a sample's position is a byte per axis at 1/8 block inside its {@link
 * LidarMap#BUCKET}-block bucket and its spread a byte on a two-slope scale (see {@link LidarWire}),
 * about twelve bytes per sample. Storage on disk is {@link LidarMapStorage}.
 */
public final class LidarCodec {
    public static final int MAXIMUM_BUCKET_SAMPLES = 65_536;
    public static final int MAXIMUM_OBJECTS = 2_048;
    public static final int MAXIMUM_COVERAGE = 64;

    private LidarCodec() {}

    static SurveyTarget.Candidate candidate(
            int source, int category, String oreId, double probability) {
        OreTraits.Category[] categories = OreTraits.Category.values();
        return new SurveyTarget.Candidate(
                OreIdentification.Source.byId(source),
                category < 0 ? null : categories[Math.min(category, categories.length - 1)],
                oreId,
                probability);
    }

    // ------------------------------------------------------------------------------------------
    // Wire

    public static void writeBucket(
            FriendlyByteBuf buffer, long key, int revision, List<LidarSample> samples) {
        buffer.writeLong(key);
        buffer.writeVarInt(revision);
        buffer.writeVarInt(samples.size());
        float ox = LidarMap.bucketX(key) * (float) LidarMap.BUCKET;
        float oy = LidarMap.bucketY(key) * (float) LidarMap.BUCKET;
        float oz = LidarMap.bucketZ(key) * (float) LidarMap.BUCKET;
        for (LidarSample s : samples) {
            buffer.writeByte(s.kind().ordinal());
            buffer.writeByte(LidarWire.offset(s.x() - ox));
            buffer.writeByte(LidarWire.offset(s.y() - oy));
            buffer.writeByte(LidarWire.offset(s.z() - oz));
            buffer.writeByte(LidarWire.spreadCode(s.spreadH()));
            buffer.writeByte(LidarWire.spreadCode(s.spreadV()));
            buffer.writeByte(Math.round(s.confidence() * 255.0F));
            buffer.writeByte(s.flags());
            buffer.writeMedium(s.color() & 0xFFFFFF);
            buffer.writeVarInt(Math.max(0, s.object()));
        }
    }

    public record WireBucket(long key, int revision, List<LidarSample> samples) {}

    public static WireBucket readBucket(FriendlyByteBuf buffer) {
        long key = buffer.readLong();
        int revision = buffer.readVarInt();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAXIMUM_BUCKET_SAMPLES) {
            throw new IllegalArgumentException("Invalid LiDAR bucket size " + count);
        }
        float ox = LidarMap.bucketX(key) * (float) LidarMap.BUCKET;
        float oy = LidarMap.bucketY(key) * (float) LidarMap.BUCKET;
        float oz = LidarMap.bucketZ(key) * (float) LidarMap.BUCKET;
        List<LidarSample> samples = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            LidarSample.Kind kind = LidarSample.Kind.byId(buffer.readUnsignedByte());
            float x = ox + LidarWire.position(buffer.readUnsignedByte());
            float y = oy + LidarWire.position(buffer.readUnsignedByte());
            float z = oz + LidarWire.position(buffer.readUnsignedByte());
            float spreadH = LidarWire.spread(buffer.readUnsignedByte());
            float spreadV = LidarWire.spread(buffer.readUnsignedByte());
            float confidence = buffer.readUnsignedByte() / 255.0F;
            int flags = buffer.readUnsignedByte();
            int color = buffer.readUnsignedMedium();
            int object = buffer.readVarInt();
            samples.add(
                    new LidarSample(
                            kind, x, y, z, spreadH, spreadV, confidence, color, object, flags));
        }
        return new WireBucket(key, revision, samples);
    }

    public static void writeObject(FriendlyByteBuf buffer, LidarObject o) {
        buffer.writeVarInt(o.id());
        buffer.writeByte(o.kind().ordinal());
        buffer.writeFloat(o.x());
        buffer.writeFloat(o.y());
        buffer.writeFloat(o.z());
        buffer.writeFloat(o.sizeX());
        buffer.writeFloat(o.sizeY());
        buffer.writeFloat(o.sizeZ());
        buffer.writeFloat(o.angle());
        buffer.writeFloat(o.positionError());
        buffer.writeFloat(o.depthError());
        buffer.writeFloat(o.intensity());
        buffer.writeFloat(o.confidence());
        buffer.writeByte(o.detail());
        buffer.writeByte(o.level());
        buffer.writeByte(o.shape());
        buffer.writeFloat(o.volume());
        buffer.writeByte(o.fill());
        buffer.writeVarInt(o.links());
        buffer.writeVarInt(o.suspectedLinks());
        buffer.writeVarInt(o.surveys());
        buffer.writeVarLong(o.time());
        int count = Math.min(LidarObject.MAXIMUM_CANDIDATES, o.candidates().size());
        buffer.writeVarInt(count);
        for (int i = 0; i < count; i++) {
            SurveyTarget.Candidate candidate = o.candidates().get(i);
            buffer.writeByte(candidate.source().ordinal());
            buffer.writeByte(candidate.category() == null ? -1 : candidate.category().ordinal());
            buffer.writeUtf(candidate.oreId(), 256);
            buffer.writeFloat((float) candidate.probability());
        }
    }

    public static LidarObject readObject(FriendlyByteBuf buffer) {
        int id = buffer.readVarInt();
        LidarObject.Kind kind = LidarObject.Kind.byId(buffer.readUnsignedByte());
        float x = buffer.readFloat();
        float y = buffer.readFloat();
        float z = buffer.readFloat();
        float sizeX = buffer.readFloat();
        float sizeY = buffer.readFloat();
        float sizeZ = buffer.readFloat();
        float angle = buffer.readFloat();
        float positionError = buffer.readFloat();
        float depthError = buffer.readFloat();
        float intensity = buffer.readFloat();
        float confidence = buffer.readFloat();
        int detail = buffer.readByte();
        int level = buffer.readByte();
        int shape = buffer.readByte();
        float volume = buffer.readFloat();
        int fill = buffer.readByte();
        int links = buffer.readVarInt();
        int suspected = buffer.readVarInt();
        int surveys = buffer.readVarInt();
        long time = buffer.readVarLong();
        int count = buffer.readVarInt();
        if (count < 0 || count > LidarObject.MAXIMUM_CANDIDATES) {
            throw new IllegalArgumentException("Too many object candidates");
        }
        List<SurveyTarget.Candidate> candidates = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            candidates.add(
                    candidate(
                            buffer.readByte(),
                            buffer.readByte(),
                            buffer.readUtf(256),
                            buffer.readFloat()));
        }
        return new LidarObject(
                id,
                kind,
                x,
                y,
                z,
                sizeX,
                sizeY,
                sizeZ,
                angle,
                positionError,
                depthError,
                intensity,
                confidence,
                detail,
                level,
                shape,
                volume,
                fill,
                links,
                suspected,
                surveys,
                time,
                candidates);
    }

    public static void writeCoverage(FriendlyByteBuf buffer, LidarCoverage c) {
        buffer.writeFloat(c.x());
        buffer.writeFloat(c.z());
        buffer.writeFloat(c.radius());
        buffer.writeFloat(c.topY());
        buffer.writeFloat(c.bottomY());
        buffer.writeBoolean(c.cave());
        buffer.writeByte(c.level());
        buffer.writeVarLong(c.time());
    }

    public static LidarCoverage readCoverage(FriendlyByteBuf buffer) {
        return new LidarCoverage(
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readFloat(),
                buffer.readBoolean(),
                buffer.readByte(),
                buffer.readVarLong());
    }
}

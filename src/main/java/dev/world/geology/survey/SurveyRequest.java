package dev.world.geology.survey;

import dev.world.geology.SeedHash;
import dev.world.geology.SurveyMode;
import java.util.List;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/**
 * Everything that defines one survey's configuration. Two requests with the same fields produce the
 * same measurement, errors included.
 *
 * @param geophones geophones used, sorted by position
 * @param environment noise multiplier (weather, ground coupling), rounded so tiny weather jitter
 *     does not make an identical setup count as new
 * @param level installed upgrade level
 * @param analyzer installed cave analyser level; it maps voids but does not change the seismic
 *     record, so it is not part of {@link #configHash}
 */
public record SurveyRequest(
        UUID stationId,
        ResourceLocation dimension,
        long worldSeed,
        SurveyMode mode,
        BlockPos station,
        int radius,
        List<BlockPos> geophones,
        double environment,
        boolean wellTie,
        int level,
        int analyzer) {
    public SurveyRequest(
            UUID stationId,
            ResourceLocation dimension,
            long worldSeed,
            SurveyMode mode,
            BlockPos station,
            int radius,
            List<BlockPos> geophones,
            double environment,
            boolean wellTie,
            int level) {
        this(
                stationId,
                dimension,
                worldSeed,
                mode,
                station,
                radius,
                geophones,
                environment,
                wellTie,
                level,
                0);
    }

    public SurveyRequest {
        station = station.immutable();
        geophones = geophones.stream().map(BlockPos::immutable).sorted().toList();
        environment = Math.round(environment * 20.0D) / 20.0D;
    }

    public long configHash() {
        long hash = SeedHash.combine(worldSeed, SeedHash.hash(dimension.toString()));
        hash = SeedHash.combine(hash, mode.ordinal(), station.asLong(), radius);
        for (BlockPos geophone : geophones) {
            hash = SeedHash.combine(hash, geophone.asLong());
        }
        hash = SeedHash.combine(hash, Math.round(environment * 20.0D), wellTie ? 1L : 0L);
        return SeedHash.combine(hash, level);
    }

    public long noiseSeed() {
        return SeedHash.combine(worldSeed, configHash());
    }

    /** Identity of the whole job: the seismic configuration plus the analyser. */
    public long jobHash() {
        return SeedHash.combine(configHash(), 0x43415645L, analyzer);
    }

    /** Seed of the analyser's errors: deterministic per configuration and analyser level. */
    public long caveSeed() {
        return SeedHash.combine(noiseSeed(), 0x414E414CL, analyzer);
    }
}

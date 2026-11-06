package dev.world.geology.station;

import dev.network.SeismicStationRequestPayload;
import dev.network.SeismicSurveyListPayload;
import dev.network.SeismicSurveyMapPayload;
import dev.world.geology.Borehole;
import dev.world.geology.GeologySavedData;
import dev.world.geology.NaturalGeology;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.SurveyArchive;
import dev.world.geology.SurveyMap;
import dev.world.geology.SurveyRecord;
import dev.world.geology.SurveyTarget;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreSignature;
import dev.world.geology.ore.OreSignatureTable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server side of the station screen: what is sent to players who have a station's menu open. Only
 * processed measurements, interpreted targets and the player's own equipment leave the server.
 */
public final class SeismicStationViewers {
    /** Signal-to-noise ratio the ore table's identification chances are quoted for. */
    public static final double TABLE_SNR = 8.0D;

    /** Identification chances per ore table and level; tables are replaced, never mutated. */
    private static final Map<List<OreSignature>, double[][]> IDENTIFICATION = new WeakHashMap<>();

    private SeismicStationViewers() {}

    public static void surveyFinished(ServerLevel level, SeismicStationBlockEntity station) {
        for (ServerPlayer player : level.players()) {
            if (player.containerMenu instanceof SeismicStationMenu menu
                    && menu.stationPos().equals(station.getBlockPos())) {
                sendSurveyList(player, station);
            }
        }
    }

    public static void sendSurveyList(ServerPlayer player, SeismicStationBlockEntity station) {
        ServerLevel level = (ServerLevel) station.getLevel();
        List<SurveyRecord> records =
                SurveyArchive.get(level.getServer(), station.stationId()).records();
        List<SeismicSurveyListPayload.Entry> entries = new ArrayList<>();
        for (SurveyRecord record : records) {
            if (entries.size() >= SeismicSurveyListPayload.MAXIMUM_ENTRIES) {
                break;
            }
            if (!record.dimension().equals(level.dimension().location())) {
                continue;
            }
            entries.add(
                    new SeismicSurveyListPayload.Entry(
                            record.id(),
                            record.mode().ordinal(),
                            record.gameTime(),
                            (int) Math.floor(record.centerX()),
                            (int) Math.floor(record.centerZ()),
                            (int) Math.round(record.radius()),
                            record.geophoneX().length,
                            (float) record.map().metrics().signalQuality(),
                            record.targets().size(),
                            (float) record.map().metrics().peakIntensity(),
                            record.level()));
        }
        int radius = station.regionalRadius();
        BlockPos site = station.currentSite();
        GeologySavedData data = GeologySavedData.get(level);
        List<BlockPos> geophones = data.geophonesWithin(site, radius);
        if (geophones.size() > SeismicSurveyListPayload.MAXIMUM_MARKS) {
            geophones = geophones.subList(0, SeismicSurveyListPayload.MAXIMUM_MARKS);
        }
        List<SeismicSurveyListPayload.Hole> holes = new ArrayList<>();
        for (Borehole borehole : data.boreholesWithin(site, radius)) {
            if (holes.size() >= SeismicSurveyListPayload.MAXIMUM_MARKS) {
                break;
            }
            holes.add(
                    new SeismicSurveyListPayload.Hole(
                            borehole.top(),
                            borehole.bottomY(),
                            Optional.ofNullable(borehole.ore()),
                            borehole.oreTopY(),
                            borehole.oreBottomY(),
                            borehole.localUnits(),
                            borehole.industrial()));
        }
        PacketDistributor.sendToPlayer(
                player,
                new SeismicSurveyListPayload(
                        station.getBlockPos(),
                        entries,
                        geophones,
                        holes,
                        oreTable(level.dimension().location(), station.scannerLevel())));
    }

    /** The dimension's ore table with the station's chances of naming each ore. */
    public static List<SeismicSurveyListPayload.OreRow> oreTable(
            ResourceLocation dimension, int level) {
        List<OreSignature> table = OreSignatureTable.forDimension(dimension);
        double[][] chances = identificationChances(table, dimension);
        int current = ScannerUpgrade.clamp(level);
        List<SeismicSurveyListPayload.OreRow> rows = new ArrayList<>();
        for (int i = 0; i < table.size() && i < SeismicSurveyListPayload.MAXIMUM_ORES; i++) {
            OreSignature signature = table.get(i);
            boolean known = signature.generationKnown();
            rows.add(
                    new SeismicSurveyListPayload.OreRow(
                            signature.oreId(),
                            signature.traits().category().ordinal(),
                            (float) signature.prevalence(),
                            known ? signature.traits().minY() : 0,
                            known ? signature.traits().maxY() : 0,
                            known,
                            (float)
                                    (ScannerUpgrade.namesOres(current)
                                            ? chances[current][i]
                                            : 0.0D),
                            (float) chances[ScannerUpgrade.MAX_LEVEL][i]));
        }
        return rows;
    }

    private static double[][] identificationChances(
            List<OreSignature> table, ResourceLocation dimension) {
        synchronized (IDENTIFICATION) {
            double[][] cached = IDENTIFICATION.get(table);
            if (cached != null) {
                return cached;
            }
            NaturalGeology.Setting setting =
                    NaturalGeology.Setting.forDimension(dimension.toString());
            double[][] chances = new double[ScannerUpgrade.MAX_LEVEL + 1][];
            for (int level = 0; level <= ScannerUpgrade.MAX_LEVEL; level++) {
                chances[level] =
                        OreIdentification.identificationProbabilities(
                                table,
                                y -> NaturalGeology.host(setting, y),
                                setting,
                                OreIdentification.Precision.survey(level, TABLE_SNR));
            }
            IDENTIFICATION.put(table, chances);
            return chances;
        }
    }

    /** Handles a validated request from the station screen. */
    public static void handle(ServerPlayer player, SeismicStationRequestPayload request) {
        if (!(player.containerMenu instanceof SeismicStationMenu menu)
                || !menu.stationPos().equals(request.station())
                || !menu.stillValid(player)) {
            return;
        }
        SeismicStationBlockEntity station = menu.station(player);
        if (station == null) {
            return;
        }
        ServerLevel level = (ServerLevel) station.getLevel();
        SurveyArchive archive = SurveyArchive.get(level.getServer(), station.stationId());
        SurveyRecord record = archive.byId(request.surveyId());
        if (record == null || !record.dimension().equals(level.dimension().location())) {
            return;
        }
        PacketDistributor.sendToPlayer(
                player, mapPayload(station.getBlockPos(), record, record.map(), record.targets()));
    }

    public static SeismicSurveyMapPayload mapPayload(
            BlockPos station, SurveyRecord record, SurveyMap map, List<SurveyTarget> targets) {
        var metrics = map.metrics();
        float[] values = new float[SeismicSurveyMapPayload.METRIC_COUNT];
        values[SeismicSurveyMapPayload.METRIC_RECEIVERS] = (float) metrics.effectiveReceivers();
        values[SeismicSurveyMapPayload.METRIC_COVERAGE] = (float) metrics.azimuthCoverage();
        values[SeismicSurveyMapPayload.METRIC_VELOCITY_ERROR] = (float) metrics.velocityError();
        values[SeismicSurveyMapPayload.METRIC_DEPTH_ERROR] = (float) metrics.depthError();
        values[SeismicSurveyMapPayload.METRIC_LATERAL] = (float) metrics.lateralAccuracy();
        values[SeismicSurveyMapPayload.METRIC_NOISE] = (float) metrics.noiseLevel();
        values[SeismicSurveyMapPayload.METRIC_QUALITY] = (float) metrics.signalQuality();
        values[SeismicSurveyMapPayload.METRIC_PEAK] = (float) metrics.peakIntensity();
        values[SeismicSurveyMapPayload.METRIC_GEOPHONES] = metrics.geophones();
        List<BlockPos> geophones = new ArrayList<>();
        for (int i = 0; i < record.geophoneX().length; i++) {
            geophones.add(
                    BlockPos.containing(
                            record.geophoneX()[i], record.datumY(), record.geophoneZ()[i]));
        }
        List<SeismicSurveyMapPayload.Target> sent = new ArrayList<>();
        for (SurveyTarget target : targets) {
            if (sent.size() >= SeismicSurveyMapPayload.MAXIMUM_TARGETS) {
                break;
            }
            sent.add(toPayload(target));
        }
        return new SeismicSurveyMapPayload(
                station,
                record.id(),
                record.mode().ordinal(),
                record.level(),
                map.centerX(),
                map.centerZ(),
                map.radius(),
                (int) Math.round(record.datumY()),
                record.minY(),
                map.resolution(),
                values,
                geophones,
                sent);
    }

    static SeismicSurveyMapPayload.Target toPayload(SurveyTarget target) {
        List<SeismicSurveyMapPayload.Candidate> candidates = new ArrayList<>();
        for (SurveyTarget.Candidate candidate : target.candidates()) {
            if (candidates.size() >= SeismicSurveyMapPayload.MAXIMUM_CANDIDATES) {
                break;
            }
            candidates.add(
                    new SeismicSurveyMapPayload.Candidate(
                            candidate.source().ordinal(),
                            candidate.category() == null ? -1 : candidate.category().ordinal(),
                            candidate.oreId(),
                            (float) candidate.probability()));
        }
        return new SeismicSurveyMapPayload.Target(
                (float) target.x(),
                (float) target.y(),
                (float) target.z(),
                (float) target.radiusX(),
                (float) target.radiusZ(),
                (float) target.angle(),
                (float) target.thickness(),
                (float) target.positionError(),
                (float) target.depthError(),
                (float) target.intensity(),
                target.polarity(),
                target.level(),
                target.detail().ordinal(),
                (float) target.oreProbability(),
                List.copyOf(candidates));
    }
}

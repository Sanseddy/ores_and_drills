package dev.world.geology;

import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreSignature;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Turns a processed survey into {@link SurveyTarget}s: each picked anomaly is traced to the model
 * element that produced it, measured with the errors of the installed equipment and identified
 * against the ore table. What the result may reveal grows with the upgrade level — ore classes,
 * then names; a footprint, then an outline, then the deposit's own lens — but every value stays a
 * measurement: positions carry the survey's positioning error and probabilities its noise.
 */
public final class SurveyInterpretation {
    public static final int MAXIMUM_CANDIDATES = 5;
    public static final double MINIMUM_PROBABILITY = 0.02D;

    private static final long IDENTIFICATION_SALT = 0x4944454E54L;

    private SurveyInterpretation() {}

    /** The ore of a deposit, by the key its bodies share. */
    public record DepositOre(String oreId, RockProperties mineral) {}

    public static List<SurveyTarget> interpret(
            SurveyInput input,
            SeismicForwardModel.Output output,
            Map<Long, DepositOre> deposits,
            List<OreSignature> table) {
        SurveyMap map = output.map();
        int level = input.level();
        double velocityError = SeismicForwardModel.velocityError(input);
        boolean depthKnown = ScannerUpgrade.resolvesDepth(input.mode(), level);
        double depthError = map.metrics() == null ? 0.0D : map.metrics().depthError();
        List<SurveyTarget> targets = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        // The cap applies to targets, not peaks: a large zone raises several peaks that collapse
        // into one target and must not crowd weaker sources out of the list.
        for (SurveyAnomalies.Anomaly anomaly : SurveyAnomalies.find(map, null, Integer.MAX_VALUE)) {
            if (targets.size() >= SurveyAnomalies.MAXIMUM_ANOMALIES) {
                break;
            }
            int index = map.indexAt(anomaly.x(), anomaly.z());
            if (index < 0) {
                continue;
            }
            int source = output.sources()[index];
            SubsurfaceBody body = source >= 0 ? input.bodies().get(source) : null;
            long key = body == null ? 0L : body.key();
            if (key != 0L && !seen.add(key)) {
                // Several peaks of one large zone: the strongest already describes it.
                continue;
            }
            double measuredY = map.depthY()[index];
            double[] truth = truth(input, body, source, deposits, anomaly, measuredY);
            OreIdentification.Precision precision =
                    OreIdentification.Precision.survey(level, anomaly.intensity());
            OreIdentification.Observation observation =
                    OreIdentification.measure(
                            truth,
                            measuredY,
                            depthKnown,
                            precision,
                            SeedHash.combine(
                                    input.noiseSeed(),
                                    key != 0L ? key : index,
                                    IDENTIFICATION_SALT));
            OreIdentification.Result result =
                    OreIdentification.identify(
                            observation,
                            table,
                            y -> NaturalGeology.host(input.setting(), y),
                            input.setting(),
                            precision,
                            true);
            if (!ScannerUpgrade.namesOres(level)) {
                result = result.byCategory();
            }
            result = result.top(MAXIMUM_CANDIDATES, MINIMUM_PROBABILITY);
            List<SurveyTarget.Candidate> candidates = new ArrayList<>();
            for (OreIdentification.Candidate candidate : result.candidates()) {
                candidates.add(SurveyTarget.Candidate.of(candidate));
            }
            targets.add(
                    geometry(
                            input,
                            anomaly,
                            body,
                            key,
                            depthKnown ? measuredY : Double.NaN,
                            depthError,
                            velocityError,
                            candidates,
                            result.oreProbability()));
        }
        return List.copyOf(targets);
    }

    /** Intrinsic contrast and absorption of what really caused the anomaly. */
    private static double[] truth(
            SurveyInput input,
            SubsurfaceBody body,
            int source,
            Map<Long, DepositOre> deposits,
            SurveyAnomalies.Anomaly anomaly,
            double measuredY) {
        if (body != null) {
            boolean ore =
                    body.kind() == SubsurfaceBody.Kind.ORE_BODY
                            || body.kind() == SubsurfaceBody.Kind.MINERALIZED_HALO;
            DepositOre deposit = ore ? deposits.get(body.key()) : null;
            if (deposit != null) {
                return OreIdentification.expectedOre(
                        NaturalGeology.host(input.setting(), body.y()), deposit.mineral());
            }
            return OreIdentification.truthOf(body);
        }
        if (source == SeismicForwardModel.SOURCE_CAVITY) {
            return OreIdentification.cavityTruth();
        }
        // Noise or the sum of many weak contributions: no intrinsic contrast to speak of.
        double sign = anomaly.polarity() != 0 ? anomaly.polarity() : 1.0D;
        return new double[] {0.004D * sign, 0.0D};
    }

    private static SurveyTarget geometry(
            SurveyInput input,
            SurveyAnomalies.Anomaly anomaly,
            SubsurfaceBody body,
            long key,
            double measuredY,
            double relativeDepthError,
            double velocityError,
            List<SurveyTarget.Candidate> candidates,
            double oreProbability) {
        int level = input.level();
        double positionError =
                anomaly.positionError()
                        * SeismicForwardModel.POSITION_ERROR_SCALE
                        * ScannerUpgrade.positionFactor(level);
        double x = anomaly.x();
        double z = anomaly.z();
        double y = measuredY;
        double radiusX = Math.max(1.0D, anomaly.radius());
        double radiusZ = radiusX;
        double angle = 0.0D;
        double thickness = Double.NaN;
        SurveyTarget.Detail detail = SurveyTarget.Detail.FOOTPRINT;
        if (body != null && ScannerUpgrade.resolvesOutline(level)) {
            SubsurfaceBody shape = body;
            detail = SurveyTarget.Detail.OUTLINE;
            if (ScannerUpgrade.resolvesCore(level)) {
                SubsurfaceBody core = coreOf(input, body);
                if (core != null) {
                    shape = core;
                    detail = SurveyTarget.Detail.CORE;
                }
            }
            SeismicForwardModel.BodyView view =
                    SeismicForwardModel.view(input, shape, velocityError);
            // The outline is only as sharp as the survey's lateral resolution.
            double blur = positionError * (detail == SurveyTarget.Detail.CORE ? 0.5D : 1.0D);
            x = view.x();
            z = view.z();
            radiusX = Math.sqrt(shape.radiusX() * shape.radiusX() + blur * blur);
            radiusZ = Math.sqrt(shape.radiusZ() * shape.radiusZ() + blur * blur);
            angle = shape.angle();
            if (Double.isFinite(measuredY)) {
                y = view.centerY();
            }
            if (detail == SurveyTarget.Detail.CORE) {
                thickness = Math.max(1.0D, view.topY() - view.bottomY());
            }
        }
        double depthError =
                Double.isFinite(y)
                        ? Math.max(1.0D, input.datumY() - y) * Math.max(0.0D, relativeDepthError)
                        : Double.NaN;
        return new SurveyTarget(
                key,
                x,
                y,
                z,
                radiusX,
                radiusZ,
                angle,
                thickness,
                positionError,
                depthError,
                anomaly.intensity(),
                anomaly.polarity(),
                level,
                detail,
                candidates,
                oreProbability);
    }

    /**
     * The zone whose response the map's points of {@code target} belong to. A resolved lens is far
     * smaller than the altered halo that makes most of its anomaly, so its points are matched
     * against the halo's outline as an {@link SurveyTarget.Detail#OUTLINE} survey would show it;
     * every other target is its own zone.
     */
    public static SurveyTarget responseZone(SurveyInput input, SurveyTarget target) {
        if (target.detail() != SurveyTarget.Detail.CORE || target.key() == 0L) {
            return target;
        }
        SubsurfaceBody halo = null;
        for (SubsurfaceBody body : input.bodies()) {
            if (body.key() == target.key() && body.kind() == SubsurfaceBody.Kind.MINERALIZED_HALO) {
                halo = body;
                break;
            }
        }
        if (halo == null) {
            return target;
        }
        SeismicForwardModel.BodyView view =
                SeismicForwardModel.view(input, halo, SeismicForwardModel.velocityError(input));
        double blur = target.positionError();
        return new SurveyTarget(
                target.key(),
                view.x(),
                target.depthKnown() ? view.centerY() : Double.NaN,
                view.z(),
                Math.sqrt(halo.radiusX() * halo.radiusX() + blur * blur),
                Math.sqrt(halo.radiusZ() * halo.radiusZ() + blur * blur),
                halo.angle(),
                Math.max(1.0D, view.topY() - view.bottomY()),
                target.positionError(),
                target.depthError(),
                target.intensity(),
                target.polarity(),
                target.level(),
                SurveyTarget.Detail.OUTLINE,
                target.candidates(),
                target.oreProbability());
    }

    /** The deposit lens that shares a halo's key, if the model has one. */
    private static SubsurfaceBody coreOf(SurveyInput input, SubsurfaceBody body) {
        if (body.kind() == SubsurfaceBody.Kind.ORE_BODY) {
            return body;
        }
        if (body.kind() != SubsurfaceBody.Kind.MINERALIZED_HALO) {
            return null;
        }
        for (SubsurfaceBody other : input.bodies()) {
            if (other.key() == body.key() && other.kind() == SubsurfaceBody.Kind.ORE_BODY) {
                return other;
            }
        }
        return null;
    }
}

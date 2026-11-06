package dev.world.geology.lidar;

import dev.world.geology.SurveyTarget;
import java.util.List;
import java.util.function.ToDoubleFunction;

/**
 * Refines a stored target with a new measurement of the same structure. Independent measurements
 * (different configurations) are averaged by inverse variance, so the position error shrinks, and
 * their identifications are fused; an identical repeat simply replaces the old reading. Older
 * readings weigh less as they age, which lets a re-survey follow a deposit being mined out.
 */
public final class LidarObjectMerge {
    private LidarObjectMerge() {}

    public static LidarObject target(
            LidarObject old,
            long oldConfig,
            LidarObject fresh,
            long config,
            long time,
            long staleTicks,
            ToDoubleFunction<SurveyTarget.Candidate> prior) {
        if (!CandidateFusion.independent(oldConfig, config)) {
            return copy(fresh, old.surveys(), time);
        }
        double decay = LidarMap.decay(time - old.time(), staleTicks);
        double wa = decay / Math.max(0.25D, old.positionError() * old.positionError());
        double wb = 1.0D / Math.max(0.25D, fresh.positionError() * fresh.positionError());
        float x = (float) ((old.x() * wa + fresh.x() * wb) / (wa + wb));
        float z = (float) ((old.z() * wa + fresh.z() * wb) / (wa + wb));
        float positionError = (float) (1.0D / Math.sqrt(wa + wb));

        float y;
        float depthError;
        if (old.depthKnown()
                && fresh.depthKnown()
                && Float.isFinite(old.depthError())
                && Float.isFinite(fresh.depthError())) {
            double da = decay / Math.max(0.25D, old.depthError() * old.depthError());
            double db = 1.0D / Math.max(0.25D, fresh.depthError() * fresh.depthError());
            y = (float) ((old.y() * da + fresh.y() * db) / (da + db));
            depthError = (float) (1.0D / Math.sqrt(da + db));
        } else if (fresh.depthKnown()) {
            y = fresh.y();
            depthError = fresh.depthError();
        } else {
            y = old.y();
            depthError = old.depthError();
        }

        LidarObject shape =
                fresh.detail() > old.detail()
                                || (fresh.detail() == old.detail() && fresh.level() >= old.level())
                        ? fresh
                        : old;
        List<SurveyTarget.Candidate> candidates;
        if (CandidateFusion.comparable(old.candidates(), fresh.candidates())) {
            candidates =
                    CandidateFusion.fuse(
                            old.candidates(),
                            fresh.candidates(),
                            prior,
                            LidarObject.MAXIMUM_CANDIDATES,
                            0.02D);
        } else {
            candidates = fresh.level() >= old.level() ? fresh.candidates() : old.candidates();
        }
        double intensity =
                Math.sqrt(
                        old.intensity() * old.intensity() * decay
                                + fresh.intensity() * fresh.intensity());
        return new LidarObject(
                old.id(),
                LidarObject.Kind.TARGET,
                x,
                y,
                z,
                shape.sizeX(),
                shape.sizeY(),
                shape.sizeZ(),
                shape.angle(),
                positionError,
                depthError,
                (float) intensity,
                (float) LidarMap.confidence(intensity),
                Math.max(old.detail(), fresh.detail()),
                Math.max(old.level(), fresh.level()),
                -1,
                0.0F,
                0,
                0,
                0,
                old.surveys() + 1,
                time,
                candidates);
    }

    private static LidarObject copy(LidarObject fresh, int surveys, long time) {
        return new LidarObject(
                fresh.id(),
                fresh.kind(),
                fresh.x(),
                fresh.y(),
                fresh.z(),
                fresh.sizeX(),
                fresh.sizeY(),
                fresh.sizeZ(),
                fresh.angle(),
                fresh.positionError(),
                fresh.depthError(),
                fresh.intensity(),
                fresh.confidence(),
                fresh.detail(),
                fresh.level(),
                fresh.shape(),
                fresh.volume(),
                fresh.fill(),
                fresh.links(),
                fresh.suspectedLinks(),
                Math.max(1, surveys),
                time,
                fresh.candidates());
    }
}

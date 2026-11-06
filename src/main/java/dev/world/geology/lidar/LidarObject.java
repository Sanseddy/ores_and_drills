package dev.world.geology.lidar;

import dev.world.geology.SurveyTarget;
import java.util.List;

/**
 * An interpreted structure of the map: a seismic target, a mapped cave or a borehole finding. All
 * figures are estimates with their errors; {@link #positionError} and {@link #depthError} say how
 * far the true position may lie, which is why coordinates of such objects are shown as approximate.
 *
 * @param id stable id within its map
 * @param y estimated centre height, NaN when the depth was not resolved
 * @param sizeX full extent along the object's own X axis (after {@link #angle})
 * @param sizeY full vertical extent, NaN when not resolved
 * @param angle rotation of the horizontal axes, radians
 * @param intensity signal-to-noise ratio of a seismic target; 0 for others
 * @param confidence 0–1 overall certainty that the object exists as shown
 * @param detail {@link SurveyTarget.Detail} ordinal, or -1
 * @param level scanner (targets) or analyser (caves) level of the best measurement
 * @param shape {@code CaveAnalysis.Shape} ordinal for caves, else -1
 * @param volume estimated open volume of a cave in cubic blocks, else 0
 * @param fill {@code CaveAnalysis} fill code of a cave, else 0
 * @param links confirmed connections of a cave
 * @param suspectedLinks unchecked connections of a cave
 * @param surveys how many independent measurements went into the object
 * @param time game time of the latest measurement
 */
public record LidarObject(
        int id,
        Kind kind,
        float x,
        float y,
        float z,
        float sizeX,
        float sizeY,
        float sizeZ,
        float angle,
        float positionError,
        float depthError,
        float intensity,
        float confidence,
        int detail,
        int level,
        int shape,
        float volume,
        int fill,
        int links,
        int suspectedLinks,
        int surveys,
        long time,
        List<SurveyTarget.Candidate> candidates) {
    public static final int MAXIMUM_CANDIDATES = 5;

    public enum Kind {
        /** A seismic target: deposit, natural structure or anomaly, see the candidates. */
        TARGET,
        /** A cave the analyser mapped. */
        CAVE,
        /** Ore a borehole intersected: position exact, identity from the core analysis. */
        CORE_ORE,
        /** Voids the drill's analyser found beside its hole. */
        CORE_VOID;

        public boolean cave() {
            return this == CAVE || this == CORE_VOID;
        }

        public static Kind byId(int id) {
            Kind[] values = values();
            return id >= 0 && id < values.length ? values[id] : TARGET;
        }
    }

    public LidarObject {
        candidates = List.copyOf(candidates);
        confidence = Math.max(0.0F, Math.min(1.0F, confidence));
    }

    public boolean depthKnown() {
        return Float.isFinite(y);
    }

    /** Whether the coordinates are uncertain by more than a block. */
    public boolean approximate() {
        return !depthKnown() || positionError > 1.0F || !(depthError <= 1.0F);
    }

    public LidarObject withId(int newId) {
        return new LidarObject(
                newId,
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
                suspectedLinks,
                surveys,
                time,
                candidates);
    }

    public LidarObject withConfidence(float newConfidence) {
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
                newConfidence,
                detail,
                level,
                shape,
                volume,
                fill,
                links,
                suspectedLinks,
                surveys,
                time,
                candidates);
    }

    public LidarObject withLinks(int confirmed, int suspected) {
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
                confirmed,
                suspected,
                surveys,
                time,
                candidates);
    }
}

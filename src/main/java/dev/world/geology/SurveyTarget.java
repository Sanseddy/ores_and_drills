package dev.world.geology;

import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreTraits;
import java.util.List;

/**
 * One interpreted anomaly: where the survey places its source, what shape it resolves and what the
 * source probably is. Every figure is an estimate with the survey's errors in it; the true body and
 * deposit stay on the server.
 *
 * @param key identity of the source for merging repeated surveys; never sent to clients
 * @param y estimated centre height, or NaN when the survey does not resolve depth
 * @param thickness estimated vertical extent, or NaN when not resolved
 * @param detail what the shape stands for, see {@link Detail}
 * @param candidates most probable explanations, best first
 * @param oreProbability total probability that the source is ore
 */
public record SurveyTarget(
        long key,
        double x,
        double y,
        double z,
        double radiusX,
        double radiusZ,
        double angle,
        double thickness,
        double positionError,
        double depthError,
        double intensity,
        int polarity,
        int level,
        Detail detail,
        List<Candidate> candidates,
        double oreProbability) {
    /** How much of the source's geometry the survey resolved. */
    public enum Detail {
        /** Only the anomaly's footprint: a circle of the area above half its peak. */
        FOOTPRINT,
        /** Outline and orientation of the reflecting zone (for a deposit, its altered halo). */
        OUTLINE,
        /** The deposit's own lens inside its halo, with thickness. */
        CORE;

        public static Detail byId(int id) {
            Detail[] values = values();
            return id >= 0 && id < values.length ? values[id] : FOOTPRINT;
        }
    }

    /**
     * One explanation.
     *
     * @param source kind of source
     * @param category ore class, or null for natural sources
     * @param oreId block id of the ore, or empty when only the class is known
     */
    public record Candidate(
            OreIdentification.Source source,
            OreTraits.Category category,
            String oreId,
            double probability) {
        public Candidate {
            oreId = oreId == null ? "" : oreId;
        }

        public static Candidate of(OreIdentification.Candidate candidate) {
            return new Candidate(
                    candidate.source(),
                    candidate.category(),
                    candidate.oreId(),
                    candidate.probability());
        }
    }

    public SurveyTarget {
        candidates = List.copyOf(candidates);
    }

    public boolean depthKnown() {
        return Double.isFinite(y);
    }
}

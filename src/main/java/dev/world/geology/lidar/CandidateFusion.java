package dev.world.geology.lidar;

import dev.world.geology.SurveyTarget;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Combines the identifications of two independent measurements of one structure. Each posterior
 * already contains the prior once, so the combination is p ∝ p₁·p₂ / prior. Candidates a list left
 * out (below its reporting threshold) count with the floor probability. Only measurements with
 * different survey configurations are independent; an identical repeat carries the same errors and
 * must not be fused, see {@link #independent}.
 */
public final class CandidateFusion {
    /** Probability given to a candidate a list did not report. */
    public static final double FLOOR = 0.01D;

    private CandidateFusion() {}

    public static boolean independent(long firstConfig, long secondConfig) {
        return firstConfig != secondConfig;
    }

    /**
     * Whether both lists name ores at the same granularity. Lists from a scanner that only knows
     * ore classes are not fused with lists naming ores; the finer one is kept instead.
     */
    public static boolean comparable(
            List<SurveyTarget.Candidate> first, List<SurveyTarget.Candidate> second) {
        return named(first) == named(second);
    }

    private static boolean named(List<SurveyTarget.Candidate> candidates) {
        for (SurveyTarget.Candidate candidate : candidates) {
            if (!candidate.oreId().isEmpty()) {
                return true;
            }
        }
        return false;
    }

    /**
     * @param prior prior probability of a candidate in the scanner's model; must be positive
     * @param limit most candidates kept
     * @param minimum smallest probability kept
     */
    public static List<SurveyTarget.Candidate> fuse(
            List<SurveyTarget.Candidate> first,
            List<SurveyTarget.Candidate> second,
            ToDoubleFunction<SurveyTarget.Candidate> prior,
            int limit,
            double minimum) {
        Map<String, SurveyTarget.Candidate> keys = new LinkedHashMap<>();
        Map<String, Double> p1 = new LinkedHashMap<>();
        Map<String, Double> p2 = new LinkedHashMap<>();
        for (SurveyTarget.Candidate candidate : first) {
            keys.putIfAbsent(key(candidate), candidate);
            p1.merge(key(candidate), candidate.probability(), Double::sum);
        }
        for (SurveyTarget.Candidate candidate : second) {
            keys.putIfAbsent(key(candidate), candidate);
            p2.merge(key(candidate), candidate.probability(), Double::sum);
        }
        Map<String, Double> combined = new LinkedHashMap<>();
        double total = 0.0D;
        for (Map.Entry<String, SurveyTarget.Candidate> entry : keys.entrySet()) {
            double a = Math.max(FLOOR, p1.getOrDefault(entry.getKey(), FLOOR));
            double b = Math.max(FLOOR, p2.getOrDefault(entry.getKey(), FLOOR));
            double value = a * b / Math.max(1.0E-6D, prior.applyAsDouble(entry.getValue()));
            combined.put(entry.getKey(), value);
            total += value;
        }
        List<SurveyTarget.Candidate> result = new ArrayList<>();
        if (total <= 0.0D) {
            return result;
        }
        for (Map.Entry<String, Double> entry : combined.entrySet()) {
            SurveyTarget.Candidate candidate = keys.get(entry.getKey());
            double probability = entry.getValue() / total;
            if (probability >= minimum) {
                result.add(
                        new SurveyTarget.Candidate(
                                candidate.source(),
                                candidate.category(),
                                candidate.oreId(),
                                probability));
            }
        }
        result.sort(
                Comparator.comparingDouble(SurveyTarget.Candidate::probability)
                        .reversed()
                        .thenComparing(SurveyTarget.Candidate::oreId));
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : List.copyOf(result);
    }

    private static String key(SurveyTarget.Candidate candidate) {
        return candidate.source().ordinal()
                + "/"
                + (candidate.category() == null ? -1 : candidate.category().ordinal())
                + "/"
                + candidate.oreId();
    }
}

package dev.world.geology.lidar;

import dev.world.geology.SurveyTarget;
import dev.world.geology.ore.OreIdentification;
import dev.world.geology.ore.OreSignature;
import dev.world.geology.ore.OreTraits;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.ToDoubleFunction;

/**
 * Prior probabilities of identification candidates, as the identification model uses them: a kind
 * of source by its own prior, an ore by its prevalence among the dimension's ores, an ore class by
 * the summed prevalence of its ores. Prevalence is how often generation places an ore, never a
 * physical property.
 */
public final class OrePriors {
    /** Prior of "noise", standing in for the survey's false-alarm chance. */
    private static final double NOISE_PRIOR = 0.05D;

    private OrePriors() {}

    public static ToDoubleFunction<SurveyTarget.Candidate> of(List<OreSignature> table) {
        Map<String, Double> ores = new HashMap<>();
        Map<OreTraits.Category, Double> classes = new EnumMap<>(OreTraits.Category.class);
        for (OreSignature signature : table) {
            ores.merge(signature.oreId(), signature.prevalence(), Double::sum);
            classes.merge(signature.traits().category(), signature.prevalence(), Double::sum);
        }
        double orePrior = OreIdentification.Source.ORE.prior();
        return candidate -> {
            OreIdentification.Source source = candidate.source();
            if (source == OreIdentification.Source.NOISE) {
                return NOISE_PRIOR;
            }
            if (source != OreIdentification.Source.ORE) {
                return source.prior();
            }
            double share =
                    candidate.oreId().isEmpty()
                            ? classes.getOrDefault(candidate.category(), 0.0D)
                            : ores.getOrDefault(candidate.oreId(), 0.0D);
            return orePrior * Math.max(1.0E-4D, share);
        };
    }
}

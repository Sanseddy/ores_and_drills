package dev.world.geology.ore;

import dev.world.geology.DepositBodies;
import dev.world.geology.NaturalGeology;
import dev.world.geology.RockProperties;
import dev.world.geology.SeedHash;
import dev.world.geology.SeismicMath;
import dev.world.geology.SubsurfaceBody;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.function.DoubleFunction;

/**
 * Bayesian identification of what causes an anomaly. The measurement is a noisy estimate of the
 * source's intrinsic contrast (the reflection coefficient its material would have at ore-body
 * grade), its absorption and its depth. Each hypothesis — every ore of the table and every kind of
 * natural heterogeneity — predicts those values; the posterior weighs the fit by the hypothesis's
 * prior. Ore priors come from how common each ore is; similar ores therefore share the probability,
 * and a better scanner narrows the measurement until they separate.
 *
 * <p>The station and the exploration drill use the same model: a core is simply a measurement with
 * much smaller errors.
 */
public final class OreIdentification {
    /** Contrast spread within one ore: grade and texture vary between deposits. */
    static final double ORE_SPREAD = 0.002D;

    /** Each upgrade level scales the measurement errors by this factor. */
    public static final double LEVEL_FACTOR = 0.72D;

    /** Intrinsic contrast and absorption a cave zone reads as. */
    static final double CAVITY_CONTRAST = -0.9D;

    static final double CAVITY_SPREAD = 0.15D;
    static final double CAVITY_ATTENUATION = 1.8D;

    private static final double[] VARIATIONS = {
        0.05D, 0.15D, 0.25D, 0.35D, 0.45D, 0.55D, 0.65D, 0.75D, 0.85D, 0.95D
    };

    private OreIdentification() {}

    public enum Source {
        ORE(0.40D),
        DENSE_MASSIF(0.18D),
        LOW_VELOCITY_LENS(0.11D),
        FRACTURED_ZONE(0.11D),
        LITHOLOGIC_CONTACT(0.06D),
        CAVITY(0.14D),
        /**
         * No coherent reflector: noise, or many weak contributions adding up. Its prior is the
         * survey's false-alarm chance, see {@link Precision#falseAlarm()}.
         */
        NOISE(0.0D);

        private final double prior;

        Source(double prior) {
            this.prior = prior;
        }

        public double prior() {
            return prior;
        }

        public static Source byId(int id) {
            Source[] values = values();
            return id >= 0 && id < values.length ? values[id] : NOISE;
        }

        static Source of(SubsurfaceBody.Kind kind) {
            return switch (kind) {
                case ORE_BODY, MINERALIZED_HALO -> ORE;
                case DENSE_MASSIF -> DENSE_MASSIF;
                case LOW_VELOCITY_LENS -> LOW_VELOCITY_LENS;
                case FRACTURED_ZONE -> FRACTURED_ZONE;
                case LITHOLOGIC_CONTACT -> LITHOLOGIC_CONTACT;
            };
        }

        SubsurfaceBody.Kind kind() {
            return switch (this) {
                case DENSE_MASSIF -> SubsurfaceBody.Kind.DENSE_MASSIF;
                case LOW_VELOCITY_LENS -> SubsurfaceBody.Kind.LOW_VELOCITY_LENS;
                case FRACTURED_ZONE -> SubsurfaceBody.Kind.FRACTURED_ZONE;
                case LITHOLOGIC_CONTACT -> SubsurfaceBody.Kind.LITHOLOGIC_CONTACT;
                default -> throw new IllegalStateException("No body kind for " + this);
            };
        }
    }

    /**
     * One measurement.
     *
     * @param contrast estimated intrinsic reflection coefficient
     * @param attenuation estimated ln(Q_host / Q_source)
     * @param depthKnown whether the survey resolved {@code y}
     */
    public record Observation(double contrast, double attenuation, double y, boolean depthKnown) {}

    /**
     * Measurement errors of one instrument at one upgrade level.
     *
     * @param falseAlarm prior chance that the anomaly is no coherent reflector at all
     */
    public record Precision(double contrast, double attenuation, double falseAlarm) {
        /**
         * A seismic survey: errors grow for weak anomalies and shrink with each upgrade; an anomaly
         * just above the detection threshold may well be noise, a strong one hardly.
         */
        public static Precision survey(int level, double snr) {
            double scale = Math.pow(LEVEL_FACTOR, Math.max(0, level));
            double falseAlarm = Math.min(0.5D, 0.5D * Math.exp(-(snr - 4.0D)));
            return new Precision(
                    (0.010D + 0.05D / Math.sqrt(Math.max(1.0D, snr))) * scale,
                    0.35D * scale,
                    Math.max(0.0D, falseAlarm));
        }

        /** A drilled core: the material is in hand, the errors are a laboratory's. */
        public static Precision core(int level) {
            double scale = Math.pow(LEVEL_FACTOR, Math.max(0, level));
            return new Precision(0.006D * scale, 0.12D * scale, 0.0D);
        }
    }

    /**
     * A hypothesis and its posterior probability.
     *
     * @param ore the ore, or null for a natural source or for an ore class as a whole
     * @param category the ore class; null for natural sources
     */
    public record Candidate(
            Source source, OreSignature ore, OreTraits.Category category, double probability) {
        public String oreId() {
            return ore == null ? null : ore.oreId();
        }

        Candidate withProbability(double value) {
            return new Candidate(source, ore, category, value);
        }
    }

    /** Candidates by decreasing probability, and the total probability that the source is ore. */
    public record Result(List<Candidate> candidates, double oreProbability) {
        public Candidate best() {
            return candidates.isEmpty() ? null : candidates.getFirst();
        }

        /** Probability of a given ore, 0 when it is not among the candidates. */
        public double probabilityOf(String oreId) {
            for (Candidate candidate : candidates) {
                if (oreId.equals(candidate.oreId())) {
                    return candidate.probability();
                }
            }
            return 0.0D;
        }

        /** Ores merged into their classes: what a scanner that cannot name ores reports. */
        public Result byCategory() {
            Map<OreTraits.Category, Double> classes = new EnumMap<>(OreTraits.Category.class);
            List<Candidate> merged = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (candidate.source() == Source.ORE) {
                    classes.merge(candidate.category(), candidate.probability(), Double::sum);
                } else {
                    merged.add(candidate);
                }
            }
            classes.forEach(
                    (category, probability) ->
                            merged.add(new Candidate(Source.ORE, null, category, probability)));
            merged.sort(ORDER);
            return new Result(List.copyOf(merged), oreProbability);
        }

        /** The {@code limit} most probable candidates that reach {@code minimum}. */
        public Result top(int limit, double minimum) {
            List<Candidate> kept = new ArrayList<>();
            for (Candidate candidate : candidates) {
                if (kept.size() >= limit || candidate.probability() < minimum) {
                    break;
                }
                kept.add(candidate);
            }
            return new Result(List.copyOf(kept), oreProbability);
        }
    }

    private static final Comparator<Candidate> ORDER =
            Comparator.comparingDouble((Candidate c) -> -c.probability())
                    .thenComparing(OreIdentification::sortKey);

    private static String sortKey(Candidate candidate) {
        if (candidate.ore() != null) {
            return candidate.ore().oreId();
        }
        return candidate.source().name()
                + (candidate.category() == null ? "" : candidate.category().name());
    }

    /** What an ore of {@code mineral} predicts in {@code host}, at ore-body grade. */
    public static double[] expectedOre(RockProperties host, RockProperties mineral) {
        RockProperties body = host.mixedWith(mineral, DepositBodies.CORE_GRADE);
        return new double[] {
            SeismicMath.reflectionCoefficient(host, body), Math.log(host.quality() / body.quality())
        };
    }

    /**
     * Posterior over all hypotheses.
     *
     * @param hostAt host rock at a height
     * @param naturalAllowed false for a core, which proves the material is ore
     */
    public static Result identify(
            Observation observation,
            List<OreSignature> table,
            DoubleFunction<RockProperties> hostAt,
            NaturalGeology.Setting setting,
            Precision precision,
            boolean naturalAllowed) {
        RockProperties host = hostAt.apply(observation.y());
        List<Candidate> weighted = new ArrayList<>();
        double total = 0.0D;
        double ore = 0.0D;
        double contrastSigma = Math.hypot(precision.contrast(), ORE_SPREAD);
        for (OreSignature signature : table) {
            double[] expected = expectedOre(host, signature.mineral());
            double weight =
                    Source.ORE.prior()
                            * signature.prevalence()
                            * gaussian(observation.contrast(), expected[0], contrastSigma)
                            * gaussian(
                                    observation.attenuation(), expected[1], precision.attenuation())
                            * depthLikelihood(signature, observation);
            weighted.add(
                    new Candidate(Source.ORE, signature, signature.traits().category(), weight));
            total += weight;
            ore += weight;
        }
        if (naturalAllowed) {
            for (Source source : Source.values()) {
                if (source == Source.ORE) {
                    continue;
                }
                double prior = source == Source.NOISE ? precision.falseAlarm() : source.prior();
                if (!(prior > 0.0D)) {
                    continue;
                }
                double weight =
                        prior * naturalLikelihood(source, host, setting, observation, precision);
                weighted.add(new Candidate(source, null, null, weight));
                total += weight;
            }
        }
        List<Candidate> candidates = new ArrayList<>(weighted.size());
        if (!(total > 0.0D)) {
            // Nothing fits at all: report the prior instead of dividing by zero.
            return new Result(List.of(), naturalAllowed ? Source.ORE.prior() : 1.0D);
        }
        for (Candidate candidate : weighted) {
            candidates.add(candidate.withProbability(candidate.probability() / total));
        }
        candidates.sort(ORDER);
        return new Result(List.copyOf(candidates), ore / total);
    }

    /**
     * How often a scanner of {@code precision} names each ore correctly: the mean posterior of the
     * true ore over a fixed set of measurement errors, at the ore's typical depth. This is the
     * "probability of identification" the ore table reports.
     */
    public static double[] identificationProbabilities(
            List<OreSignature> table,
            DoubleFunction<RockProperties> hostAt,
            NaturalGeology.Setting setting,
            Precision precision) {
        double[] result = new double[table.size()];
        double[][] errors = standardNormalPairs(24);
        for (int i = 0; i < table.size(); i++) {
            OreSignature signature = table.get(i);
            double y = signature.generationKnown() ? signature.traits().meanY() : 0.0D;
            double[] expected = expectedOre(hostAt.apply(y), signature.mineral());
            double sum = 0.0D;
            for (double[] error : errors) {
                Observation observation =
                        new Observation(
                                expected[0] + error[0] * precision.contrast(),
                                expected[1] + error[1] * precision.attenuation(),
                                y,
                                true);
                sum +=
                        identify(observation, table, hostAt, setting, precision, true)
                                .probabilityOf(signature.oreId());
            }
            result[i] = sum / errors.length;
        }
        return result;
    }

    private static double depthLikelihood(OreSignature signature, Observation observation) {
        if (!observation.depthKnown() || !signature.generationKnown()) {
            return 1.0D;
        }
        double y = observation.y();
        double below = signature.traits().minY() - 8.0D - y;
        double above = y - signature.traits().maxY() - 8.0D;
        double outside = Math.max(0.0D, Math.max(below, above));
        return Math.exp(-0.5D * outside * outside / (16.0D * 16.0D));
    }

    private static double naturalLikelihood(
            Source source,
            RockProperties host,
            NaturalGeology.Setting setting,
            Observation observation,
            Precision precision) {
        if (source == Source.NOISE) {
            return gaussian(observation.contrast(), 0.0D, Math.hypot(precision.contrast(), 0.01D))
                    * gaussian(
                            observation.attenuation(),
                            0.0D,
                            Math.hypot(precision.attenuation(), 0.2D));
        }
        if (source == Source.CAVITY) {
            return gaussian(
                            observation.contrast(),
                            CAVITY_CONTRAST,
                            Math.hypot(precision.contrast(), CAVITY_SPREAD))
                    * gaussian(
                            observation.attenuation(),
                            CAVITY_ATTENUATION,
                            Math.hypot(precision.attenuation(), 0.4D));
        }
        double sum = 0.0D;
        for (double variation : VARIATIONS) {
            RockProperties rock = NaturalGeology.rock(source.kind(), setting, host, variation);
            sum +=
                    gaussian(
                                    observation.contrast(),
                                    SeismicMath.reflectionCoefficient(host, rock),
                                    Math.hypot(precision.contrast(), ORE_SPREAD))
                            * gaussian(
                                    observation.attenuation(),
                                    Math.log(host.quality() / rock.quality()),
                                    precision.attenuation());
        }
        return sum / VARIATIONS.length;
    }

    /** Intrinsic contrast and attenuation of a natural body's rock, for a measurement's truth. */
    public static double[] truthOf(SubsurfaceBody body) {
        return new double[] {body.reflectivity(), Math.log(body.attenuationRatio())};
    }

    /** What a cave zone reads as. */
    public static double[] cavityTruth() {
        return new double[] {CAVITY_CONTRAST, CAVITY_ATTENUATION};
    }

    /**
     * A measurement of {@code truth} with this precision; {@code seed} fixes the errors, so the
     * same survey always reports the same probabilities.
     */
    public static Observation measure(
            double[] truth, double y, boolean depthKnown, Precision precision, long seed) {
        return new Observation(
                truth[0] + precision.contrast() * SeedHash.gaussian(seed, 1),
                truth[1] + precision.attenuation() * SeedHash.gaussian(seed, 2),
                y,
                depthKnown);
    }

    static double gaussian(double value, double mean, double sigma) {
        double z = (value - mean) / sigma;
        return Math.exp(-0.5D * z * z) / sigma;
    }

    /** Deterministic standard-normal pairs (Box–Muller over a Halton sequence). */
    static double[][] standardNormalPairs(int count) {
        double[][] pairs = new double[count][2];
        for (int i = 0; i < count; i++) {
            double u1 = halton(i + 1, 2);
            double u2 = halton(i + 1, 3);
            double r = Math.sqrt(-2.0D * Math.log(u1));
            pairs[i][0] = r * Math.cos(2.0D * Math.PI * u2);
            pairs[i][1] = r * Math.sin(2.0D * Math.PI * u2);
        }
        return pairs;
    }

    private static double halton(int index, int base) {
        double result = 0.0D;
        double fraction = 1.0D;
        int i = index;
        while (i > 0) {
            fraction /= base;
            result += fraction * (i % base);
            i /= base;
        }
        return result;
    }
}

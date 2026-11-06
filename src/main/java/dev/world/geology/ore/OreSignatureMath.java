package dev.world.geology.ore;

import dev.world.geology.RockProperties;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * Derives conditional ore properties from game data alone. There is no per-ore table: an ore's
 * density, velocity and absorption follow from the class of its products (metal, gem, dust, other),
 * from how hard it is relative to the other ores of the world, and from how deep it generates.
 * Generation frequency only sets how common an ore is; a rare ore is not denser for being rare.
 */
public final class OreSignatureMath {
    /** Share given to ores without recorded generation, relative to the rarest recorded one. */
    static final double UNKNOWN_PREVALENCE_FACTOR = 0.25D;

    /** Depth index of an ore whose generation height is unknown: the middle of the world. */
    public static final double UNKNOWN_DEPTH = 0.5D;

    private OreSignatureMath() {}

    /**
     * Signatures of a set of ores generated in a world whose rock spans {@code minY}…{@code maxY}.
     * Hardness is ranked within the set, so the same ore can read slightly differently in a heavily
     * modded world — the characteristics are relative, like an interpreter's.
     */
    public static List<OreSignature> build(List<OreTraits> ores, int minY, int maxY) {
        double[] depth = new double[ores.size()];
        for (int i = 0; i < depth.length; i++) {
            OreTraits ore = ores.get(i);
            depth[i] = ore.generationKnown() ? depthIndex(ore.meanY(), minY, maxY) : UNKNOWN_DEPTH;
        }
        RockProperties[] minerals = minerals(ores, depth);
        double[] prevalence = prevalences(ores);
        List<OreSignature> signatures = new ArrayList<>(ores.size());
        for (int i = 0; i < ores.size(); i++) {
            signatures.add(new OreSignature(ores.get(i), minerals[i], prevalence[i]));
        }
        return List.copyOf(signatures);
    }

    /** Minerals of {@code ores}; {@code depth} holds each ore's depth index (0 top, 1 floor). */
    public static RockProperties[] minerals(List<OreTraits> ores, double[] depth) {
        double[] hardnessRank = rank(ores);
        RockProperties[] result = new RockProperties[ores.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = mineral(ores.get(i).category(), hardnessRank[i], depth[i]);
        }
        return result;
    }

    /**
     * How common each ore is among {@code ores}, summing to 1. Ores without recorded generation are
     * still possible — a datapack or structure may place them — but are treated as rarer than any
     * recorded ore.
     */
    public static double[] prevalences(List<OreTraits> ores) {
        int count = ores.size();
        double[] result = new double[count];
        if (count == 0) {
            return result;
        }
        double rarest = Double.POSITIVE_INFINITY;
        for (OreTraits ore : ores) {
            if (ore.generationKnown()) {
                rarest = Math.min(rarest, ore.frequency());
            }
        }
        double unknown = Double.isFinite(rarest) ? rarest * UNKNOWN_PREVALENCE_FACTOR : 1.0D;
        double total = 0.0D;
        for (int i = 0; i < count; i++) {
            result[i] = ores.get(i).generationKnown() ? ores.get(i).frequency() : unknown;
            total += result[i];
        }
        for (int i = 0; i < count; i++) {
            result[i] = total > 0.0D ? result[i] / total : 1.0D / count;
        }
        return result;
    }

    /**
     * Conditional mineral of an ore class, modulated by relative hardness (0–1) and generation
     * depth (0 at the top of the world, 1 at its floor). Metals are dense and fast, gems fast and
     * light, soft ores slower, everything else (fuels and the like) light and slow. Harder ores are
     * faster and less absorbing; ores formed deeper are denser, faster and less absorbing, as rock
     * under more pressure is — which is also what lets a careful measurement tell apart two ores of
     * one class that generate at different depths.
     */
    public static RockProperties mineral(
            OreTraits.Category category, double hardness, double depth) {
        double h = clamp(hardness) - 0.5D;
        double d = clamp(depth) - 0.5D;
        return switch (category) {
            case METALLIC ->
                    new RockProperties(
                            4.4D + 1.2D * h + 1.6D * d,
                            6200.0D + 1400.0D * h + 800.0D * d,
                            110.0D + 60.0D * h + 40.0D * d);
            case GEM ->
                    new RockProperties(
                            3.0D + 0.6D * h + 0.6D * d,
                            6600.0D + 1400.0D * h + 800.0D * d,
                            180.0D + 60.0D * h + 40.0D * d);
            case DUST ->
                    new RockProperties(
                            3.6D + 1.0D * h + 1.2D * d,
                            4600.0D + 1200.0D * h + 600.0D * d,
                            70.0D + 40.0D * h + 30.0D * d);
            case OTHER ->
                    new RockProperties(
                            2.2D + 0.6D * h + 0.6D * d,
                            3600.0D + 1200.0D * h + 600.0D * d,
                            50.0D + 30.0D * h + 20.0D * d);
        };
    }

    public static double depthIndex(double meanY, int minY, int maxY) {
        if (maxY <= minY) {
            return UNKNOWN_DEPTH;
        }
        return clamp((maxY - meanY) / (double) (maxY - minY));
    }

    /** Percentile rank of tool tier then hardness; ties share their average rank. */
    static double[] rank(List<OreTraits> ores) {
        int count = ores.size();
        double[] result = new double[count];
        if (count == 1) {
            result[0] = 0.5D;
        }
        if (count <= 1) {
            return result;
        }
        Integer[] order = new Integer[count];
        for (int i = 0; i < count; i++) {
            order[i] = i;
        }
        Arrays.sort(order, Comparator.comparingDouble((Integer i) -> score(ores.get(i))));
        int start = 0;
        while (start < count) {
            int end = start;
            double value = score(ores.get(order[start]));
            while (end + 1 < count && score(ores.get(order[end + 1])) == value) {
                end++;
            }
            double rank = (start + end) * 0.5D / (count - 1);
            for (int k = start; k <= end; k++) {
                result[order[k]] = rank;
            }
            start = end + 1;
        }
        return result;
    }

    private static double score(OreTraits ore) {
        return ore.toolTier() * 100.0D + ore.hardness();
    }

    private static double clamp(double value) {
        return Math.max(0.0D, Math.min(1.0D, value));
    }
}

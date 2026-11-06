package dev.world.geology;

import java.util.ArrayList;
import java.util.List;

/**
 * Turns a deposit into survey bodies. MEDIUM/LARGE lenses are only 3–7 blocks thick and 20–60 wide,
 * far below the Fresnel zone of a regional survey, so on their own they would be nearly invisible.
 * Real ore systems sit inside a much wider zone of altered, weakly mineralised host rock; the model
 * adds such a halo, scaled from the deposit, so that a deposit is detectable through its
 * surroundings while its exact blocks stay unresolved. The halo changes no blocks.
 */
public final class DepositBodies {
    /** Volume share of ore mineral inside the deposit's own blocks. */
    public static final double CORE_GRADE = 0.35D;

    /** Thinnest core the model accepts; thinner lenses still reflect as a 1-block bed. */
    public static final double MINIMUM_CORE_THICKNESS = 1.0D;

    private DepositBodies() {}

    /**
     * Bodies for one deposit.
     *
     * @param tierIndex {@code OreDepositTier} index: 0 TINY … 3 LARGE
     * @param remainingFraction remaining ore over initial ore where the real blocks are known, else
     *     1. Mining removes ore from the core; the altered halo around it stays.
     */
    public static List<SubsurfaceBody> bodies(
            long key,
            int tierIndex,
            double x,
            double y,
            double z,
            double radiusX,
            double radiusZ,
            double thickness,
            RockProperties ore,
            RockProperties host,
            double remainingFraction) {
        double remaining = Math.max(0.0D, Math.min(1.0D, remainingFraction));
        double coreThickness = Math.max(MINIMUM_CORE_THICKNESS, thickness);
        double angle = angle(key);
        List<SubsurfaceBody> bodies = new ArrayList<>(2);
        RockProperties core = host.mixedWith(ore, CORE_GRADE * remaining);
        double coreReflectivity = SeismicMath.reflectionCoefficient(host, core);
        if (remaining > 0.0D && coreReflectivity != 0.0D) {
            bodies.add(
                    new SubsurfaceBody(
                            SubsurfaceBody.Kind.ORE_BODY,
                            key,
                            x,
                            y,
                            z,
                            Math.max(1.0D, radiusX),
                            Math.max(1.0D, radiusZ),
                            coreThickness,
                            angle,
                            coreReflectivity,
                            host.quality() / core.quality()));
        }

        double haloGrade = haloGrade(tierIndex);
        if (haloGrade > 0.0D) {
            double scale = haloScale(tierIndex);
            double margin = haloMargin(tierIndex);
            // Alteration fractures the rock as well as mineralising it: Q falls to under half.
            RockProperties halo = host.mixedWith(ore, haloGrade);
            halo = halo.withQuality(halo.quality() * 0.45D);
            bodies.add(
                    new SubsurfaceBody(
                            SubsurfaceBody.Kind.MINERALIZED_HALO,
                            key,
                            x,
                            y,
                            z,
                            radiusX * scale + margin,
                            radiusZ * scale + margin,
                            coreThickness * 2.0D + (tierIndex >= 2 ? 8.0D : 2.0D),
                            angle,
                            SeismicMath.reflectionCoefficient(host, halo),
                            host.quality() / halo.quality()));
        }
        return List.copyOf(bodies);
    }

    /**
     * Ore-mineral share of the halo: none around TINY, faint around SMALL. MEDIUM/LARGE lenses hold
     * hundreds of thousands of units in a few thousand blocks — massive ore — and sit in
     * correspondingly strong alteration zones. The values were calibrated against the real
     * overworld cave field (OverworldCalibrationGameTests) so that such zones compete with caves.
     */
    static double haloGrade(int tierIndex) {
        return switch (tierIndex) {
            case 0 -> 0.0D;
            case 1 -> 0.03D;
            case 2 -> 0.15D;
            default -> 0.20D;
        };
    }

    static double haloScale(int tierIndex) {
        return tierIndex >= 2 ? 4.2D : 2.0D;
    }

    static double haloMargin(int tierIndex) {
        return switch (tierIndex) {
            case 0, 1 -> 4.0D;
            case 2 -> 25.0D;
            default -> 34.0D;
        };
    }

    /** Orientation of the lens; deterministic per deposit. */
    static double angle(long key) {
        return (SeedHash.unit(key, 0x414E474CL)) * Math.PI;
    }
}

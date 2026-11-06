package dev.world.geology;

import dev.world.geology.ore.OreSignatureMath;
import dev.world.geology.ore.OreTraits;
import java.util.ArrayList;
import java.util.List;

/** Synthetic survey inputs for the pure model tests: no world, no generator. */
final class SurveyFixtures {
    static final double DATUM_Y = 70.0D;
    static final int MIN_Y = -59;
    static final NaturalGeology.Setting SETTING = NaturalGeology.Setting.OVERWORLD;

    /** A deep, fairly hard metal ore, like iron in a vanilla world. */
    static final RockProperties METAL_ORE =
            OreSignatureMath.mineral(OreTraits.Category.METALLIC, 0.4D, 0.8D);

    private SurveyFixtures() {}

    static List<SubsurfaceBody> deposit(
            long key, int tier, double x, double y, double z, double remaining) {
        double radius =
                switch (tier) {
                    case 0 -> 1.5D;
                    case 1 -> 3.0D;
                    case 2 -> 12.5D;
                    default -> 25.0D;
                };
        double thickness = tier >= 2 ? (tier == 3 ? 6.0D : 4.0D) : 2.0D;
        return DepositBodies.bodies(
                key,
                tier,
                x,
                y,
                z,
                radius,
                radius,
                thickness,
                METAL_ORE,
                NaturalGeology.host(SETTING, y),
                remaining);
    }

    static SurveyInput input(
            SurveyMode mode,
            double radius,
            double[] geophoneX,
            double[] geophoneZ,
            List<SubsurfaceBody> bodies,
            CavityGrid cavities,
            long seed) {
        return input(mode, radius, geophoneX, geophoneZ, bodies, cavities, seed, 0);
    }

    static SurveyInput input(
            SurveyMode mode,
            double radius,
            double[] geophoneX,
            double[] geophoneZ,
            List<SubsurfaceBody> bodies,
            CavityGrid cavities,
            long seed,
            int level) {
        return new SurveyInput(
                mode,
                0.0D,
                DATUM_Y,
                0.0D,
                radius,
                MIN_Y,
                SETTING,
                geophoneX,
                geophoneZ,
                1.0D,
                false,
                bodies,
                NaturalGeology.horizons(SETTING, MIN_Y - 5),
                cavities,
                seed,
                level);
    }

    static SurveyInput input(SurveyMode mode, double radius, List<SubsurfaceBody> bodies) {
        return input(mode, radius, new double[0], new double[0], bodies, null, 12345L);
    }

    static double intensityAt(SurveyMap map, double x, double z) {
        int index = map.indexAt(x, z);
        return index < 0 ? 0.0D : map.intensity(index);
    }

    /** Strongest intensity within {@code reach} blocks of (x, z). */
    static double peakNear(SurveyMap map, double x, double z, double reach) {
        double best = 0.0D;
        for (int row = 0; row < map.resolution(); row++) {
            for (int column = 0; column < map.resolution(); column++) {
                double dx = map.pixelX(column) - x;
                double dz = map.pixelZ(row) - z;
                if (dx * dx + dz * dz <= reach * reach) {
                    best = Math.max(best, map.intensity(column + row * map.resolution()));
                }
            }
        }
        return best;
    }

    static List<SubsurfaceBody> concat(List<List<SubsurfaceBody>> parts) {
        List<SubsurfaceBody> all = new ArrayList<>();
        parts.forEach(all::addAll);
        return all;
    }
}

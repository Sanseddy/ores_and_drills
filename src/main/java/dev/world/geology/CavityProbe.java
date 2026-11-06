package dev.world.geology;

/**
 * Describes open space in the undisturbed terrain. The server implementation evaluates the
 * generator's density function; it never reads chunks. Implementations must be safe to call from a
 * worker thread.
 */
@FunctionalInterface
public interface CavityProbe {
    CavityProbe SOLID = (x, y, z) -> 0.0D;

    /**
     * 0 for rock. For open space, how strongly its filling contrasts with the surrounding rock, as
     * −R of the rock–filling interface: about 1 for air, 0.8 for water, and little for lava, whose
     * impedance is close to that of rock.
     */
    double contrast(int x, int y, int z);

    /** Contrast of an open space filled with {@code filling} inside {@code host}. */
    static double fillingContrast(RockProperties host, RockProperties filling) {
        return Math.max(0.0D, -SeismicMath.reflectionCoefficient(host, filling));
    }
}

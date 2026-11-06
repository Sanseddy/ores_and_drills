package dev.world.geology;

/**
 * One reflector of the survey model: a lens-shaped volume whose impedance differs from the
 * surrounding host. It is server-side model state only; surveys never send bodies to clients.
 *
 * @param key deterministic identity of the structure; bodies of one deposit share it, and with it
 *     their positioning error
 * @param x horizontal centre
 * @param y vertical centre
 * @param z horizontal centre
 * @param radiusX semi-axis along the body's own X direction, in blocks
 * @param radiusZ semi-axis along the body's own Z direction, in blocks
 * @param thickness full vertical thickness in blocks
 * @param angle rotation of the body's axes around Y, in radians
 * @param reflectivity reflection coefficient of the body's top relative to its host
 * @param attenuationRatio Q of the host divided by Q of the body; above 1 absorbs more
 */
public record SubsurfaceBody(
        Kind kind,
        long key,
        double x,
        double y,
        double z,
        double radiusX,
        double radiusZ,
        double thickness,
        double angle,
        double reflectivity,
        double attenuationRatio) {
    public SubsurfaceBody {
        if (kind == null) {
            throw new IllegalArgumentException("Body kind is required");
        }
        if (!(radiusX > 0.0D && radiusZ > 0.0D && thickness > 0.0D)) {
            throw new IllegalArgumentException("Body dimensions must be positive");
        }
        if (!Double.isFinite(reflectivity) || !(attenuationRatio > 0.0D)) {
            throw new IllegalArgumentException("Invalid body contrast");
        }
    }

    public double top() {
        return y + thickness * 0.5D;
    }

    public double bottom() {
        return y - thickness * 0.5D;
    }

    public double maximumRadius() {
        return Math.max(radiusX, radiusZ);
    }

    public enum Kind {
        /** The deposit's own ore blocks, mixed into the host at the ore grade. */
        ORE_BODY,
        /**
         * Altered, weakly mineralised host rock around a deposit. It is a property of the model
         * only: no blocks are placed for it.
         */
        MINERALIZED_HALO,
        /** A dense crystalline intrusion: positive contrast, large, little absorption. */
        DENSE_MASSIF,
        /** A porous low-velocity lens (tuff, weathered rock): negative contrast. */
        LOW_VELOCITY_LENS,
        /** A fractured zone: small contrast, strong absorption. */
        FRACTURED_ZONE,
        /** A steep contact between two rock units, seen as a long narrow reflector. */
        LITHOLOGIC_CONTACT;

        public static Kind byId(int id) {
            Kind[] kinds = values();
            return id >= 0 && id < kinds.length ? kinds[id] : FRACTURED_ZONE;
        }
    }
}

package dev.world.geology;

/**
 * A laterally continuous boundary of the layered background, such as the top of deepslate. These
 * are the "main boundaries" of a cross-section; the regional background removes them from the
 * anomaly map because they are the same everywhere.
 *
 * @param y height of the boundary
 * @param reflectivity reflection coefficient for a wave travelling down through it
 */
public record Horizon(Kind kind, double y, double reflectivity) {
    public enum Kind {
        DEEPSLATE_TOP,
        BEDROCK_TOP;

        public static Kind byId(int id) {
            Kind[] kinds = values();
            return id >= 0 && id < kinds.length ? kinds[id] : DEEPSLATE_TOP;
        }
    }
}

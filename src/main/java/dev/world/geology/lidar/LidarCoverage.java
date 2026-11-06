package dev.world.geology.lidar;

/**
 * A volume one survey covered: a vertical cylinder around the station. The view outlines covered
 * volumes and leaves everything outside dark; merging uses them to tell "measured and empty" from
 * "never measured".
 *
 * @param cave whether this is the analyser's cave map rather than the seismic survey
 * @param level scanner or analyser level
 */
public record LidarCoverage(
        float x,
        float z,
        float radius,
        float topY,
        float bottomY,
        boolean cave,
        int level,
        long time) {
    public boolean contains(double px, double py, double pz) {
        double dx = px - x;
        double dz = pz - z;
        return dx * dx + dz * dz <= (double) radius * radius && py <= topY && py >= bottomY;
    }
}

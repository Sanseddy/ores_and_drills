package dev.world.geology;

/**
 * Processed result of one survey on its square grid; pixels outside the survey circle hold zeros
 * with {@code sigma} 0. Index {@code column + row * resolution}, column along X and row along Z.
 *
 * @param amplitude signed residual reflectivity of the dominant event (background removed)
 * @param sigma noise standard deviation of that event; {@code |amplitude| / sigma} is the intensity
 * @param depthY interpreted height of the dominant event
 * @param attenuation absorption anomaly of the dominant event, 0–255
 * @param lateralSigma horizontal position uncertainty, in blocks
 */
public record SurveyMap(
        SurveyMode mode,
        double centerX,
        double centerZ,
        double radius,
        int resolution,
        float[] amplitude,
        float[] sigma,
        short[] depthY,
        byte[] attenuation,
        float[] lateralSigma,
        SurveyMetrics metrics) {
    public int size() {
        return resolution * resolution;
    }

    public double pixelSize() {
        return radius * 2.0D / resolution;
    }

    public boolean inside(int index) {
        return sigma[index] > 0.0F;
    }

    public double intensity(int index) {
        return sigma[index] > 0.0F ? Math.abs(amplitude[index]) / sigma[index] : 0.0D;
    }

    public double pixelX(int column) {
        return centerX - radius + (column + 0.5D) * pixelSize();
    }

    public double pixelZ(int row) {
        return centerZ - radius + (row + 0.5D) * pixelSize();
    }

    /** Index of the pixel containing (x, z), or -1 outside the grid. */
    public int indexAt(double x, double z) {
        int column = (int) Math.floor((x - (centerX - radius)) / pixelSize());
        int row = (int) Math.floor((z - (centerZ - radius)) / pixelSize());
        if (column < 0 || row < 0 || column >= resolution || row >= resolution) {
            return -1;
        }
        return column + row * resolution;
    }

    public SurveyMap withMetrics(SurveyMetrics newMetrics) {
        return new SurveyMap(
                mode,
                centerX,
                centerZ,
                radius,
                resolution,
                amplitude,
                sigma,
                depthY,
                attenuation,
                lateralSigma,
                newMetrics);
    }
}

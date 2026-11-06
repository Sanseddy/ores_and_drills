package dev.world.geology;

import java.util.List;

/**
 * Immutable snapshot of everything a survey computation needs. It is assembled on the server thread
 * and then processed on a worker without touching the world.
 *
 * @param datumY height the station stands at; depths are measured from it
 * @param minY lowest height the model covers (just above the bedrock floor)
 * @param geophoneX horizontal positions of the geophones (the station's own sensor is implicit)
 * @param environment noise multiplier from weather and from how well the station couples to the
 *     ground
 * @param wellTie whether a borehole inside the area calibrates the velocity
 * @param noiseSeed seed of this exact configuration; equal configurations get equal noise
 * @param level installed upgrade level, see {@link ScannerUpgrade}
 */
public record SurveyInput(
        SurveyMode mode,
        double centerX,
        double datumY,
        double centerZ,
        double radius,
        int minY,
        NaturalGeology.Setting setting,
        double[] geophoneX,
        double[] geophoneZ,
        double environment,
        boolean wellTie,
        List<SubsurfaceBody> bodies,
        List<Horizon> horizons,
        CavityGrid cavities,
        long noiseSeed,
        int level) {
    public SurveyInput {
        if (geophoneX.length != geophoneZ.length) {
            throw new IllegalArgumentException("Geophone coordinate arrays differ in length");
        }
        geophoneX = geophoneX.clone();
        geophoneZ = geophoneZ.clone();
        bodies = List.copyOf(bodies);
        horizons = List.copyOf(horizons);
        level = ScannerUpgrade.clamp(level);
    }

    public int resolution() {
        return ScannerUpgrade.resolution(mode, level);
    }

    public double pixelSize() {
        return radius * 2.0D / resolution();
    }

    public double binHeight() {
        return binHeight(mode);
    }

    public int bins() {
        return bins(mode, datumY, minY);
    }

    /**
     * Depth bin height: about a tenth of the dominant wavelength, finer than the quarter-wavelength
     * vertical resolution without inflating the number of independent bins a noise peak can win.
     */
    public static double binHeight(SurveyMode mode) {
        return mode == SurveyMode.REGIONAL ? 24.0D : 8.0D;
    }

    public static int bins(SurveyMode mode, double datumY, int minY) {
        return Math.max(1, (int) Math.ceil((datumY - minY) / binHeight(mode)));
    }

    public double pixelX(int column) {
        return centerX - radius + (column + 0.5D) * pixelSize();
    }

    public double pixelZ(int row) {
        return centerZ - radius + (row + 0.5D) * pixelSize();
    }
}

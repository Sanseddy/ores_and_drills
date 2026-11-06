package dev.world.geology;

import java.util.List;

/**
 * The settings of the station's one survey slider, from the closest and finest to the widest and
 * coarsest. Each step is a programme and a radius within what the installed scanner allows: a
 * smaller radius spreads the same grid over less ground, so it resolves finer detail. Both sides
 * derive the list from the same two radii, so an index is all the client sends.
 */
public record SurveyPreset(SurveyMode mode, int radius) {
    public static final int COUNT = 6;

    /** Fraction of the scanner's speed every level gains: better electronics stack faster. */
    public static final double SPEED_PER_LEVEL = 0.07D;

    public static List<SurveyPreset> of(int detailedRadius, int regionalRadius) {
        int detailed = Math.max(16, detailedRadius);
        int regional = Math.max(detailed, regionalRadius);
        return List.of(
                new SurveyPreset(SurveyMode.DETAILED, Math.max(16, detailed / 3)),
                new SurveyPreset(SurveyMode.DETAILED, Math.max(16, detailed * 2 / 3)),
                new SurveyPreset(SurveyMode.DETAILED, detailed),
                new SurveyPreset(SurveyMode.REGIONAL, Math.max(detailed, regional / 3)),
                new SurveyPreset(SurveyMode.REGIONAL, Math.max(detailed, regional * 2 / 3)),
                new SurveyPreset(SurveyMode.REGIONAL, regional));
    }

    public static SurveyPreset at(int index, int detailedRadius, int regionalRadius) {
        List<SurveyPreset> presets = of(detailedRadius, regionalRadius);
        return presets.get(Math.max(0, Math.min(presets.size() - 1, index)));
    }

    /**
     * Pulsing time of a preset: the programme's configured time for its full radius, less for a
     * smaller area, and shorter with every scanner level.
     *
     * @param baseTicks configured duration of the programme
     * @param maximumRadius the programme's full radius at this level
     */
    public static int durationTicks(int baseTicks, int radius, int maximumRadius, int level) {
        double area = Math.min(1.0D, radius / (double) Math.max(1, maximumRadius));
        double speed = 1.0D - SPEED_PER_LEVEL * ScannerUpgrade.clamp(level);
        return Math.max(40, (int) Math.round(baseTicks * (0.4D + 0.6D * area) * speed));
    }

    /** Map cell edge of the preset at a scanner level, in blocks. */
    public double cellSize(int level) {
        return radius * 2.0D / ScannerUpgrade.resolution(mode, level);
    }
}

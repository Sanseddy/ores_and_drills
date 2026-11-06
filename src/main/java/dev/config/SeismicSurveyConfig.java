package dev.config;

import dev.world.geology.ScannerUpgrade;
import dev.world.geology.SurveyMode;
import net.neoforged.neoforge.common.ModConfigSpec;

/** Settings of the seismic survey station, geophones and exploration drill. */
public final class SeismicSurveyConfig {
    public static final String FILE_NAME = "ores_and_drills-seismic.toml";

    public static final ModConfigSpec SPEC;
    public static final ModConfigSpec.IntValue REGIONAL_RADIUS;
    public static final ModConfigSpec.IntValue UPGRADED_REGIONAL_RADIUS;
    public static final ModConfigSpec.BooleanValue REGIONAL_UPGRADE;
    public static final ModConfigSpec.IntValue DETAILED_RADIUS;
    public static final ModConfigSpec.IntValue REGIONAL_DURATION_TICKS;
    public static final ModConfigSpec.IntValue DETAILED_DURATION_TICKS;
    public static final ModConfigSpec.IntValue REGIONAL_ENERGY_PER_TICK;
    public static final ModConfigSpec.IntValue DETAILED_ENERGY_PER_TICK;
    public static final ModConfigSpec.IntValue STATION_ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue MAX_GEOPHONES;
    public static final ModConfigSpec.IntValue SAVED_SURVEYS_PER_STATION;
    public static final ModConfigSpec.IntValue TICK_BUDGET_MICROS;
    public static final ModConfigSpec.IntValue CAVITY_SAMPLE_BUDGET;
    public static final ModConfigSpec.IntValue DRILL_ENERGY_PER_TICK;
    public static final ModConfigSpec.IntValue DRILL_ENERGY_CAPACITY;
    public static final ModConfigSpec.IntValue DRILL_TICKS_PER_BLOCK;
    public static final ModConfigSpec.IntValue DRILL_MAX_DEPTH;
    public static final ModConfigSpec.IntValue LIDAR_MAX_SAMPLES;
    public static final ModConfigSpec.IntValue LIDAR_MAX_OBJECTS;
    public static final ModConfigSpec.IntValue LIDAR_STALE_DAYS;
    public static final ModConfigSpec.IntValue LIDAR_SYNC_SAMPLES_PER_TICK;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("seismic_survey");
        REGIONAL_RADIUS =
                builder.comment("Radius of a regional survey, in blocks.")
                        .defineInRange("regional_radius", 2048, 256, 8192);
        UPGRADED_REGIONAL_RADIUS =
                builder.comment(
                                "Regional radius from scanner level 1 (the first upgrade module),"
                                        + " in blocks.")
                        .defineInRange("upgraded_regional_radius", 4096, 256, 8192);
        REGIONAL_UPGRADE =
                builder.comment(
                                "Gives every station the upgraded regional radius without an"
                                        + " upgrade module.")
                        .define("regional_upgrade", false);
        DETAILED_RADIUS =
                builder.comment(
                                "Radius of a detailed survey, in blocks; scanner level 1 and above"
                                        + " multiply it by 1.5.")
                        .defineInRange("detailed_radius", 512, 64, 2048);
        REGIONAL_DURATION_TICKS =
                builder.comment("Length of a regional survey in ticks (20 ticks = 1 second).")
                        .defineInRange("regional_duration_ticks", 1200, 100, 72000);
        DETAILED_DURATION_TICKS =
                builder.comment("Length of a detailed survey in ticks.")
                        .defineInRange("detailed_duration_ticks", 600, 100, 72000);
        REGIONAL_ENERGY_PER_TICK =
                builder.comment("FE used per tick while a regional survey is pulsing.")
                        .defineInRange("regional_energy_per_tick", 80, 0, 1_000_000);
        DETAILED_ENERGY_PER_TICK =
                builder.comment("FE used per tick while a detailed survey is pulsing.")
                        .defineInRange("detailed_energy_per_tick", 60, 0, 1_000_000);
        STATION_ENERGY_CAPACITY =
                builder.comment("Energy buffer of the station, in FE.")
                        .defineInRange("station_energy_capacity", 120_000, 1_000, 100_000_000);
        MAX_GEOPHONES =
                builder.comment(
                                "Most geophones one survey uses; the nearest ones inside the"
                                        + " survey radius are taken.")
                        .defineInRange("max_geophones", 16, 0, 64);
        SAVED_SURVEYS_PER_STATION =
                builder.comment("Finished surveys a station keeps; the oldest are dropped.")
                        .defineInRange("saved_surveys_per_station", 8, 1, 32);
        TICK_BUDGET_MICROS =
                builder.comment(
                                "Server-thread time the survey service may use per tick, in"
                                        + " microseconds. Heavy computation runs on worker threads.")
                        .defineInRange("tick_budget_micros", 2000, 100, 50_000);
        CAVITY_SAMPLE_BUDGET =
                builder.comment(
                                "Terrain density probes per survey used to map caves. Lower values"
                                        + " are faster and coarser.")
                        .defineInRange("cavity_sample_budget", 131_072, 1_024, 4_194_304);
        builder.pop();

        builder.push("lidar_map");
        LIDAR_MAX_SAMPLES =
                builder.comment(
                                "Most measurements one station keeps per dimension in its 3D map;"
                                        + " the parts farthest from the latest survey are dropped"
                                        + " first.")
                        .defineInRange("max_samples", 250_000, 10_000, 2_000_000);
        LIDAR_MAX_OBJECTS =
                builder.comment("Most interpreted objects one station keeps per dimension.")
                        .defineInRange("max_objects", 1_024, 64, 8_192);
        LIDAR_STALE_DAYS =
                builder.comment(
                                "Age in in-game days after which a measurement counts as outdated:"
                                        + " it is shown dimmed and weighs half when a new survey"
                                        + " refines it.")
                        .defineInRange("stale_days", 5, 1, 365);
        LIDAR_SYNC_SAMPLES_PER_TICK =
                builder.comment(
                                "Measurements sent to one viewer per tick; larger maps arrive over"
                                        + " several ticks.")
                        .defineInRange("sync_samples_per_tick", 6_000, 500, 100_000);
        builder.pop();

        builder.push("exploration_drill");
        DRILL_ENERGY_PER_TICK =
                builder.comment("FE used per tick while the exploration drill is drilling.")
                        .defineInRange("energy_per_tick", 40, 0, 1_000_000);
        DRILL_ENERGY_CAPACITY =
                builder.comment("Energy buffer of the exploration drill, in FE.")
                        .defineInRange("energy_capacity", 40_000, 1_000, 100_000_000);
        DRILL_TICKS_PER_BLOCK =
                builder.comment("Ticks the drill needs to core one block of depth.")
                        .defineInRange("ticks_per_block", 4, 1, 200);
        DRILL_MAX_DEPTH =
                builder.comment("Deepest a borehole goes below the drill, in blocks.")
                        .defineInRange("max_depth", 256, 8, 512);
        builder.pop();
        SPEC = builder.build();
    }

    private SeismicSurveyConfig() {}

    /** Largest radius of {@code mode} for a station without upgrades. */
    public static int radius(SurveyMode mode) {
        return radius(mode, 0);
    }

    /** Largest radius of {@code mode} at scanner {@code level}. */
    public static int radius(SurveyMode mode, int level) {
        boolean range = ScannerUpgrade.has(level, ScannerUpgrade.Improvement.RANGE);
        return switch (mode) {
            case REGIONAL ->
                    range || REGIONAL_UPGRADE.get()
                            ? UPGRADED_REGIONAL_RADIUS.get()
                            : REGIONAL_RADIUS.get();
            case DETAILED ->
                    (int)
                            Math.round(
                                    DETAILED_RADIUS.get()
                                            * ScannerUpgrade.detailedRadiusFactor(level));
        };
    }

    public static int durationTicks(SurveyMode mode) {
        return mode == SurveyMode.REGIONAL
                ? REGIONAL_DURATION_TICKS.get()
                : DETAILED_DURATION_TICKS.get();
    }

    /** Age in ticks after which a map measurement is outdated. */
    public static long staleTicks() {
        return LIDAR_STALE_DAYS.get() * 24_000L;
    }

    public static int energyPerTick(SurveyMode mode) {
        return mode == SurveyMode.REGIONAL
                ? REGIONAL_ENERGY_PER_TICK.get()
                : DETAILED_ENERGY_PER_TICK.get();
    }
}

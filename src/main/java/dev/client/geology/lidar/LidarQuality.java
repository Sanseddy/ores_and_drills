package dev.client.geology.lidar;

import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Client-side quality of the LiDAR view: how many returns a measurement becomes, how many returns
 * are drawn at most before distant parts switch to their reduced copy, and whether the soft glow
 * pass runs. Stored in the client config so every screen and session uses the player's choice.
 */
public enum LidarQuality {
    LOW(0.5F, 60_000, false),
    MEDIUM(1.0F, 160_000, true),
    HIGH(1.6F, 320_000, true);

    public static final ModConfigSpec SPEC;
    private static final ModConfigSpec.EnumValue<LidarQuality> VALUE;

    static {
        ModConfigSpec.Builder builder = new ModConfigSpec.Builder();
        builder.push("lidar_view");
        VALUE =
                builder.comment(
                                "Quality of the 3D survey view: LOW, MEDIUM or HIGH. Lower values"
                                        + " draw fewer points and skip the glow.")
                        .defineEnum("quality", MEDIUM);
        builder.pop();
        SPEC = builder.build();
    }

    private final float density;
    private final int maxPoints;
    private final boolean glow;

    LidarQuality(float density, int maxPoints, boolean glow) {
        this.density = density;
        this.maxPoints = maxPoints;
        this.glow = glow;
    }

    public float density() {
        return density;
    }

    public int maxPoints() {
        return maxPoints;
    }

    public boolean glow() {
        return glow;
    }

    public static LidarQuality current() {
        try {
            return VALUE.get();
        } catch (IllegalStateException notLoaded) {
            return MEDIUM;
        }
    }

    public static void set(LidarQuality quality) {
        try {
            VALUE.set(quality);
            VALUE.save();
        } catch (IllegalStateException notLoaded) {
            // The config is not loaded outside a running client; nothing to persist.
        }
    }

    public LidarQuality next() {
        return values()[(ordinal() + 1) % values().length];
    }
}

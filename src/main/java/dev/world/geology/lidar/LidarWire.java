package dev.world.geology.lidar;

/**
 * The quantisation of samples on the wire: a position byte per axis at 1/8 block inside its {@link
 * LidarMap#BUCKET}-block bucket, and spreads on two slopes — quarter blocks up to 32 blocks, then
 * 2-block steps up to 286 — fine where positions are sharp, coarse where they are vague anyway.
 */
public final class LidarWire {
    private static final float POSITION_SCALE = 8.0F;

    private LidarWire() {}

    public static int offset(float relative) {
        return Math.max(0, Math.min(255, (int) Math.floor(relative * POSITION_SCALE)));
    }

    public static float position(int code) {
        return (code + 0.5F) / POSITION_SCALE;
    }

    public static int spreadCode(float spread) {
        if (spread < 32.0F) {
            return Math.max(0, Math.round(spread * 4.0F));
        }
        return Math.min(255, 128 + Math.round((spread - 32.0F) / 2.0F));
    }

    public static float spread(int code) {
        return code < 128 ? code / 4.0F : 32.0F + (code - 128) * 2.0F;
    }
}

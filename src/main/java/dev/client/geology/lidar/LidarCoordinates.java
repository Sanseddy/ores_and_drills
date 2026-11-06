package dev.client.geology.lidar;

import dev.world.geology.lidar.LidarObject;
import java.util.Locale;

/**
 * The block position quoted for a map object, and how sure it is. A borehole finding gives the
 * first ore block the core met — the most reliable point; a survey target or cave gives its
 * estimated centre, approximate by its position errors. A target whose depth was not resolved takes
 * the middle of the surveyed column and says so.
 *
 * @param error horizontal uncertainty in blocks
 * @param depthError vertical uncertainty in blocks, or -1 when the depth is unknown
 */
public record LidarCoordinates(
        int x, int y, int z, boolean approximate, int error, int depthError) {
    public static LidarCoordinates of(LidarObject object, double fallbackY) {
        int x = (int) Math.floor(object.x());
        int z = (int) Math.floor(object.z());
        if (object.kind() == LidarObject.Kind.CORE_ORE && Float.isFinite(object.sizeY())) {
            int top = (int) Math.floor(object.y() + object.sizeY() * 0.5F - 0.5F);
            return new LidarCoordinates(x, top, z, false, 0, 0);
        }
        if (!object.depthKnown()) {
            return new LidarCoordinates(
                    x,
                    (int) Math.floor(fallbackY),
                    z,
                    true,
                    Math.round(object.positionError()),
                    -1);
        }
        int depthError = Float.isFinite(object.depthError()) ? Math.round(object.depthError()) : -1;
        return new LidarCoordinates(
                x,
                (int) Math.floor(object.y()),
                z,
                object.approximate(),
                Math.round(object.positionError()),
                depthError);
    }

    /** The clipboard text, always "X: …, Y: …, Z: …". */
    public String clipboard() {
        return String.format(Locale.ROOT, "X: %d, Y: %d, Z: %d", x, y, z);
    }
}

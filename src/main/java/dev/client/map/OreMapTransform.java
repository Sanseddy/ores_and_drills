package dev.client.map;

/**
 * Maps an offset (dx, dz) in blocks from a patch center to a map's screen space: {@code x = xx*dx +
 * xz*dz + x0}, {@code y = yx*dx + yz*dz + y0}. Every map integration reduces its own camera, zoom
 * and rotation to one of these.
 */
public record OreMapTransform(double xx, double xz, double x0, double yx, double yz, double y0) {
    public static OreMapTransform axisAligned(
            double pixelsPerBlock, double centerScreenX, double centerScreenY) {
        return new OreMapTransform(
                pixelsPerBlock, 0.0D, centerScreenX, 0.0D, pixelsPerBlock, centerScreenY);
    }

    /**
     * World offsets rotated by {@code radians} (screen y points down, so positive turns clockwise),
     * then scaled.
     */
    public static OreMapTransform rotated(
            double pixelsPerBlock, double radians, double centerScreenX, double centerScreenY) {
        double cos = Math.cos(radians) * pixelsPerBlock;
        double sin = Math.sin(radians) * pixelsPerBlock;
        return new OreMapTransform(cos, -sin, centerScreenX, sin, cos, centerScreenY);
    }

    public double pixelsPerBlock() {
        return Math.sqrt(xx * xx + yx * yx);
    }

    public double x(double dx, double dz) {
        return xx * dx + xz * dz + x0;
    }

    public double y(double dx, double dz) {
        return yx * dx + yz * dz + y0;
    }
}

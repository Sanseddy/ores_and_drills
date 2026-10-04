package dev.client.ore.speck;

/**
 * Speck size categories, by number of pixels in one connected fragment, counted on the 16x16 grid. On higher
 * resolution textures an area is converted back to 16x16 pixels first, so a 2x2 block on a 32x32 texture is
 * still one pixel.
 */
public enum OreSpeckType {
    TYPE_1(1, 2),
    TYPE_2(3, 4),
    TYPE_3(5, 8),
    TYPE_4(9, Integer.MAX_VALUE);

    private final int minimumSize;
    private final int maximumSize;

    OreSpeckType(int minimumSize, int maximumSize) {
        this.minimumSize = minimumSize;
        this.maximumSize = maximumSize;
    }

    public static final int BASE_RESOLUTION = 16;

    public static OreSpeckType forSize(int pixels, int resolution) {
        int scale = areaScale(resolution);
        return forSize(Math.max(1, (pixels + scale - 1) / scale));
    }

    public static OreSpeckType forSize(int size) {
        if (size <= TYPE_1.maximumSize) {
            return TYPE_1;
        }
        if (size <= TYPE_2.maximumSize) {
            return TYPE_2;
        }
        if (size <= TYPE_3.maximumSize) {
            return TYPE_3;
        }
        return TYPE_4;
    }

    public int number() {
        return ordinal() + 1;
    }

    public int minimumSize() {
        return minimumSize;
    }

    public int maximumSize() {
        return maximumSize;
    }

    /** Smallest real pixel count of this type on a texture of {@code resolution}. */
    public int minimumPixels(int resolution) {
        return (minimumSize - 1) * areaScale(resolution) + 1;
    }

    /** Largest real pixel count of this type on a texture of {@code resolution}. */
    public int maximumPixels(int resolution) {
        return maximumSize == Integer.MAX_VALUE ? Integer.MAX_VALUE : maximumSize * areaScale(resolution);
    }

    /** Texture pixels per 16x16 pixel. */
    public static int areaScale(int resolution) {
        int scale = Math.max(1, resolution / BASE_RESOLUTION);
        return scale * scale;
    }
}

package dev.client.ore.speck;

/** Minimal immutable-by-convention ARGB raster, so speck analysis stays independent of {@code NativeImage}. */
public record ArgbImage(int width, int height, int[] pixels) {
    public ArgbImage {
        if (width <= 0 || height <= 0 || pixels.length != width * height) {
            throw new IllegalArgumentException("Pixel array does not match " + width + "x" + height);
        }
    }

    public static ArgbImage blank(int width, int height) {
        return new ArgbImage(width, height, new int[width * height]);
    }

    public int get(int x, int y) {
        return pixels[y * width + x];
    }

    public static int alpha(int argb) {
        return argb >>> 24;
    }

    public static int red(int argb) {
        return (argb >> 16) & 0xFF;
    }

    public static int green(int argb) {
        return (argb >> 8) & 0xFF;
    }

    public static int blue(int argb) {
        return argb & 0xFF;
    }

    /**
     * Brings any square (or vertically animated) texture to the fixed 16x16 grid speck layouts work on.
     * Only the first animation frame is used; higher resolutions are sampled at each cell's center so
     * hard pixel-art edges survive instead of being blurred.
     */
    public ArgbImage normalized(int size) {
        int frame = Math.min(width, height);
        if (frame == size && width == size && height == size) {
            return this;
        }

        int[] result = new int[size * size];
        for (int y = 0; y < size; y++) {
            int sourceY = Math.min(frame - 1, (int) ((y + 0.5D) * frame / size));
            for (int x = 0; x < size; x++) {
                int sourceX = Math.min(frame - 1, (int) ((x + 0.5D) * frame / size));
                result[y * size + x] = get(sourceX, sourceY);
            }
        }
        return new ArgbImage(size, size, result);
    }
}

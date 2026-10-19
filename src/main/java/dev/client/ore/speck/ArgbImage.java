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
     * This image as the game draws it with a tint color: every channel multiplied by the tint's RGB, alpha
     * unchanged. White (and the "no tint" value -1) returns the image itself.
     */
    public ArgbImage tinted(int tintRgb) {
        int rgb = tintRgb & 0xFFFFFF;
        if (rgb == 0xFFFFFF) {
            return this;
        }
        int[] result = new int[pixels.length];
        for (int index = 0; index < pixels.length; index++) {
            result[index] = multiply(pixels[index], rgb);
        }
        return new ArgbImage(width, height, result);
    }

    public static int multiply(int argb, int tintRgb) {
        int red = (red(argb) * red(tintRgb) + 127) / 255;
        int green = (green(argb) * green(tintRgb) + 127) / 255;
        int blue = (blue(argb) * blue(tintRgb) + 127) / 255;
        return argb & 0xFF000000 | red << 16 | green << 8 | blue;
    }

    /**
     * {@code top} drawn over this image with ordinary alpha blending, as stacked translucent model layers are.
     * Both images are brought to this image's frame size first.
     */
    public ArgbImage over(ArgbImage top) {
        int size = Math.min(width, height);
        ArgbImage bottom = normalized(size);
        ArgbImage upper = top.normalized(size);
        int[] result = new int[size * size];
        for (int index = 0; index < result.length; index++) {
            result[index] = blend(bottom.pixels[index], upper.pixels[index]);
        }
        return new ArgbImage(size, size, result);
    }

    static int blend(int bottom, int top) {
        int topAlpha = alpha(top);
        if (topAlpha == 0xFF) {
            return top;
        }
        if (topAlpha == 0) {
            return bottom;
        }
        int bottomAlpha = alpha(bottom);
        int outAlpha = topAlpha + bottomAlpha * (255 - topAlpha) / 255;
        if (outAlpha == 0) {
            return 0;
        }
        int red = (red(top) * topAlpha + red(bottom) * bottomAlpha * (255 - topAlpha) / 255) / outAlpha;
        int green = (green(top) * topAlpha + green(bottom) * bottomAlpha * (255 - topAlpha) / 255) / outAlpha;
        int blue = (blue(top) * topAlpha + blue(bottom) * bottomAlpha * (255 - topAlpha) / 255) / outAlpha;
        return outAlpha << 24 | red << 16 | green << 8 | blue;
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

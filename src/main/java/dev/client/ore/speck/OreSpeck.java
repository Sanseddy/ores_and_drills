package dev.client.ore.speck;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import java.util.Random;

/**
 * One connected ore fragment found on an original ore texture: its exact shape and original pixel colors
 * (local to its bounding box), its bounding-box position on the source texture, its size and its type.
 */
public final class OreSpeck {
    private final int originX;
    private final int originY;
    private final int width;
    private final int height;
    private final int[] xs;
    private final int[] ys;
    private final int[] colors;
    private final int resolution;
    private final OreSpeckType type;

    private OreSpeck(int originX, int originY, int width, int height, int[] xs, int[] ys, int[] colors, int resolution) {
        this.originX = originX;
        this.originY = originY;
        this.width = width;
        this.height = height;
        this.xs = xs;
        this.ys = ys;
        this.colors = colors;
        this.resolution = resolution;
        this.type = OreSpeckType.forSize(xs.length, resolution);
    }

    public static OreSpeck fromAbsolutePixels(int[] absoluteXs, int[] absoluteYs, int[] colors) {
        return fromAbsolutePixels(absoluteXs, absoluteYs, colors, OreSpeckType.BASE_RESOLUTION);
    }

    /**
     * Builds a speck from absolute source coordinates on a texture of {@code resolution}; the bounding box
     * becomes the local origin.
     */
    public static OreSpeck fromAbsolutePixels(int[] absoluteXs, int[] absoluteYs, int[] colors, int resolution) {
        if (absoluteXs.length == 0 || absoluteXs.length != absoluteYs.length || absoluteXs.length != colors.length) {
            throw new IllegalArgumentException("A speck needs at least one pixel with matching coordinate/color arrays");
        }

        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int index = 0; index < absoluteXs.length; index++) {
            minX = Math.min(minX, absoluteXs[index]);
            minY = Math.min(minY, absoluteYs[index]);
            maxX = Math.max(maxX, absoluteXs[index]);
            maxY = Math.max(maxY, absoluteYs[index]);
        }

        int[] localXs = new int[absoluteXs.length];
        int[] localYs = new int[absoluteYs.length];
        for (int index = 0; index < absoluteXs.length; index++) {
            localXs[index] = absoluteXs[index] - minX;
            localYs[index] = absoluteYs[index] - minY;
        }
        return new OreSpeck(minX, minY, maxX - minX + 1, maxY - minY + 1, localXs, localYs, colors.clone(), resolution);
    }

    /**
     * A smaller, still connected piece of this speck with its original colors, used when an extra speck of a
     * small type is needed but the original texture has none. Grows from a random pixel through 8-neighbors.
     */
    public OreSpeck cropped(int targetSize, Random random) {
        int size = Math.max(1, Math.min(size(), targetSize));
        if (size == size()) {
            return this;
        }

        int[] index = new int[width * height];
        Arrays.fill(index, -1);
        for (int pixel = 0; pixel < size(); pixel++) {
            index[ys[pixel] * width + xs[pixel]] = pixel;
        }

        boolean[] taken = new boolean[size()];
        List<Integer> chosen = new ArrayList<>(size);
        Deque<Integer> frontier = new ArrayDeque<>();
        int start = random.nextInt(size());
        frontier.add(start);
        taken[start] = true;
        while (!frontier.isEmpty() && chosen.size() < size) {
            int pixel = frontier.poll();
            chosen.add(pixel);
            for (int dy = -1; dy <= 1; dy++) {
                for (int dx = -1; dx <= 1; dx++) {
                    int nx = xs[pixel] + dx;
                    int ny = ys[pixel] + dy;
                    if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= width || ny >= height) {
                        continue;
                    }
                    int neighbor = index[ny * width + nx];
                    if (neighbor >= 0 && !taken[neighbor]) {
                        taken[neighbor] = true;
                        frontier.add(neighbor);
                    }
                }
            }
        }

        int[] croppedXs = new int[chosen.size()];
        int[] croppedYs = new int[chosen.size()];
        int[] croppedColors = new int[chosen.size()];
        for (int pixel = 0; pixel < chosen.size(); pixel++) {
            int source = chosen.get(pixel);
            croppedXs[pixel] = originX + xs[source];
            croppedYs[pixel] = originY + ys[source];
            croppedColors[pixel] = colors[source];
        }
        return fromAbsolutePixels(croppedXs, croppedYs, croppedColors, resolution);
    }

    /** X of the bounding box on the original texture. */
    public int originX() {
        return originX;
    }

    /** Y of the bounding box on the original texture. */
    public int originY() {
        return originY;
    }

    public int width() {
        return width;
    }

    public int height() {
        return height;
    }

    public int size() {
        return xs.length;
    }

    /** Side of the (square) texture this speck was found on. */
    public int resolution() {
        return resolution;
    }

    public OreSpeckType type() {
        return type;
    }

    public int pixelX(int pixel) {
        return xs[pixel];
    }

    public int pixelY(int pixel) {
        return ys[pixel];
    }

    public int pixelColor(int pixel) {
        return colors[pixel];
    }

    /** The shape as a row-major {@code width x height} mask, mainly for comparisons and debugging. */
    public boolean[] shapeMask() {
        boolean[] mask = new boolean[width * height];
        for (int pixel = 0; pixel < size(); pixel++) {
            mask[ys[pixel] * width + xs[pixel]] = true;
        }
        return mask;
    }

    @Override
    public String toString() {
        return "OreSpeck[type=" + type.number() + ", size=" + size() + ", at=" + originX + "," + originY
                + ", box=" + width + "x" + height + "]";
    }
}

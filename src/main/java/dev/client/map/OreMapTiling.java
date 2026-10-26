package dev.client.map;

import java.util.HashMap;
import java.util.Map;

/**
 * Minecraft-free tiling of a patch's cells. Level {@code n} merges cells into 2^n x 2^n tiles
 * aligned on the deposit centre, so a patch stays a solid pixel blob when a map is zoomed out far
 * enough that single blocks would fall between screen pixels.
 */
final class OreMapTiling {
    static final int MAX_LEVEL = 6;

    private OreMapTiling() {}

    /** The finest level whose tiles are at least one screen pixel wide at this zoom. */
    static int levelFor(double pixelsPerBlock) {
        int level = 0;
        while (level < MAX_LEVEL && pixelsPerBlock * (1 << level) < 1.0D) {
            level++;
        }
        return level;
    }

    /**
     * @param xs cell x offsets from the centre
     * @param zs cell z offsets from the centre
     * @param remaining ore left in each cell
     * @param initial ore each cell started with
     */
    static Tiles tile(int[] xs, int[] zs, long[] remaining, long[] initial, int step, int rgb) {
        Map<Long, long[]> merged = new HashMap<>();
        for (int index = 0; index < xs.length; index++) {
            int tileX = Math.floorDiv(xs[index], step) * step;
            int tileZ = Math.floorDiv(zs[index], step) * step;
            long[] amounts =
                    merged.computeIfAbsent(
                            (long) tileX << 32 | tileZ & 0xFFFFFFFFL, ignored -> new long[2]);
            amounts[0] += remaining[index];
            amounts[1] += initial[index];
        }
        int[] tileXs = new int[merged.size()];
        int[] tileZs = new int[merged.size()];
        int[] colors = new int[merged.size()];
        int index = 0;
        for (Map.Entry<Long, long[]> tile : merged.entrySet()) {
            tileXs[index] = (int) (tile.getKey() >> 32);
            tileZs[index] = (int) (long) tile.getKey();
            colors[index] = OreMapPalette.shade(rgb, tile.getValue()[0], tile.getValue()[1]);
            index++;
        }
        return new Tiles(step, tileXs, tileZs, colors);
    }

    /**
     * Tile {@code i} covers offsets [xs[i], xs[i] + step) x [zs[i], zs[i] + step) from the centre.
     */
    record Tiles(int step, int[] xs, int[] zs, int[] colors) {
        int size() {
            return xs.length;
        }
    }
}

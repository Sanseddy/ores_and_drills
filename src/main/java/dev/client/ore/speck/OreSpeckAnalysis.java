package dev.client.ore.speck;

import org.jetbrains.annotations.Nullable;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * Reads an original ore texture as a set of real ore specks.
 * <p>
 * A pixel is an ore pixel when its color is closer to the palette of the ore's drop item than to the
 * background rock of the same texture. The background is modelled from the texture itself: pixels that are
 * identical to a known host rock at the same position (vanilla and most modded ores are painted over stone /
 * deepslate / netherrack), plus the dominant colors among pixels that do not almost exactly match the
 * palette. A fixed distance threshold alone cannot work: a grey stone tone can be closer to raw iron's
 * browns than a fleck's own dark rim is. Touching ore pixels (8-neighborhood) form one speck.
 * <p>
 * An overlay texture (a mostly transparent ore layer drawn over a separate rock texture, common in resource
 * packs and some mods) needs none of that: its opaque pixels are the ore.
 * <p>
 * The analysis runs at a chosen resolution, so high resolution resource packs keep their detail; speck
 * types still count pixels of the 16x16 grid (see {@link OreSpeckType#forSize(int, int)}).
 */
public final class OreSpeckAnalysis {
    public static final int GRID = OreSpeckType.BASE_RESOLUTION;
    /** Share of transparent pixels from which a texture is read as an ore overlay. */
    static final double OVERLAY_TRANSPARENT_SHARE = 0.25D;
    static final int HOST_CHANNEL_TOLERANCE = 6;
    static final double MINIMUM_HOST_MATCH_SHARE = 0.25D;
    /** Pixels this close to the palette are certainly ore and never feed the background model. */
    static final double SEED_DISTANCE = 20.0D;
    /** Even when closer to the palette than to the background, a pixel this far from the palette is not ore. */
    static final double MAXIMUM_PALETTE_DISTANCE = 96.0D;
    /** Share of the remaining non-seed pixels whose dominant colors are taken as background. */
    static final double BACKGROUND_COVERAGE = 0.85D;
    /** An unhosted match covering more than this share almost certainly matched the background rock. */
    static final double MAXIMUM_UNHOSTED_COVERAGE = 0.6D;
    private static final int OPAQUE_ALPHA = 128;

    private final List<OreSpeck> specks;
    private final int[] countsByType;
    private final SortedMap<Integer, Integer> sizeHistogram;
    private final Source source;
    private final int resolution;

    private OreSpeckAnalysis(List<OreSpeck> specks, Source source, int resolution) {
        this.specks = List.copyOf(specks);
        this.source = source;
        this.resolution = resolution;
        this.countsByType = new int[OreSpeckType.values().length];
        TreeMap<Integer, Integer> histogram = new TreeMap<>();
        for (OreSpeck speck : specks) {
            countsByType[speck.type().ordinal()]++;
            histogram.merge(speck.size(), 1, Integer::sum);
        }
        this.sizeHistogram = Collections.unmodifiableSortedMap(histogram);
    }

    public static OreSpeckAnalysis empty() {
        return empty(GRID);
    }

    public static OreSpeckAnalysis empty(int resolution) {
        return new OreSpeckAnalysis(List.of(), Source.NONE, resolution);
    }

    /** Analysis on the 16x16 grid. */
    public static OreSpeckAnalysis analyze(ArgbImage ore, @Nullable ArgbImage host, int[] paletteRgb) {
        return analyze(ore, host, paletteRgb, GRID);
    }

    /**
     * @param ore        the original ore block texture (any size; brought to {@code resolution}, first frame)
     * @param host       the host rock texture it was painted on, or {@code null} when unknown
     * @param paletteRgb colors of the ore's drop item ({@code 0xRRGGBB})
     * @param resolution side of the square grid the specks are read and later placed on
     */
    public static OreSpeckAnalysis analyze(ArgbImage ore, @Nullable ArgbImage host, int[] paletteRgb, int resolution) {
        ArgbImage normalizedOre = ore.normalized(resolution);
        ArgbImage normalizedHost = host == null ? null : host.normalized(resolution);
        int[] pixels = normalizedOre.pixels();
        int pixelCount = pixels.length;
        boolean[] opaque = new boolean[pixelCount];
        int transparent = 0;
        for (int index = 0; index < pixelCount; index++) {
            opaque[index] = ArgbImage.alpha(pixels[index]) >= OPAQUE_ALPHA;
            transparent += opaque[index] ? 0 : 1;
        }
        if (transparent >= pixelCount * OVERLAY_TRANSPARENT_SHARE) {
            return transparent == pixelCount
                    ? empty(resolution)
                    : new OreSpeckAnalysis(connectedSpecks(normalizedOre, opaque), Source.OVERLAY, resolution);
        }

        boolean[] candidate = new boolean[pixelCount];
        boolean[] knownBackground = new boolean[pixelCount];
        for (int index = 0; index < pixelCount; index++) {
            if (ArgbImage.alpha(pixels[index]) < OPAQUE_ALPHA) {
                continue;
            }
            knownBackground[index] = normalizedHost != null && sameHostPixel(pixels[index], normalizedHost.pixels()[index]);
            candidate[index] = !knownBackground[index];
        }

        double[] paletteDistance = new double[pixelCount];
        boolean[] seed = new boolean[pixelCount];
        for (int index = 0; index < pixelCount; index++) {
            paletteDistance[index] = paletteRgb.length == 0 ? Double.MAX_VALUE : distanceToPalette(pixels[index], paletteRgb);
            seed[index] = candidate[index] && paletteDistance[index] <= SEED_DISTANCE;
        }

        if (!any(seed)) {
            // The drop shares no colors with the block: with a known host, whatever differs from it is ore.
            return normalizedHost != null && any(candidate)
                    ? new OreSpeckAnalysis(connectedSpecks(normalizedOre, candidate), Source.HOST_DIFFERENCE, resolution)
                    : empty(resolution);
        }

        int[] background = backgroundColors(pixels, knownBackground, candidate, seed);
        boolean[] mask = new boolean[pixelCount];
        for (int index = 0; index < pixelCount; index++) {
            mask[index] = candidate[index]
                    && paletteDistance[index] <= MAXIMUM_PALETTE_DISTANCE
                    && paletteDistance[index] < distanceToPalette(pixels[index], background);
        }

        if (normalizedHost == null && count(mask) > pixelCount * MAXIMUM_UNHOSTED_COVERAGE) {
            return empty(resolution);
        }
        return any(mask)
                ? new OreSpeckAnalysis(connectedSpecks(normalizedOre, mask), normalizedHost != null ? Source.HOST_AND_PALETTE : Source.PALETTE, resolution)
                : empty(resolution);
    }

    /**
     * Exact colors of the known host pixels, then the most common color groups among the other non-seed
     * pixels until the background covers {@link #BACKGROUND_COVERAGE} of all non-seed pixels: rock tones
     * dominate those, while a rare off-palette ore tone (a fleck's rim) stays out of the background model.
     * Known host pixels count first, so with a well-matched host the leftover ore rims are never added.
     */
    static int[] backgroundColors(int[] pixels, boolean[] knownBackground, boolean[] candidate, boolean[] seed) {
        Set<Integer> colors = new LinkedHashSet<>();
        Map<Integer, List<Integer>> groups = new HashMap<>();
        int nonSeed = 0;
        int covered = 0;
        for (int index = 0; index < pixels.length; index++) {
            int rgb = pixels[index] & 0xFFFFFF;
            if (knownBackground[index]) {
                colors.add(rgb);
                covered++;
                nonSeed++;
            } else if (candidate[index] && !seed[index]) {
                int group = ((rgb >> 20) & 0xF) << 8 | ((rgb >> 12) & 0xF) << 4 | ((rgb >> 4) & 0xF);
                groups.computeIfAbsent(group, ignored -> new ArrayList<>()).add(rgb);
                nonSeed++;
            }
        }

        List<List<Integer>> ranked = new ArrayList<>(groups.values());
        ranked.sort(Comparator.comparingInt((List<Integer> group) -> group.size()).reversed());
        for (List<Integer> group : ranked) {
            if (covered >= nonSeed * BACKGROUND_COVERAGE) {
                break;
            }
            colors.addAll(group);
            covered += group.size();
        }
        return colors.stream().mapToInt(Integer::intValue).toArray();
    }

    /**
     * Picks the candidate texture sharing the most identical pixels (at the same positions) with the ore
     * texture, or {@code null} when none shares at least {@link #MINIMUM_HOST_MATCH_SHARE} of them.
     */
    @Nullable
    public static ArgbImage pickHost(ArgbImage ore, List<ArgbImage> candidates) {
        return pickHost(ore, candidates, GRID);
    }

    @Nullable
    public static ArgbImage pickHost(ArgbImage ore, List<ArgbImage> candidates, int resolution) {
        ArgbImage normalizedOre = ore.normalized(resolution);
        int opaque = 0;
        for (int color : normalizedOre.pixels()) {
            if (ArgbImage.alpha(color) >= OPAQUE_ALPHA) {
                opaque++;
            }
        }

        ArgbImage best = null;
        int bestMatches = (int) Math.ceil(opaque * MINIMUM_HOST_MATCH_SHARE);
        for (ArgbImage candidate : candidates) {
            int matches = hostMatchCount(normalizedOre, candidate.normalized(resolution));
            if (matches >= bestMatches && (best == null || matches > bestMatches)) {
                best = candidate;
                bestMatches = matches;
            }
        }
        return best;
    }

    static int hostMatchCount(ArgbImage normalizedOre, ArgbImage normalizedHost) {
        int matches = 0;
        for (int index = 0; index < normalizedOre.pixels().length; index++) {
            int color = normalizedOre.pixels()[index];
            if (ArgbImage.alpha(color) >= OPAQUE_ALPHA && sameHostPixel(color, normalizedHost.pixels()[index])) {
                matches++;
            }
        }
        return matches;
    }

    /**
     * Last-resort templates for textures nothing could be detected on (unusual modded art, or a drop texture
     * sharing no colors with the block): small hand-shaped specks shaded with the drop palette, so the deposit
     * still reads as ore instead of plain rock.
     */
    public static OreSpeckAnalysis synthetic(int[] paletteRgb) {
        return synthetic(paletteRgb, GRID);
    }

    /** Synthetic templates drawn at {@code resolution}: each 16x16 pixel of a shape becomes a square block. */
    public static OreSpeckAnalysis synthetic(int[] paletteRgb, int resolution) {
        if (paletteRgb.length == 0) {
            return empty(resolution);
        }
        int scale = Math.max(1, resolution / GRID);

        int[] darkToLight = Arrays.stream(paletteRgb)
                .boxed()
                .sorted(Comparator.comparingInt(color -> brightness(color)))
                .mapToInt(Integer::intValue)
                .toArray();
        String[][] shapes = {
                {".XX", "XXX", ".X."},
                {"XX.", "XXX"},
                {"XX", "X."},
                {"XX", "XX"},
                {".X", "XX"},
                {"XX"},
                {"X"},
                {"X"}
        };

        List<OreSpeck> specks = new ArrayList<>(shapes.length);
        for (String[] shape : shapes) {
            List<int[]> pixels = new ArrayList<>();
            int width = shape[0].length();
            int height = shape.length;
            for (int y = 0; y < height; y++) {
                for (int x = 0; x < width; x++) {
                    if (shape[y].charAt(x) == 'X') {
                        pixels.add(new int[] {x, y});
                    }
                }
            }

            int count = pixels.size() * scale * scale;
            int[] xs = new int[count];
            int[] ys = new int[count];
            int[] colors = new int[count];
            int span = Math.max(1, width + height - 2);
            int out = 0;
            for (int[] cell : pixels) {
                // Light from the top-left, like vanilla ore flecks.
                double light = 1.0D - (cell[0] + cell[1]) / (double) span;
                int shade = (int) Math.round(light * (darkToLight.length - 1));
                int color = 0xFF000000 | (darkToLight[shade] & 0xFFFFFF);
                for (int dy = 0; dy < scale; dy++) {
                    for (int dx = 0; dx < scale; dx++) {
                        xs[out] = cell[0] * scale + dx;
                        ys[out] = cell[1] * scale + dy;
                        colors[out++] = color;
                    }
                }
            }
            specks.add(OreSpeck.fromAbsolutePixels(xs, ys, colors, resolution));
        }
        return new OreSpeckAnalysis(specks, Source.SYNTHETIC, resolution);
    }

    /** Groups mask pixels into 8-connected fragments, ordered by their position on the source texture. */
    static List<OreSpeck> connectedSpecks(ArgbImage image, boolean[] mask) {
        int width = image.width();
        int height = image.height();
        boolean[] visited = new boolean[mask.length];
        List<OreSpeck> specks = new ArrayList<>();
        Deque<Integer> queue = new ArrayDeque<>();
        for (int start = 0; start < mask.length; start++) {
            if (!mask[start] || visited[start]) {
                continue;
            }

            List<Integer> component = new ArrayList<>();
            visited[start] = true;
            queue.add(start);
            while (!queue.isEmpty()) {
                int index = queue.poll();
                component.add(index);
                int x = index % width;
                int y = index / width;
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        int nx = x + dx;
                        int ny = y + dy;
                        if ((dx == 0 && dy == 0) || nx < 0 || ny < 0 || nx >= width || ny >= height) {
                            continue;
                        }
                        int neighbor = ny * width + nx;
                        if (mask[neighbor] && !visited[neighbor]) {
                            visited[neighbor] = true;
                            queue.add(neighbor);
                        }
                    }
                }
            }

            int[] xs = new int[component.size()];
            int[] ys = new int[component.size()];
            int[] colors = new int[component.size()];
            for (int pixel = 0; pixel < component.size(); pixel++) {
                int index = component.get(pixel);
                xs[pixel] = index % width;
                ys[pixel] = index / width;
                colors[pixel] = image.pixels()[index];
            }
            specks.add(OreSpeck.fromAbsolutePixels(xs, ys, colors, width));
        }
        return specks;
    }

    static double distanceToPalette(int argb, int[] paletteRgb) {
        double best = Double.MAX_VALUE;
        for (int color : paletteRgb) {
            best = Math.min(best, colorDistance(argb, color));
        }
        return best;
    }

    /** Weighted RGB distance (green matters most to the eye), in roughly the same 0..255 units as a channel. */
    static double colorDistance(int first, int second) {
        int red = ArgbImage.red(first) - ArgbImage.red(second);
        int green = ArgbImage.green(first) - ArgbImage.green(second);
        int blue = ArgbImage.blue(first) - ArgbImage.blue(second);
        return Math.sqrt((2.0D * red * red + 4.0D * green * green + 3.0D * blue * blue) / 9.0D);
    }

    private static boolean sameHostPixel(int color, int hostColor) {
        return ArgbImage.alpha(hostColor) >= OPAQUE_ALPHA
                && Math.abs(ArgbImage.red(color) - ArgbImage.red(hostColor)) <= HOST_CHANNEL_TOLERANCE
                && Math.abs(ArgbImage.green(color) - ArgbImage.green(hostColor)) <= HOST_CHANNEL_TOLERANCE
                && Math.abs(ArgbImage.blue(color) - ArgbImage.blue(hostColor)) <= HOST_CHANNEL_TOLERANCE;
    }

    private static int brightness(int rgb) {
        return Math.max(ArgbImage.red(rgb), Math.max(ArgbImage.green(rgb), ArgbImage.blue(rgb)));
    }

    private static boolean any(boolean[] mask) {
        for (boolean value : mask) {
            if (value) {
                return true;
            }
        }
        return false;
    }

    private static int count(boolean[] mask) {
        int count = 0;
        for (boolean value : mask) {
            if (value) {
                count++;
            }
        }
        return count;
    }

    /** All specks of the original texture, ordered by position; this count is the "normal" fill. */
    public List<OreSpeck> specks() {
        return specks;
    }

    public int total() {
        return specks.size();
    }

    public int count(OreSpeckType type) {
        return countsByType[type.ordinal()];
    }

    public List<OreSpeck> specksOf(OreSpeckType type) {
        return specks.stream().filter(speck -> speck.type() == type).toList();
    }

    /** Speck size (pixels) to number of specks of that size. */
    public SortedMap<Integer, Integer> sizeHistogram() {
        return sizeHistogram;
    }

    public int totalPixels() {
        int total = 0;
        for (OreSpeck speck : specks) {
            total += speck.size();
        }
        return total;
    }

    /** Side of the square grid the specks were read on; layouts are generated at the same resolution. */
    public int resolution() {
        return resolution;
    }

    public Source source() {
        return source;
    }

    public String summary() {
        return total() + " specks (type1=" + count(OreSpeckType.TYPE_1)
                + ", type2=" + count(OreSpeckType.TYPE_2)
                + ", type3=" + count(OreSpeckType.TYPE_3)
                + ", type4=" + count(OreSpeckType.TYPE_4)
                + ", sizes=" + sizeHistogram + ", source=" + source + ", resolution=" + resolution + ")";
    }

    public enum Source {
        /** Opaque pixels of a mostly transparent ore overlay texture. */
        OVERLAY,
        /** Non-host pixels matching the drop palette. */
        HOST_AND_PALETTE,
        /** Nothing matched the palette, so every non-host pixel counts as ore. */
        HOST_DIFFERENCE,
        /** No host could be identified; drop palette match only. */
        PALETTE,
        SYNTHETIC,
        NONE
    }
}

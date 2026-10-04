package dev.client.ore.speck;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

/**
 * Re-places the specks detected on an original ore texture onto a fresh 16x16 grid and derives every fill
 * state from that one plan, so states are nested: a poorer state is always a subset of a richer one. That
 * lets a faint trace of the initial state sit under the current state without contradicting it. Several
 * plans with different seeds give different arrangements of the same specks.
 * <p>
 * A layout uses the resolution of its analysis; all areas, gaps and noise cells below are given for 16x16 and
 * scale with it, so a 32x32 resource pack gets a 28x28 inner area, two-pixel gaps and so on.
 * <ul>
 *     <li>Normal ({@code fill == 1}): every original speck exactly once, real shapes and colors, placed at
 *     random inside the inner 14x14 area, largest type first.</li>
 *     <li>Depleted ({@code fill < 1}): whole specks disappear in a fixed random order, type 1 specks first, so
 *     the last ones left are always at least type 2. When that would be
 *     too coarse (too few specks, or one speck holding most of the ore) a smooth noise mask instead clips
 *     the specks pixel by pixel, so fragments inside the noise area get cut partially or completely.</li>
 *     <li>Overfilled ({@code fill > 1}): extra specks, mostly of types 1-2, type 3 under stronger overfill
 *     and type 4 only rarely, may use the whole 16x16 area.</li>
 * </ul>
 */
public final class OreSpeckLayout {
    public static final int BASE_SIZE = OreSpeckAnalysis.GRID;
    static final int NOISE_MODE_MAXIMUM_SPECKS = 3;
    static final double NOISE_MODE_DOMINANT_SHARE = 0.45D;
    private static final int BEST_CANDIDATE_SAMPLES = 3;
    /** Noise cell side on the 16x16 grid. */
    private static final int NOISE_CELL = 4;
    private static final OreSpeckType[] PLACEMENT_ORDER = {
            OreSpeckType.TYPE_4, OreSpeckType.TYPE_3, OreSpeckType.TYPE_2, OreSpeckType.TYPE_1
    };

    private final int size;
    private final int baseCount;
    private final List<Placement> normal;
    /** {@code keepRank[i]} is how many specks must remain before {@code normal[i]} becomes visible. */
    private final int[] keepRank;
    private final List<Placement> extras;
    private final boolean noiseMode;
    /** Pixel indices of the normal state, most noise-resistant first (noise mode only). */
    private final int[] noiseOrder;

    private OreSpeckLayout(
            int size,
            int baseCount,
            List<Placement> normal,
            int[] keepRank,
            List<Placement> extras,
            boolean noiseMode,
            int[] noiseOrder
    ) {
        this.size = size;
        this.baseCount = baseCount;
        this.normal = List.copyOf(normal);
        this.keepRank = keepRank;
        this.extras = List.copyOf(extras);
        this.noiseMode = noiseMode;
        this.noiseOrder = noiseOrder;
    }

    /**
     * @param maximumFill the richest fill factor that will be requested; bounds how many extras are planned
     */
    public static OreSpeckLayout plan(OreSpeckAnalysis analysis, long seed, double maximumFill) {
        Random random = new Random(seed);
        int baseCount = analysis.total();
        int size = analysis.resolution();
        Grid grid = new Grid(size);

        List<Placement> normal = new ArrayList<>(baseCount);
        for (OreSpeckType type : PLACEMENT_ORDER) {
            List<OreSpeck> ofType = new ArrayList<>(analysis.specksOf(type));
            Collections.shuffle(ofType, random);
            for (OreSpeck speck : ofType) {
                Placement placement = grid.place(speck, false, random);
                if (placement != null) {
                    normal.add(placement);
                }
            }
        }

        // Visible specks (type 2 and larger) stay longest; 1-2 pixel specks are only an addition on top of them,
        // so a nearly empty block never shows a couple of lone pixels.
        List<Integer> visible = new ArrayList<>();
        List<Integer> tiny = new ArrayList<>();
        for (int index = 0; index < normal.size(); index++) {
            (normal.get(index).speck().type() == OreSpeckType.TYPE_1 ? tiny : visible).add(index);
        }
        Collections.shuffle(visible, random);
        Collections.shuffle(tiny, random);
        List<Integer> removal = new ArrayList<>(visible);
        removal.addAll(tiny);
        int[] keepRank = new int[normal.size()];
        for (int rank = 0; rank < removal.size(); rank++) {
            keepRank[removal.get(rank)] = rank;
        }

        List<Placement> extras = new ArrayList<>();
        int extraCount = baseCount == 0 ? 0 : extrasFor(maximumFill, baseCount);
        for (int index = 0; index < extraCount; index++) {
            OreSpeck template = extraTemplate(analysis, index, baseCount, random);
            if (template == null) {
                break;
            }
            Placement placement = grid.place(template, true, random);
            if (placement != null) {
                extras.add(placement);
            }
        }

        boolean noiseMode = useNoiseMode(normal);
        int[] noiseOrder = noiseMode ? noiseOrder(normal, random, size) : new int[0];
        return new OreSpeckLayout(size, baseCount, normal, keepRank, extras, noiseMode, noiseOrder);
    }

    /** Whole-speck removal is too coarse with very few specks, or when one speck holds most of the ore. */
    static boolean useNoiseMode(List<Placement> normal) {
        if (normal.isEmpty()) {
            return false;
        }
        if (normal.size() <= NOISE_MODE_MAXIMUM_SPECKS) {
            return true;
        }
        int total = 0;
        int largest = 0;
        for (Placement placement : normal) {
            total += placement.speck().size();
            largest = Math.max(largest, placement.speck().size());
        }
        return largest > total * NOISE_MODE_DOMINANT_SHARE;
    }

    /** Number of whole specks shown for a depleted fill; never zero while any ore remains. */
    static int keptFor(double fill, int count) {
        if (count == 0 || fill <= 0.0D) {
            return 0;
        }
        if (fill >= 1.0D) {
            return count;
        }
        return Math.max(1, Math.min(count, (int) Math.round(fill * count)));
    }

    static int extrasFor(double fill, int baseCount) {
        if (fill <= 1.0D || baseCount == 0) {
            return 0;
        }
        return Math.max(1, (int) Math.round((fill - 1.0D) * baseCount));
    }

    /**
     * Extras appear in sequence as the fill grows, so the type of the {@code index}-th extra is decided by
     * the fill at which it first appears: early extras are small, later ones may be type 3, very late ones
     * occasionally type 4.
     */
    private static OreSpeck extraTemplate(OreSpeckAnalysis analysis, int index, int baseCount, Random random) {
        double appearsAt = 1.0D + (index + 1.0D) / baseCount;
        double[] weights = {
                0.55D,
                0.45D,
                appearsAt > 1.5D ? Math.min(0.35D, 0.7D * (appearsAt - 1.5D)) : 0.0D,
                appearsAt >= 1.9D ? 0.05D : 0.0D
        };
        double roll = random.nextDouble() * Arrays.stream(weights).sum();
        OreSpeckType type = OreSpeckType.TYPE_1;
        for (int candidate = 0; candidate < weights.length; candidate++) {
            roll -= weights[candidate];
            if (roll < 0.0D) {
                type = OreSpeckType.values()[candidate];
                break;
            }
        }
        return templateOfType(analysis, type, random);
    }

    /** An original speck of that type; otherwise a connected piece of a larger one; otherwise the next smaller type. */
    static OreSpeck templateOfType(OreSpeckAnalysis analysis, OreSpeckType type, Random random) {
        int resolution = analysis.resolution();
        for (int ordinal = type.ordinal(); ordinal >= 0; ordinal--) {
            OreSpeckType candidateType = OreSpeckType.values()[ordinal];
            List<OreSpeck> originals = analysis.specksOf(candidateType);
            if (!originals.isEmpty()) {
                return originals.get(random.nextInt(originals.size()));
            }

            List<OreSpeck> larger = analysis.specks().stream()
                    .filter(speck -> speck.size() > candidateType.maximumPixels(resolution))
                    .toList();
            if (!larger.isEmpty() && candidateType != OreSpeckType.TYPE_4) {
                OreSpeck source = larger.get(random.nextInt(larger.size()));
                int minimum = candidateType.minimumPixels(resolution);
                int span = candidateType.maximumPixels(resolution) - minimum + 1;
                return source.cropped(minimum + random.nextInt(span), random);
            }
        }
        return analysis.specks().isEmpty() ? null : analysis.specks().get(0);
    }

    private static int[] noiseOrder(List<Placement> normal, Random random, int size) {
        int cell = NOISE_CELL * scaleOf(size);
        int lattice = size / cell + 1;
        double[] values = new double[lattice * lattice];
        for (int index = 0; index < values.length; index++) {
            values[index] = random.nextDouble();
        }

        List<int[]> pixels = new ArrayList<>();
        for (Placement placement : normal) {
            for (int pixel = 0; pixel < placement.speck().size(); pixel++) {
                int x = placement.x() + placement.speck().pixelX(pixel);
                int y = placement.y() + placement.speck().pixelY(pixel);
                double value = smoothNoise(values, lattice, (x + 0.5D) / cell, (y + 0.5D) / cell);
                pixels.add(new int[] {y * size + x, (int) Math.round(value * 1_000_000)});
            }
        }
        pixels.sort(Comparator.<int[]>comparingInt(entry -> -entry[1]).thenComparingInt(entry -> entry[0]));
        return pixels.stream().mapToInt(entry -> entry[0]).toArray();
    }

    private static double smoothNoise(double[] values, int lattice, double x, double y) {
        int cellX = Math.min(lattice - 2, (int) Math.floor(x));
        int cellY = Math.min(lattice - 2, (int) Math.floor(y));
        double fractionX = smoothstep(x - cellX);
        double fractionY = smoothstep(y - cellY);
        double top = lerp(values[cellY * lattice + cellX], values[cellY * lattice + cellX + 1], fractionX);
        double bottom = lerp(values[(cellY + 1) * lattice + cellX], values[(cellY + 1) * lattice + cellX + 1], fractionX);
        return lerp(top, bottom, fractionY);
    }

    private static double smoothstep(double value) {
        return value * value * (3.0D - 2.0D * value);
    }

    private static double lerp(double from, double to, double progress) {
        return from + (to - from) * progress;
    }

    /**
     * Fewest whole specks that together reach a type 2 speck's size. Normally that is the single first speck,
     * which is type 2 or larger; it only grows for textures made of 1-2 pixel specks alone.
     */
    private int minimumVisibleCount() {
        OreSpeck[] byRank = new OreSpeck[normal.size()];
        for (int index = 0; index < normal.size(); index++) {
            byRank[keepRank[index]] = normal.get(index).speck();
        }
        int pixels = 0;
        for (int rank = 0; rank < byRank.length; rank++) {
            pixels += byRank[rank].size();
            if (pixels >= OreSpeckType.TYPE_2.minimumPixels(size)) {
                return rank + 1;
            }
        }
        return byRank.length;
    }

    /** Specks visible at this fill factor, in placement order (normal ones first, then extras). */
    public List<Placement> placementsFor(double fill) {
        if (fill <= 0.0D || normal.isEmpty()) {
            return List.of();
        }

        List<Placement> result = new ArrayList<>(normal.size() + extras.size());
        int kept = noiseMode ? normal.size() : Math.max(keptFor(fill, normal.size()), minimumVisibleCount());
        for (int index = 0; index < normal.size(); index++) {
            if (keepRank[index] < kept) {
                result.add(normal.get(index));
            }
        }
        result.addAll(extras.subList(0, Math.min(extras.size(), extrasFor(fill, baseCount))));
        return result;
    }

    /** The ore overlay for this fill factor: original speck colors on a transparent grid of {@link #size()}. */
    public ArgbImage renderOre(double fill) {
        int[] pixels = new int[size * size];
        for (Placement placement : placementsFor(fill)) {
            OreSpeck speck = placement.speck();
            for (int pixel = 0; pixel < speck.size(); pixel++) {
                int x = placement.x() + speck.pixelX(pixel);
                int y = placement.y() + speck.pixelY(pixel);
                pixels[y * size + x] = speck.pixelColor(pixel);
            }
        }

        if (noiseMode && fill > 0.0D && fill < 1.0D && noiseOrder.length > 0) {
            int minimum = Math.min(noiseOrder.length, OreSpeckType.TYPE_2.minimumPixels(size));
            int keep = Math.max(minimum, (int) Math.round(fill * noiseOrder.length));
            for (int rank = keep; rank < noiseOrder.length; rank++) {
                pixels[noiseOrder[rank]] = 0;
            }
        }
        return new ArgbImage(size, size, pixels);
    }

    /**
     * The trace of this fill factor: exactly the pixels of {@link #renderOre}, with saturation removed so the
     * mined-out imprint carries no ore color, only its brightness.
     */
    public ArgbImage renderTrace(double fill) {
        int[] pixels = renderOre(fill).pixels();
        for (int index = 0; index < pixels.length; index++) {
            int color = pixels[index];
            if (ArgbImage.alpha(color) != 0) {
                int luminance = (54 * ArgbImage.red(color) + 183 * ArgbImage.green(color) + 19 * ArgbImage.blue(color) + 128) >> 8;
                pixels[index] = color & 0xFF000000 | luminance << 16 | luminance << 8 | luminance;
            }
        }
        return new ArgbImage(size, size, pixels);
    }

    /**
     * Which of {@code variants} arrangements a block face shows. With 8 variants the lattice {@code x + 3y + 2z}
     * gives any two blocks that touch by a side or an edge within one wall plane different arrangements (every
     * such step changes the sum by 1, 2, 3, 4, 5 or 6 mod 8), and a random shift per 8x8x8 region breaks the
     * lattice's repetition; only blocks across a region border may occasionally coincide.
     */
    public static int variantFor(int x, int y, int z, int face, int variants) {
        long region = mix((long) Math.floorDiv(x, 8) * 0x9E3779B97F4A7C15L
                ^ (long) Math.floorDiv(y, 8) * 0xC2B2AE3D27D4EB4FL
                ^ (long) Math.floorDiv(z, 8) * 0x165667B19E3779F9L);
        long faceShift = mix(face * 0xD6E8FEB86659FD93L + 1L);
        return (int) Math.floorMod(x + 3L * y + 2L * z + region + faceShift, (long) variants);
    }

    private static long mix(long value) {
        value = (value ^ (value >>> 31)) * 0xBF58476D1CE4E5B9L;
        value = (value ^ (value >>> 29)) * 0x94D049BB133111EBL;
        return value ^ (value >>> 32);
    }

    /** Side of the generated textures, the resolution of the analysis. */
    public int size() {
        return size;
    }

    /** First coordinate of the inner area (1 on a 16x16 grid). */
    public int innerMin() {
        return scaleOf(size);
    }

    /** Exclusive end of the inner area (15 on a 16x16 grid). */
    public int innerMax() {
        return size - scaleOf(size);
    }

    private static int scaleOf(int size) {
        return Math.max(1, size / BASE_SIZE);
    }

    /** Number of specks detected on the original texture: the normal fill. */
    public int baseCount() {
        return baseCount;
    }

    public List<Placement> normalPlacements() {
        return normal;
    }

    public List<Placement> extraPlacements() {
        return extras;
    }

    public boolean noiseMode() {
        return noiseMode;
    }

    /** One speck at its final top-left position on the generated texture. */
    public record Placement(OreSpeck speck, int x, int y, boolean extra) {
    }

    /** Occupancy of the generated texture while specks are being placed. */
    private static final class Grid {
        private final int size;
        private final int scale;
        private final boolean[] occupied;
        private final List<double[]> centers = new ArrayList<>();

        Grid(int size) {
            this.size = size;
            this.scale = scaleOf(size);
            this.occupied = new boolean[size * size];
        }

        /**
         * Normal specks prefer the inner 14x14 area with a one-pixel gap to other specks; extras use the full
         * 16x16. Each falls back step by step (full area, then touching allowed) rather than being dropped.
         */
        Placement place(OreSpeck speck, boolean extra, Random random) {
            int[][] attempts = extra
                    ? new int[][] {{0, size, 1}, {0, size, 0}}
                    : new int[][] {{scale, size - scale, 1}, {0, size, 1}, {scale, size - scale, 0}, {0, size, 0}};
            for (int[] attempt : attempts) {
                List<int[]> candidates = candidates(speck, attempt[0], attempt[1], attempt[2] == 1);
                if (!candidates.isEmpty()) {
                    int[] chosen = bestCandidate(speck, candidates, random);
                    mark(speck, chosen[0], chosen[1]);
                    return new Placement(speck, chosen[0], chosen[1], extra);
                }
            }
            return null;
        }

        private List<int[]> candidates(OreSpeck speck, int minimum, int maximum, boolean keepGap) {
            List<int[]> candidates = new ArrayList<>();
            for (int y = minimum; y + speck.height() <= maximum; y++) {
                for (int x = minimum; x + speck.width() <= maximum; x++) {
                    if (fits(speck, x, y, keepGap)) {
                        candidates.add(new int[] {x, y});
                    }
                }
            }
            return candidates;
        }

        private boolean fits(OreSpeck speck, int x, int y, boolean keepGap) {
            int reach = keepGap ? scale : 0;
            for (int pixel = 0; pixel < speck.size(); pixel++) {
                int px = x + speck.pixelX(pixel);
                int py = y + speck.pixelY(pixel);
                for (int dy = -reach; dy <= reach; dy++) {
                    for (int dx = -reach; dx <= reach; dx++) {
                        int nx = px + dx;
                        int ny = py + dy;
                        if (nx >= 0 && ny >= 0 && nx < size && ny < size && occupied[ny * size + nx]) {
                            return false;
                        }
                    }
                }
            }
            return true;
        }

        /** Best of a few random candidates by distance to already placed specks: random, but evenly spread. */
        private int[] bestCandidate(OreSpeck speck, List<int[]> candidates, Random random) {
            int[] best = null;
            double bestDistance = -1.0D;
            for (int sample = 0; sample < BEST_CANDIDATE_SAMPLES; sample++) {
                int[] candidate = candidates.get(random.nextInt(candidates.size()));
                double centerX = candidate[0] + speck.width() / 2.0D;
                double centerY = candidate[1] + speck.height() / 2.0D;
                double nearest = Double.MAX_VALUE;
                for (double[] center : centers) {
                    double dx = center[0] - centerX;
                    double dy = center[1] - centerY;
                    nearest = Math.min(nearest, dx * dx + dy * dy);
                }
                if (nearest > bestDistance) {
                    bestDistance = nearest;
                    best = candidate;
                }
            }
            return best;
        }

        private void mark(OreSpeck speck, int x, int y) {
            for (int pixel = 0; pixel < speck.size(); pixel++) {
                occupied[(y + speck.pixelY(pixel)) * size + x + speck.pixelX(pixel)] = true;
            }
            centers.add(new double[] {x + speck.width() / 2.0D, y + speck.height() / 2.0D});
        }
    }
}

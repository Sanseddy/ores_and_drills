package dev.world.geology;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Cave analyser: turns a lattice of void probes into an approximate map of caves. Each probe says
 * whether its point is rock, open (air, water or lava) or above the terrain surface. Connected open
 * probes form caves; a wall point is placed where the density changes sign between an open probe
 * and a rock neighbour, plus the analyser's positioning noise. The map is only as fine as the
 * lattice: voids between probes are not seen and nothing is completed beyond what was probed.
 *
 * <p>Errors are deterministic per survey configuration: false readings in rock, missed small voids
 * and wall noise all derive from {@code seed}.
 */
public final class CaveAnalysis {
    public static final byte SOLID = 0;
    public static final byte AIR = 1;
    public static final byte WATER = 2;
    public static final byte LAVA = 3;

    /** Above the terrain surface: sky or sea, never a cave. */
    public static final byte SKY = 4;

    /** Not read from the world; resolved from the terrain model. */
    public static final byte UNKNOWN = 5;

    private static final long FALSE_SALT = 0x46414C5345L;
    private static final long DETECT_SALT = 0x444554454354L;
    private static final long JITTER_SALT = 0x4A4954L;
    private static final long KEEP_SALT = 0x4B454550L;
    private static final int MAXIMUM_CAVES = 256;
    private static final int MAXIMUM_LINKS = 256;

    private CaveAnalysis() {}

    /** Open space in the undisturbed terrain, for probes the world could not answer. */
    public interface Terrain {
        Terrain SOLID_TERRAIN =
                new Terrain() {
                    @Override
                    public double density(int x, int y, int z) {
                        return 1.0D;
                    }

                    @Override
                    public byte fill(int y) {
                        return AIR;
                    }
                };

        /** Generator density: above zero is rock. Must be safe on a worker thread. */
        double density(int x, int y, int z);

        /** What fills open space at height {@code y}. */
        byte fill(int y);
    }

    /** Shape class of a cave from its volume and elongation. */
    public enum Shape {
        SMALL_VOID,
        TUNNEL,
        CHAMBER,
        CAVERN;

        public static Shape byId(int id) {
            Shape[] values = values();
            return id >= 0 && id < values.length ? values[id] : CHAMBER;
        }
    }

    /**
     * The probe lattice: {@code nx × ny × nz} probes {@code spacing} apart, starting at ({@code
     * originX}, {@code topY}, {@code originZ}) and going down in Y. Index {@code (iy·nx + ix)·nz +
     * iz}.
     *
     * @param density signed density per probe, above zero for rock
     * @param fill fill code per probe ({@link #SOLID} … {@link #UNKNOWN})
     */
    public record Grid(
            double originX,
            double topY,
            double originZ,
            double spacing,
            int nx,
            int ny,
            int nz,
            float[] density,
            byte[] fill) {
        public Grid {
            int size = nx * ny * nz;
            if (density.length != size || fill.length != size) {
                throw new IllegalArgumentException("Cave grid arrays do not match its size");
            }
        }

        public int index(int ix, int iy, int iz) {
            return (iy * nx + ix) * nz + iz;
        }

        public double x(int ix) {
            return originX + ix * spacing;
        }

        public double y(int iy) {
            return topY - iy * spacing;
        }

        public double z(int iz) {
            return originZ + iz * spacing;
        }

        public int size() {
            return nx * ny * nz;
        }
    }

    /** Analyser settings of one level, see {@link CaveAnalyzer}. */
    public record Settings(
            double jitter,
            double falseRate,
            double detectionCells,
            double weakFactor,
            int maxSamples) {
        public static Settings of(int level) {
            return new Settings(
                    CaveAnalyzer.jitter(level),
                    CaveAnalyzer.falseRate(level),
                    CaveAnalyzer.detectionCells(level),
                    CaveAnalyzer.weakFactor(level),
                    CaveAnalyzer.maxSamples(level));
        }
    }

    /**
     * A mapped wall point.
     *
     * @param cave index into {@link Result#caves()}, or -1 when the cave was too small to list
     */
    public record Wall(double x, double y, double z, int cave, byte fill, float confidence) {}

    /**
     * A detected cave.
     *
     * @param cells open probes
     * @param volume estimated open volume in cubic blocks
     * @param axes half-lengths of the principal axes, longest first
     * @param confidence chance the analyser had of detecting it
     */
    public record Cave(
            int cells,
            double volume,
            double x,
            double y,
            double z,
            double minX,
            double minY,
            double minZ,
            double maxX,
            double maxY,
            double maxZ,
            double[] axes,
            Shape shape,
            byte fill,
            double confidence) {}

    /**
     * A suspected connection: two caves separated by a single rock probe. Whether a passage really
     * runs between the end points is decided by resampling, see {@link CaveAnalyzer#confirmStep}.
     */
    public record Link(
            int caveA,
            int caveB,
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz) {}

    public record Result(List<Wall> walls, List<Cave> caves, List<Link> links) {}

    /** Resolves {@link #UNKNOWN} columns from the terrain model, top down. */
    public static void resolveUnknown(Grid grid, Terrain terrain, BooleanSupplier cancelled) {
        for (int ix = 0; ix < grid.nx(); ix++) {
            if (cancelled.getAsBoolean()) {
                return;
            }
            for (int iz = 0; iz < grid.nz(); iz++) {
                if (grid.fill()[grid.index(ix, 0, iz)] != UNKNOWN) {
                    continue;
                }
                int x = (int) Math.floor(grid.x(ix));
                int z = (int) Math.floor(grid.z(iz));
                boolean belowGround = false;
                for (int iy = 0; iy < grid.ny(); iy++) {
                    int index = grid.index(ix, iy, iz);
                    int y = (int) Math.floor(grid.y(iy));
                    double density = terrain.density(x, y, z);
                    grid.density()[index] = (float) Math.max(-1.0D, Math.min(1.0D, density));
                    if (density > 0.0D) {
                        belowGround = true;
                        grid.fill()[index] = SOLID;
                    } else {
                        grid.fill()[index] = belowGround ? terrain.fill(y) : SKY;
                    }
                }
            }
        }
    }

    public static boolean open(byte fill) {
        return fill == AIR || fill == WATER || fill == LAVA;
    }

    /** Analyses a fully resolved grid; {@code null} when cancelled. */
    public static Result analyse(
            Grid grid, Settings settings, long seed, BooleanSupplier cancelled) {
        int size = grid.size();
        boolean[] open = new boolean[size];
        boolean[] weak = new boolean[size];
        for (int i = 0; i < size; i++) {
            byte fill = grid.fill()[i];
            if (open(fill)) {
                open[i] = true;
            } else if (fill == SOLID
                    && settings.falseRate() > 0.0D
                    && SeedHash.unit(seed ^ FALSE_SALT, i) < settings.falseRate()) {
                open[i] = true;
                weak[i] = true;
            }
        }
        if (cancelled.getAsBoolean()) {
            return null;
        }

        int[] component = new int[size];
        Arrays.fill(component, -1);
        List<int[]> members = new ArrayList<>();
        List<Boolean> detected = new ArrayList<>();
        List<Double> chances = new ArrayList<>();
        int[] queue = new int[size];
        for (int start = 0; start < size; start++) {
            if (!open[start] || component[start] >= 0) {
                continue;
            }
            int id = members.size();
            int head = 0;
            int tail = 0;
            queue[tail++] = start;
            component[start] = id;
            boolean allWeak = true;
            while (head < tail) {
                int cell = queue[head++];
                allWeak &= weak[cell];
                int iz = cell % grid.nz();
                int rest = cell / grid.nz();
                int ix = rest % grid.nx();
                int iy = rest / grid.nx();
                for (int d = 0; d < 6; d++) {
                    int jx = ix + DX[d];
                    int jy = iy + DY[d];
                    int jz = iz + DZ[d];
                    if (jx < 0 || jy < 0 || jz < 0 || jx >= grid.nx() || jy >= grid.ny()) {
                        continue;
                    }
                    if (jz >= grid.nz()) {
                        continue;
                    }
                    int next = grid.index(jx, jy, jz);
                    if (open[next] && component[next] < 0) {
                        component[next] = id;
                        queue[tail++] = next;
                    }
                }
            }
            members.add(Arrays.copyOf(queue, tail));
            double chance = 1.0D - Math.exp(-tail / Math.max(1.0E-6D, settings.detectionCells()));
            if (allWeak) {
                chance *= settings.weakFactor();
            }
            chances.add(chance);
            detected.add(SeedHash.unit(seed ^ DETECT_SALT, start) < chance);
            if (cancelled.getAsBoolean()) {
                return null;
            }
        }

        // The largest detected caves are listed; smaller ones still leave wall points.
        Integer[] order = new Integer[members.size()];
        for (int i = 0; i < order.length; i++) {
            order[i] = i;
        }
        Arrays.sort(
                order,
                (a, b) -> {
                    int bySize = Integer.compare(members.get(b).length, members.get(a).length);
                    return bySize != 0 ? bySize : Integer.compare(a, b);
                });
        int[] listed = new int[members.size()];
        Arrays.fill(listed, -1);
        List<Cave> caves = new ArrayList<>();
        for (Integer id : order) {
            if (!detected.get(id) || caves.size() >= MAXIMUM_CAVES) {
                continue;
            }
            listed[id] = caves.size();
            caves.add(describe(grid, members.get(id), chances.get(id)));
        }

        List<Wall> walls = new ArrayList<>();
        double half = grid.spacing() * 0.5D;
        for (int cell = 0; cell < size; cell++) {
            int id = component[cell];
            if (id < 0 || !detected.get(id)) {
                continue;
            }
            int iz = cell % grid.nz();
            int rest = cell / grid.nz();
            int ix = rest % grid.nx();
            int iy = rest / grid.nx();
            for (int d = 0; d < 6; d++) {
                int jx = ix + DX[d];
                int jy = iy + DY[d];
                int jz = iz + DZ[d];
                if (jx < 0 || jy < 0 || jz < 0 || jx >= grid.nx() || jy >= grid.ny()) {
                    continue;
                }
                if (jz >= grid.nz()) {
                    continue;
                }
                int next = grid.index(jx, jy, jz);
                if (open[next] || grid.fill()[next] != SOLID) {
                    continue;
                }
                double t = crossing(grid.density()[cell], grid.density()[next]);
                long edge = SeedHash.combine(seed ^ JITTER_SALT, cell, d);
                double jitter = settings.jitter();
                double x = grid.x(ix) + DX[d] * t * grid.spacing();
                double y = grid.y(iy) - DY[d] * t * grid.spacing();
                double z = grid.z(iz) + DZ[d] * t * grid.spacing();
                x += SeedHash.gaussian(edge, 1) * jitter + (SeedHash.unit(edge, 4) - 0.5D) * half;
                y += SeedHash.gaussian(edge, 2) * jitter;
                z += SeedHash.gaussian(edge, 3) * jitter + (SeedHash.unit(edge, 5) - 0.5D) * half;
                byte fill = weak[cell] ? AIR : grid.fill()[cell];
                walls.add(new Wall(x, y, z, listed[id], fill, (float) (double) chances.get(id)));
            }
        }
        if (walls.size() > settings.maxSamples() && settings.maxSamples() > 0) {
            double keep = settings.maxSamples() / (double) walls.size();
            List<Wall> kept = new ArrayList<>(settings.maxSamples());
            for (int i = 0; i < walls.size(); i++) {
                if (SeedHash.unit(seed ^ KEEP_SALT, i) < keep) {
                    kept.add(walls.get(i));
                }
            }
            walls = kept;
        }
        if (cancelled.getAsBoolean()) {
            return null;
        }
        return new Result(
                List.copyOf(walls), List.copyOf(caves), links(grid, open, component, listed));
    }

    /** Where between an open and a rock probe the density crosses zero, 0–1 from the open one. */
    static double crossing(double openDensity, double rockDensity) {
        double span = rockDensity - openDensity;
        if (!(span > 1.0E-6D)) {
            return 0.5D;
        }
        return Math.max(0.0D, Math.min(1.0D, -openDensity / span));
    }

    private static List<Link> links(Grid grid, boolean[] open, int[] component, int[] listed) {
        Map<Long, Link> byPair = new HashMap<>();
        List<Link> links = new ArrayList<>();
        for (int iy = 0; iy < grid.ny(); iy++) {
            for (int ix = 0; ix < grid.nx(); ix++) {
                for (int iz = 0; iz < grid.nz(); iz++) {
                    int cell = grid.index(ix, iy, iz);
                    if (!open[cell] || component[cell] < 0 || listed[component[cell]] < 0) {
                        continue;
                    }
                    // Look two probes ahead along the positive axes only, so each pair is found
                    // once.
                    for (int d = 0; d < 6; d += 2) {
                        int mx = ix + DX[d];
                        int my = iy + DY[d];
                        int mz = iz + DZ[d];
                        int ex = ix + 2 * DX[d];
                        int ey = iy + 2 * DY[d];
                        int ez = iz + 2 * DZ[d];
                        if (ex >= grid.nx() || ey >= grid.ny() || ez >= grid.nz()) {
                            continue;
                        }
                        int middle = grid.index(mx, my, mz);
                        int end = grid.index(ex, ey, ez);
                        if (open[middle]
                                || grid.fill()[middle] != SOLID
                                || !open[end]
                                || component[end] < 0
                                || listed[component[end]] < 0
                                || component[end] == component[cell]) {
                            continue;
                        }
                        int a = listed[component[cell]];
                        int b = listed[component[end]];
                        long pair = ((long) Math.min(a, b) << 32) | Math.max(a, b);
                        if (byPair.containsKey(pair) || links.size() >= MAXIMUM_LINKS) {
                            continue;
                        }
                        Link link =
                                new Link(
                                        a,
                                        b,
                                        grid.x(ix),
                                        grid.y(iy),
                                        grid.z(iz),
                                        grid.x(ex),
                                        grid.y(ey),
                                        grid.z(ez));
                        byPair.put(pair, link);
                        links.add(link);
                    }
                }
            }
        }
        return List.copyOf(links);
    }

    private static Cave describe(Grid grid, int[] cells, double confidence) {
        double sx = 0.0D;
        double sy = 0.0D;
        double sz = 0.0D;
        double minX = Double.POSITIVE_INFINITY;
        double minY = Double.POSITIVE_INFINITY;
        double minZ = Double.POSITIVE_INFINITY;
        double maxX = Double.NEGATIVE_INFINITY;
        double maxY = Double.NEGATIVE_INFINITY;
        double maxZ = Double.NEGATIVE_INFINITY;
        int[] fills = new int[4];
        for (int cell : cells) {
            int iz = cell % grid.nz();
            int rest = cell / grid.nz();
            int ix = rest % grid.nx();
            int iy = rest / grid.nx();
            double x = grid.x(ix);
            double y = grid.y(iy);
            double z = grid.z(iz);
            sx += x;
            sy += y;
            sz += z;
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
            byte fill = grid.fill()[cell];
            fills[open(fill) ? fill : AIR]++;
        }
        int n = cells.length;
        double cx = sx / n;
        double cy = sy / n;
        double cz = sz / n;
        double[] covariance = new double[6];
        for (int cell : cells) {
            int iz = cell % grid.nz();
            int rest = cell / grid.nz();
            int ix = rest % grid.nx();
            int iy = rest / grid.nx();
            double dx = grid.x(ix) - cx;
            double dy = grid.y(iy) - cy;
            double dz = grid.z(iz) - cz;
            covariance[0] += dx * dx;
            covariance[1] += dy * dy;
            covariance[2] += dz * dz;
            covariance[3] += dx * dy;
            covariance[4] += dx * dz;
            covariance[5] += dy * dz;
        }
        // A probe stands for a cube of the lattice spacing: its own spread is s²/12 per axis.
        double cellVariance = grid.spacing() * grid.spacing() / 12.0D;
        for (int i = 0; i < 6; i++) {
            covariance[i] = covariance[i] / n + (i < 3 ? cellVariance : 0.0D);
        }
        double[] eigen = eigenvalues(covariance);
        double[] axes = new double[3];
        for (int i = 0; i < 3; i++) {
            // A uniform segment of half-length a has variance a²/3.
            axes[i] = Math.sqrt(Math.max(0.0D, eigen[i]) * 3.0D);
        }
        double volume = n * grid.spacing() * grid.spacing() * grid.spacing();
        byte fill = AIR;
        for (byte f = AIR; f <= LAVA; f++) {
            if (fills[f] > fills[fill]) {
                fill = f;
            }
        }
        double half = grid.spacing() * 0.5D;
        return new Cave(
                n,
                volume,
                cx,
                cy,
                cz,
                minX - half,
                minY - half,
                minZ - half,
                maxX + half,
                maxY + half,
                maxZ + half,
                axes,
                shape(volume, axes),
                fill,
                confidence);
    }

    static Shape shape(double volume, double[] axes) {
        if (volume < 64.0D) {
            return Shape.SMALL_VOID;
        }
        double elongation = axes[0] / Math.max(0.5D, axes[1]);
        if (elongation >= 3.0D) {
            return Shape.TUNNEL;
        }
        return volume >= 4000.0D ? Shape.CAVERN : Shape.CHAMBER;
    }

    /**
     * Eigenvalues of a symmetric 3×3 matrix given as (xx, yy, zz, xy, xz, yz), largest first, by
     * the trigonometric closed form.
     */
    static double[] eigenvalues(double[] m) {
        double a = m[0];
        double b = m[1];
        double c = m[2];
        double d = m[3];
        double e = m[4];
        double f = m[5];
        double p1 = d * d + e * e + f * f;
        double[] result;
        if (p1 < 1.0E-12D) {
            result = new double[] {a, b, c};
        } else {
            double q = (a + b + c) / 3.0D;
            double p2 = (a - q) * (a - q) + (b - q) * (b - q) + (c - q) * (c - q) + 2.0D * p1;
            double p = Math.sqrt(p2 / 6.0D);
            double ba = (a - q) / p;
            double bb = (b - q) / p;
            double bc = (c - q) / p;
            double bd = d / p;
            double be = e / p;
            double bf = f / p;
            // det(B) / 2 with B = (A − qI) / p, rows (xx xy xz), (xy yy yz), (xz yz zz).
            double r =
                    (ba * (bb * bc - bf * bf) - bd * (bd * bc - bf * be) + be * (bd * bf - bb * be))
                            / 2.0D;
            double phi = r <= -1.0D ? Math.PI / 3.0D : r >= 1.0D ? 0.0D : Math.acos(r) / 3.0D;
            double first = q + 2.0D * p * Math.cos(phi);
            double third = q + 2.0D * p * Math.cos(phi + 2.0D * Math.PI / 3.0D);
            result = new double[] {first, 3.0D * q - first - third, third};
        }
        Arrays.sort(result);
        return new double[] {result[2], result[1], result[0]};
    }

    private static final int[] DX = {1, -1, 0, 0, 0, 0};
    private static final int[] DY = {0, 0, 1, -1, 0, 0};
    private static final int[] DZ = {0, 0, 0, 0, 1, -1};
}

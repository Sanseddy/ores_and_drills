package dev.world.level.levelgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * Compact Minecraft adaptation of Factorio's radial spot + multi-scale basis-noise principle. This
 * is not Factorio's spot_noise implementation. Dimensions below are DIAMETERS in blocks. There is
 * no central curve, directional stretching, or coupling of the XZ mask to Y. World positions are
 * converted to deposit-local coordinates before sampling these cached fields.
 */
final class CompactDepositGeometry {
    static final int MAX_HORIZONTAL_REACH = 36;
    static final int MAX_VERTICAL_REACH = 12;
    static final int MEDIUM_MIN_DIAMETER = 20;
    static final int MEDIUM_MAX_DIAMETER = 30;
    static final int LARGE_MIN_DIAMETER = 40;
    static final int LARGE_MAX_DIAMETER = 60;
    static final int MAX_UNITS_PER_BLOCK = 5_000;
    static final int MAX_ORE_BLOCKS_PER_CHUNK = 16 * 16 * 7;
    private static final int[][] NEIGHBORS = {
        {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private static final int GRID = MAX_HORIZONTAL_REACH * 2 + 1;
    private static final int[][] XZ_NEIGHBORS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final Column OUTSIDE = new Column(-1, -1, 1);
    private final long seed;
    private final boolean large;
    private final double radius;
    private final double maximumRadius;
    private final double baseThickness;
    private final double offsetX;
    private final double offsetZ;
    private final double contourPhaseX;
    private final double contourPhaseZ;
    private final Noise3 richnessLow;
    private final Noise3 richnessHigh;
    private final Column[] columns = new Column[GRID * GRID];
    private final List<Cell> cells;
    private final int radiusX;
    private final int radiusZ;
    private final int radiusY;
    private int noiseEvaluations;

    CompactDepositGeometry(long seed, boolean large, double sizeMultiplier) {
        this(seed, large, sizeMultiplier, 0);
    }

    CompactDepositGeometry(long seed, boolean large, double sizeMultiplier, long verticalSalt) {
        this.seed = seed;
        this.large = large;
        double size = Double.isFinite(sizeMultiplier) ? clamp(sizeMultiplier, 0.1, 6.0) : 1;
        maximumRadius = (large ? LARGE_MAX_DIAMETER : MEDIUM_MAX_DIAMETER) * 0.5;
        // QF controls shape only; Minecraft reserve uses an independent salt.
        double minQF = large ? 8_000_000 : 1_000_000, maxQF = large ? 27_000_000 : 3_375_000;
        double virtualQuantity = clamp(lerp(minQF, maxQF, unit(seed, 211)) * size, minQF, maxQF);
        radius = clamp(.1 * Math.cbrt(virtualQuantity), minimumDiameter(large) * .5, maximumRadius);
        baseThickness = lerp(large ? 5.25 : 3.25, large ? 6.75 : 4.75, unit(seed, 3));
        offsetX = (unit(seed, 21) - .5) * .7;
        offsetZ = (unit(seed, 22) - .5) * .7;
        contourPhaseX = unit(seed + 31, 91) * 31;
        contourPhaseZ = unit(seed + 31, 92) * 31;
        richnessLow = new Noise3(seed + 101, 8);
        richnessHigh = new Noise3(seed + 102, 3);
        // Measure unconstrained field, then recenter and uniformly scale its coordinates. No
        // circular or rectangular clipping: relative contour features are preserved.
        int referenceReach = 64, referenceGrid = 129;
        double[] reference = new double[referenceGrid * referenceGrid];
        for (int x = -referenceReach; x <= referenceReach; x++)
            for (int z = -referenceReach; z <= referenceReach; z++)
                reference[(x + referenceReach) * referenceGrid + z + referenceReach] =
                        horizontalField(x, z);
        int[] bounds = bounds(horizontalComponent(reference, referenceGrid), referenceGrid);
        double sourceX = (bounds[0] + bounds[1]) * .5 - referenceReach,
                sourceZ = (bounds[2] + bounds[3]) * .5 - referenceReach;
        int target = (int) Math.round(2 * radius);
        double scale =
                target / (double) Math.max(bounds[1] - bounds[0] + 1, bounds[3] - bounds[2] + 1);
        double[] fields = new double[columns.length];
        boolean[] mask = null;
        for (int attempt = 0; attempt < 32; attempt++) {
            for (int x = -MAX_HORIZONTAL_REACH; x <= MAX_HORIZONTAL_REACH; x++)
                for (int z = -MAX_HORIZONTAL_REACH; z <= MAX_HORIZONTAL_REACH; z++)
                    fields[index(x, z)] =
                            horizontalField(x / scale + sourceX, z / scale + sourceZ) * scale;
            mask = horizontalComponent(fields, GRID);
            int span = Math.max(span(mask, true), span(mask, false));
            int[] actual = bounds(mask, GRID);
            boolean clear =
                    actual[0] > 0 && actual[1] < GRID - 1 && actual[2] > 0 && actual[3] < GRID - 1;
            if (clear && span >= minimumDiameter(large) && span <= maximumDiameter(large)) break;
            if (attempt == 31)
                throw new IllegalStateException("Cannot fit Factorio contour in category");
            scale *= (target + .15) / (double) span;
            if (!clear) scale *= .97;
        }
        double[] centers = new double[columns.length];
        double[] thicknesses = new double[columns.length];
        double minimumCenter = Double.POSITIVE_INFINITY, maximumCenter = Double.NEGATIVE_INFINITY;
        double diameter = Math.max(span(mask, true), span(mask, false));
        long heightSeed = SeedMixer.mix(seed ^ verticalSalt ^ 0x594845494748544CL);
        double[] waves = heightWaves(heightSeed, mask, diameter);
        Noise2 thicknessNoise = new Noise2(heightSeed + 51, 12);
        Noise2 topLow = new Noise2(heightSeed + 61, 10), topHigh = new Noise2(heightSeed + 62, 4);
        Noise2 bottomLow = new Noise2(heightSeed + 71, 10),
                bottomHigh = new Noise2(heightSeed + 72, 4);
        for (int x = -MAX_HORIZONTAL_REACH; x <= MAX_HORIZONTAL_REACH; x++) {
            for (int z = -MAX_HORIZONTAL_REACH; z <= MAX_HORIZONTAL_REACH; z++) {
                int i = index(x, z);
                if (!mask[i]) {
                    columns[i] = OUTSIDE;
                    continue;
                }
                noiseEvaluations += 5; // Thickness and the independent two-octave surfaces.
                // Two continuous 2D scales form the main wave and the smaller undulations. Fixed
                // 32/12 wavelengths and natural amplitudes; no seed-wise range amplification.
                double yc = waves[i];
                double t =
                        clamp(
                                baseThickness + 0.65 * thicknessNoise.sample(x, z),
                                minimumThickness(),
                                maximumThickness());
                double topNoise = .30 * topLow.sample(x, z) + .12 * topHigh.sample(x, z);
                double bottomNoise = .30 * bottomLow.sample(x, z) + .12 * bottomHigh.sample(x, z);
                double top = yc + t * 0.5 + topNoise;
                double bottom = yc - t * 0.5 + bottomNoise;
                centers[i] = (top + bottom) * 0.5;
                thicknesses[i] = clamp(top - bottom, minimumThickness(), maximumThickness());
                minimumCenter = Math.min(minimumCenter, centers[i]);
                maximumCenter = Math.max(maximumCenter, centers[i]);
            }
        }
        double largestStep = 0;
        for (int x = -MAX_HORIZONTAL_REACH; x <= MAX_HORIZONTAL_REACH; x++) {
            for (int z = -MAX_HORIZONTAL_REACH; z <= MAX_HORIZONTAL_REACH; z++) {
                int i = index(x, z);
                if (!mask[i]) continue;
                if (x < MAX_HORIZONTAL_REACH && mask[index(x + 1, z)])
                    largestStep =
                            Math.max(largestStep, Math.abs(centers[i] - centers[index(x + 1, z)]));
                if (z < MAX_HORIZONTAL_REACH && mask[index(x, z + 1)])
                    largestStep =
                            Math.max(largestStep, Math.abs(centers[i] - centers[index(x, z + 1)]));
            }
        }
        double amplitude = Math.min(1, (large ? 1.0 : 0.85) / Math.max(1e-9, largestStep));
        double centerOffset = (minimumCenter + maximumCenter) * 0.5;
        List<Cell> body = new ArrayList<>(large ? 22000 : 7000);
        int maximumX = 1, maximumZ = 1, maximumY = 1;
        for (int x = -MAX_HORIZONTAL_REACH; x <= MAX_HORIZONTAL_REACH; x++) {
            for (int z = -MAX_HORIZONTAL_REACH; z <= MAX_HORIZONTAL_REACH; z++) {
                int i = index(x, z);
                if (!mask[i]) continue;
                double center = (centers[i] - centerOffset) * amplitude;
                double t = thicknesses[i];
                Column column = new Column(fields[i], center - t * 0.5, center + t * 0.5);
                columns[i] = column;
                int bottom = (int) Math.ceil(column.bottom()), top = (int) Math.floor(column.top());
                // Geological envelope only. Physical ore selection has a separate resource budget.
                int selectedBottom = bottom, selectedTop = top;
                if (selectedTop - selectedBottom + 1 > maximumThickness()) selectedTop--;
                for (int y = selectedBottom; y <= selectedTop; y++) {
                    body.add(new Cell(x, y, z, weight(x, y, z, column)));
                    maximumY = Math.max(maximumY, Math.abs(y));
                }
                maximumX = Math.max(maximumX, Math.abs(x));
                maximumZ = Math.max(maximumZ, Math.abs(z));
            }
        }
        cells = List.copyOf(body); // Construction already visits x,z,y in Cell.ORDER.
        radiusX = maximumX;
        radiusZ = maximumZ;
        radiusY = maximumY;
    }

    private double horizontalField(double x, double z) {
        noiseEvaluations += 3;
        double px = x - offsetX, pz = z - offsetZ;
        double blobs =
                contourBasis(px, pz, 8)
                        + contourBasis(px, pz, 24)
                        + 1.5 * contourBasis(px, pz, 64)
                        - 1.0 / 3;
        // Single spot cone divided by peak 3QF/(pi R^2), regular blob multiplier 1/8. Regional spot
        // selection stays Minecraft's grid; gradient basis substitutes engine noise.
        return radius * (1 - Math.sqrt(px * px + pz * pz) / radius + blobs / 8);
    }

    private double contourBasis(double x, double z, double scale) {
        return gradientField(seed + 31, x / scale + contourPhaseX, z / scale + contourPhaseZ);
    }

    private static int span(boolean[] mask, boolean xAxis) {
        int min = GRID, max = -1;
        for (int i = 0; i < mask.length; i++)
            if (mask[i]) {
                int coordinate = xAxis ? i / GRID : i % GRID;
                min = Math.min(min, coordinate);
                max = Math.max(max, coordinate);
            }
        return max - min + 1;
    }

    private double[] heightWaves(long seed, boolean[] mask, double diameter) {
        double[] waves = new double[mask.length];
        Noise2 low = new Noise2(seed + 41, 32), high = new Noise2(seed + 42, 12);
        for (int i = 0; i < mask.length; i++)
            if (mask[i]) {
                noiseEvaluations += 2;
                int x = i / GRID - MAX_HORIZONTAL_REACH, z = i % GRID - MAX_HORIZONTAL_REACH;
                waves[i] =
                        (large ? 3.5 : 2.5) * low.sample(x, z)
                                + (large ? .85 : .65) * high.sample(x, z);
            }
        return waves;
    }

    private static int[] bounds(boolean[] mask, int grid) {
        int minX = grid, maxX = -1, minZ = grid, maxZ = -1;
        for (int i = 0; i < mask.length; i++)
            if (mask[i]) {
                int x = i / grid, z = i % grid;
                minX = Math.min(minX, x);
                maxX = Math.max(maxX, x);
                minZ = Math.min(minZ, z);
                maxZ = Math.max(maxZ, z);
            }
        return new int[] {minX, maxX, minZ, maxZ};
    }

    private static boolean[] horizontalComponent(double[] fields, int grid) {
        boolean[] kept = new boolean[fields.length];
        int root = 0;
        for (int i = 1; i < fields.length; i++) if (fields[i] > fields[root]) root = i;
        if (fields[root] <= 0) throw new IllegalStateException("Empty spot field");
        int[] queue = new int[fields.length];
        int first = 0, last = 0;
        kept[root] = true;
        queue[last++] = root;
        while (first < last) {
            int i = queue[first++], x = i / grid, z = i % grid;
            for (int[] d : XZ_NEIGHBORS) {
                int nx = x + d[0], nz = z + d[1];
                if (nx < 0 || nx >= grid || nz < 0 || nz >= grid) continue;
                int next = nx * grid + nz;
                if (!kept[next] && fields[next] > 0) {
                    kept[next] = true;
                    queue[last++] = next;
                }
            }
        }
        return kept;
    }

    private double weight(int x, int y, int z, Column column) {
        noiseEvaluations += 2;
        double h = clamp(column.horizontal() / (radius * 0.65), 0, 1);
        double v = clamp(1 - 2 * Math.abs(y - column.center()) / column.thickness(), 0, 1);
        double richness = 0.65 * richnessLow.sample(x, y, z) + 0.35 * richnessHigh.sample(x, y, z);
        return clamp(0.55 * h + 0.35 * v + 0.25 * richness, 0, 1);
    }

    Column column(int x, int z) {
        if (Math.abs(x) > MAX_HORIZONTAL_REACH || Math.abs(z) > MAX_HORIZONTAL_REACH)
            return OUTSIDE;
        return columns[index(x, z)];
    }

    private static int index(int x, int z) {
        return (x + MAX_HORIZONTAL_REACH) * GRID + z + MAX_HORIZONTAL_REACH;
    }

    private int minimumThickness() {
        return large ? 5 : 3;
    }

    private int maximumThickness() {
        return large ? 7 : 5;
    }

    /**
     * Host filtering is separate from the mathematical mask. Retain one actual face-connected body.
     */
    static List<Cell> connected(List<Cell> input, int maximum) {
        if (input.isEmpty() || maximum < 1) return List.of();
        int minY = input.stream().mapToInt(Cell::y).min().orElseThrow();
        int maxY = input.stream().mapToInt(Cell::y).max().orElseThrow(), height = maxY - minY + 1;
        Cell[] grid = new Cell[GRID * GRID * height];
        boolean[] visited = new boolean[grid.length];
        for (Cell c : input) grid[index(c.x, c.z) * height + c.y - minY] = c;
        int[] queue = new int[input.size()];
        int[] largest = new int[0];
        for (int root = 0; root < grid.length; root++)
            if (grid[root] != null && !visited[root]) {
                int first = 0, last = 0;
                visited[root] = true;
                queue[last++] = root;
                while (first < last) {
                    Cell c = grid[queue[first++]];
                    for (int[] d : NEIGHBORS) {
                        int x = c.x + d[0], y = c.y + d[1], z = c.z + d[2];
                        if (Math.abs(x) > MAX_HORIZONTAL_REACH
                                || Math.abs(z) > MAX_HORIZONTAL_REACH
                                || y < minY
                                || y > maxY) continue;
                        int n = index(x, z) * height + y - minY;
                        if (grid[n] != null && !visited[n]) {
                            visited[n] = true;
                            queue[last++] = n;
                        }
                    }
                }
                if (last > largest.length) largest = java.util.Arrays.copyOf(queue, last);
            }
        List<Cell> result = new ArrayList<>(largest.length);
        for (int i : largest) result.add(grid[i]);
        result.sort(Cell.ORDER);
        if (result.size() > maximum) return selectOreCells(result, maximum);
        return List.copyOf(result);
    }

    /** Every physical block needs one unit; remaining reserve follows the richness field. */
    static int oreBlockBudget(int quantity) {
        return Math.max(1, quantity);
    }

    /**
     * Sparse connected body spanning a connected envelope. An insufficient budget rejects it.
     * Host-filtered inputs must first use connected(); the cached mathematical envelope already is
     * connected.
     */
    static List<Cell> selectOreCells(List<Cell> eligible, int maximum) {
        return selectOreCells(eligible, maximum, 0, 0);
    }

    static List<Cell> selectOreCells(List<Cell> eligible, int maximum, int originX, int originZ) {
        List<Cell> body = eligible;
        if (body.isEmpty() || maximum < 1) return List.of();
        // A sufficient reserve fills the complete connected envelope. Richness changes the amount
        // stored in each block, rather than cutting holes in the patch.
        if (maximum >= body.size()) return body;
        maximum = Math.min(maximum, body.size());
        int minY = body.stream().mapToInt(Cell::y).min().orElseThrow();
        int maxY = body.stream().mapToInt(Cell::y).max().orElseThrow(), height = maxY - minY + 1;
        Cell[] grid = new Cell[GRID * GRID * height];
        int[] parent = new int[grid.length];
        java.util.Arrays.fill(parent, -1);
        int root = 0;
        double weight = -1;
        int[] extremes = {-1, -1, -1, -1};
        for (Cell c : body) {
            int i = index(c.x, c.z) * height + c.y - minY;
            grid[i] = c;
            if (c.weight > weight) {
                root = i;
                weight = c.weight;
            }
            for (int axis = 0; axis < 4; axis++) {
                Cell old = extremes[axis] < 0 ? null : grid[extremes[axis]];
                int v = axis < 2 ? c.x : c.z, previous = old == null ? 0 : axis < 2 ? old.x : old.z;
                if (old == null
                        || (axis % 2 == 0 ? v < previous : v > previous)
                        || (v == previous && c.weight > old.weight)) extremes[axis] = i;
            }
        }
        // Dense primitive traversal avoids allocating six coordinate keys for every envelope cell.
        double[] distance = new double[grid.length];
        java.util.Arrays.fill(distance, Double.POSITIVE_INFINITY);
        long routeSeed = SeedMixer.mix(Double.doubleToLongBits(weight) ^ root);
        Noise2 routesLow = new Noise2(routeSeed, 14), routesHigh = new Noise2(routeSeed + 1, 6);
        double[] routeCost = new double[GRID * GRID];
        for (int x = -MAX_HORIZONTAL_REACH; x <= MAX_HORIZONTAL_REACH; x++)
            for (int z = -MAX_HORIZONTAL_REACH; z <= MAX_HORIZONTAL_REACH; z++) {
                double n = .5 + .5 * (.7 * routesHigh.sample(x, z) + .3 * routesLow.sample(x, z));
                routeCost[index(x, z)] = 4 * n * n;
            }
        PriorityQueue<PathNode> paths =
                new PriorityQueue<>(
                        Comparator.comparingDouble(PathNode::cost)
                                .thenComparingInt(PathNode::index));
        distance[root] = 0;
        parent[root] = root;
        paths.add(new PathNode(root, 0));
        while (!paths.isEmpty()) {
            PathNode path = paths.remove();
            int i = path.index();
            if (path.cost() != distance[i]) continue;
            Cell c = grid[i];
            for (int[] d : NEIGHBORS) {
                int x = c.x + d[0], y = c.y + d[1], z = c.z + d[2];
                if (Math.abs(x) > MAX_HORIZONTAL_REACH
                        || Math.abs(z) > MAX_HORIZONTAL_REACH
                        || y < minY
                        || y > maxY) continue;
                int next = index(x, z) * height + y - minY;
                if (grid[next] != null) {
                    // Correlated costs select natural richer routes. Uniform BFS neighbour order
                    // produces conspicuous straight X/Z spokes on sparse, large envelopes.
                    double poverty = 1 - (c.weight + grid[next].weight) * .5;
                    double cost =
                            path.cost()
                                    + .1
                                    + .8 * poverty * poverty
                                    + (routeCost[index(c.x, c.z)] + routeCost[index(x, z)]) * .5;
                    if (cost < distance[next]) {
                        distance[next] = cost;
                        parent[next] = i;
                        paths.add(new PathNode(next, cost));
                    }
                }
            }
        }
        boolean[] selected = new boolean[grid.length];
        int count = 0;
        for (int end : extremes) if (parent[end] < 0) return List.of();
        for (int end : extremes)
            for (int i = end; !selected[i]; i = parent[i]) {
                selected[i] = true;
                count++;
                if (i == root) break;
            }
        if (count > maximum) return List.of();
        Map<Long, Integer> perChunk = new HashMap<>();
        for (int i = 0; i < grid.length; i++)
            if (selected[i]) {
                if (perChunk.merge(chunkKey(grid[i], originX, originZ), 1, Integer::sum)
                        > MAX_ORE_BLOCKS_PER_CHUNK) return List.of();
            }
        PriorityQueue<Integer> frontier =
                new PriorityQueue<>(
                        Comparator.<Integer>comparingDouble(i -> grid[i].weight)
                                .reversed()
                                .thenComparingInt(i -> i));
        boolean[] visited = selected.clone();
        for (int i = 0; i < grid.length; i++) if (selected[i]) frontier.add(i);
        while (!frontier.isEmpty() && count < maximum) {
            int i = frontier.remove();
            Cell c = grid[i];
            if (!selected[i]) {
                long chunk = chunkKey(c, originX, originZ);
                if (perChunk.getOrDefault(chunk, 0) >= MAX_ORE_BLOCKS_PER_CHUNK) continue;
                perChunk.merge(chunk, 1, Integer::sum);
                selected[i] = true;
                count++;
                if (count == maximum) break;
            }
            for (int[] d : NEIGHBORS) {
                int x = c.x + d[0], y = c.y + d[1], z = c.z + d[2];
                if (Math.abs(x) > MAX_HORIZONTAL_REACH
                        || Math.abs(z) > MAX_HORIZONTAL_REACH
                        || y < minY
                        || y > maxY) continue;
                int next = index(x, z) * height + y - minY;
                if (grid[next] != null && !visited[next]) {
                    visited[next] = true;
                    frontier.add(next);
                }
            }
        }
        List<Cell> result = new ArrayList<>(count);
        for (int i = 0; i < grid.length; i++) if (selected[i]) result.add(grid[i]);
        return List.copyOf(result);
    }

    private static long chunkKey(Cell c, int x, int z) {
        return ((long) Math.floorDiv(c.x + x, 16) << 32)
                ^ (Math.floorDiv(c.z + z, 16) & 0xffffffffL);
    }

    private record PathNode(int index, double cost) {}

    static double noise(long seed, double x, double y, double z) {
        int ix = (int) Math.floor(x), iy = (int) Math.floor(y), iz = (int) Math.floor(z);
        double fx = fade(x - ix), fy = fade(y - iy), fz = fade(z - iz);
        double a = lerp(value(seed, ix, iy, iz), value(seed, ix + 1, iy, iz), fx);
        double b = lerp(value(seed, ix, iy + 1, iz), value(seed, ix + 1, iy + 1, iz), fx);
        double c = lerp(value(seed, ix, iy, iz + 1), value(seed, ix + 1, iy, iz + 1), fx);
        double d = lerp(value(seed, ix, iy + 1, iz + 1), value(seed, ix + 1, iy + 1, iz + 1), fx);
        return lerp(lerp(a, b, fy), lerp(c, d, fy), fz);
    }

    private record Noise2(long seed, double scale, double ox, double oz) {
        Noise2(long seed, double scale) {
            this(seed, scale, unit(seed, 91) * 31, unit(seed, 92) * 31);
        }

        double sample(double x, double z) {
            return valueField(seed, x / scale + ox, z / scale + oz);
        }
    }

    private record Noise3(long seed, double scale, double ox, double oy, double oz) {
        Noise3(long seed, double scale) {
            this(seed, scale, unit(seed, 91) * 31, unit(seed, 93) * 31, unit(seed, 92) * 31);
        }

        double sample(double x, double y, double z) {
            return noise(seed, x / scale + ox, y / scale + oy, z / scale + oz);
        }
    }

    private static double valueField(long seed, double px, double pz) {
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
        double fx = fade(px - ix), fz = fade(pz - iz);
        return lerp(
                lerp(value(seed, ix, 0, iz), value(seed, ix + 1, 0, iz), fx),
                lerp(value(seed, ix, 0, iz + 1), value(seed, ix + 1, 0, iz + 1), fx),
                fz);
    }

    static double basis(long seed, double x, double z, double scale) {
        double px = x / scale + unit(seed, 91) * 31, pz = z / scale + unit(seed, 92) * 31;
        return gradientField(seed, px, pz);
    }

    private static double gradientField(long seed, double px, double pz) {
        int ix = (int) Math.floor(px), iz = (int) Math.floor(pz);
        double dx = px - ix, dz = pz - iz, fx = fade(dx), fz = fade(dz);
        return 1.41421356237
                * lerp(
                        lerp(
                                gradient(seed, ix, iz, dx, dz),
                                gradient(seed, ix + 1, iz, dx - 1, dz),
                                fx),
                        lerp(
                                gradient(seed, ix, iz + 1, dx, dz - 1),
                                gradient(seed, ix + 1, iz + 1, dx - 1, dz - 1),
                                fx),
                        fz);
    }

    private static double gradient(long seed, int x, int z, double dx, double dz) {
        int direction =
                (int)
                                SeedMixer.mix(
                                        seed
                                                ^ (long) x * 0x632BE59BD9B4E019L
                                                ^ (long) z * 0x85157AF5D66D4A13L)
                        & 7;
        return switch (direction) {
            case 0 -> dx;
            case 1 -> -dx;
            case 2 -> dz;
            case 3 -> -dz;
            case 4 -> (dx + dz) * 0.70710678118;
            case 5 -> (dx - dz) * 0.70710678118;
            case 6 -> (-dx + dz) * 0.70710678118;
            default -> (-dx - dz) * 0.70710678118;
        };
    }

    private static double value(long seed, int x, int y, int z) {
        return unit(
                                seed
                                        ^ (long) x * 0x632BE59BD9B4E019L
                                        ^ (long) y * 0x9E3779B97F4A7C15L
                                        ^ (long) z * 0x85157AF5D66D4A13L,
                                0)
                        * 2
                - 1;
    }

    static double unit(long seed, long salt) {
        return (SeedMixer.mix(seed + salt * 0x9E3779B97F4A7C15L) >>> 11) * 0x1.0p-53;
    }

    static int totalOre(long seed, boolean large, int blocks, float richness) {
        return (int) Math.max(1, Math.round(baseReserve(seed, large) * clamp(richness, 0.1, 6.0)));
    }

    static int maximumBlockReserve(int total, int blocks) {
        long count = Math.max(1, blocks), quantity = Math.max(count, total);
        // At least the average must fit. Otherwise one block may hold at most 20% (rounded up).
        return (int)
                Math.min(
                        MAX_UNITS_PER_BLOCK,
                        Math.max((quantity + count - 1) / count, (quantity + 4) / 5));
    }

    private static long baseReserve(long seed, boolean large) {
        long minimum = large ? 400_000 : 180_000, maximum = large ? 2_000_000 : 360_000;
        return DepositTierMath.sampleAroundCharacteristicValue(
                minimum, maximum, Math.sqrt(minimum * maximum), () -> unit(seed, 201));
    }

    private static double fade(double x) {
        return x * x * x * (x * (x * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double clamp(double x, double a, double b) {
        return Math.max(a, Math.min(b, x));
    }

    List<Cell> cells() {
        return cells;
    }

    static int minimumDiameter(boolean large) {
        return large ? LARGE_MIN_DIAMETER : MEDIUM_MIN_DIAMETER;
    }

    static int maximumDiameter(boolean large) {
        return large ? LARGE_MAX_DIAMETER : MEDIUM_MAX_DIAMETER;
    }

    int radiusX() {
        return Math.max(1, radiusX);
    }

    int radiusZ() {
        return Math.max(1, radiusZ);
    }

    int radiusY() {
        return Math.max(1, radiusY);
    }

    double diameter() {
        return radius * 2;
    }

    int noiseEvaluations() {
        return noiseEvaluations;
    }

    java.util.OptionalInt fitOriginY(int requested, int minimumY, int maximumYExclusive) {
        int bottom = cells.stream().mapToInt(Cell::y).min().orElseThrow();
        int top = cells.stream().mapToInt(Cell::y).max().orElseThrow();
        long first = (long) minimumY - bottom, last = (long) maximumYExclusive - 1 - top;
        if (first > last || first > Integer.MAX_VALUE || last < Integer.MIN_VALUE)
            return java.util.OptionalInt.empty();
        return java.util.OptionalInt.of((int) Math.max(first, Math.min(last, requested)));
    }

    record Key(int x, int y, int z) {}

    record Cell(int x, int y, int z, double weight) {
        static final Comparator<Cell> ORDER =
                Comparator.comparingInt(Cell::x)
                        .thenComparingInt(Cell::z)
                        .thenComparingInt(Cell::y);

        Key key() {
            return new Key(x, y, z);
        }
    }

    record Column(double horizontal, double bottom, double top) {
        double thickness() {
            return top - bottom;
        }

        double center() {
            return (top + bottom) * 0.5;
        }

        double field(int y) {
            return Math.min(horizontal, Math.min(y - bottom, top - y));
        }
    }
}

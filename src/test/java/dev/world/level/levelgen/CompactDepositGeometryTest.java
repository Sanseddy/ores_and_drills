package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.*;
import org.junit.jupiter.api.Test;

class CompactDepositGeometryTest {
    @Test
    void ensembleHasBoundedConnectedBodiesAndIndependentNonIntersectingSurfaces() {
        for (boolean large : new boolean[] {false, true})
            for (int seed = 0; seed < 100; seed++) {
                var g = new CompactDepositGeometry(seed, large, 1);
                int diameter = diameter(g.cells());
                assertTrue(
                        diameter >= (large ? 40 : 20) && diameter <= (large ? 60 : 30),
                        "Diameter " + diameter);
                assertConnected(g.cells());
                for (var c : g.cells()) {
                    var column = g.column(c.x(), c.z());
                    assertTrue(column.horizontal() > 0 && column.field(c.y()) >= -1e-9);
                    assertTrue(
                            column.thickness() >= (large ? 5 : 3) - 1e-9
                                    && column.thickness() <= (large ? 7 : 5) + 1e-9);
                    for (int[] d : new int[][] {{1, 0}, {0, 1}}) {
                        var next = g.column(c.x() + d[0], c.z() + d[1]);
                        if (next.horizontal() > 0)
                            assertTrue(
                                    Math.abs(column.center() - next.center())
                                            <= (large ? 1 : .85) + 1e-9);
                    }
                }
                int q = CompactDepositGeometry.totalOre(seed, large, 0, 1);
                assertTrue(q >= (large ? 400_000 : 180_000) && q <= (large ? 2_000_000 : 360_000));
                var ore =
                        CompactDepositGeometry.selectOreCells(
                                g.cells(), CompactDepositGeometry.oreBlockBudget(q));
                assertConnected(ore);
                assertTrue(ore.size() <= q);
                assertEquals(diameter, diameter(ore));
                assertEquals(
                        g.cells(), ore, "Default reserve must fill the entire geological body");
                int[] amounts =
                        DepositTierMath.distributeOre(
                                q,
                                ore.stream()
                                        .mapToDouble(CompactDepositGeometry.Cell::weight)
                                        .toArray(),
                                CompactDepositGeometry.maximumBlockReserve(q, ore.size()));
                assertEquals(q, Arrays.stream(amounts).sum());
                assertTrue(
                        Arrays.stream(amounts)
                                .allMatch(
                                        n ->
                                                n >= 1
                                                        && n
                                                                <= CompactDepositGeometry
                                                                        .MAX_UNITS_PER_BLOCK));
            }
    }

    @Test
    void footprintIsRoundAndContourHasRealDeformations() {
        for (boolean large : new boolean[] {false, true})
            for (int seed = 0; seed < 30; seed++) {
                var g = new CompactDepositGeometry(seed, large, 1);
                var bounds = bounds(g.cells());
                int x = bounds[1] - bounds[0] + 1, z = bounds[3] - bounds[2] + 1;
                assertTrue(Math.max(x, z) / (double) Math.min(x, z) < 1.65);
                var footprint = footprint(g);
                assertTrue(footprint.size() < x * z * .95);
                Set<Integer> rowWidths = new HashSet<>();
                for (int row = bounds[0]; row <= bounds[1]; row++) {
                    int width = 0;
                    for (int col = bounds[2]; col <= bounds[3]; col++)
                        if (g.column(row, col).horizontal() > 0) width++;
                    rowWidths.add(width);
                }
                assertTrue(
                        rowWidths.size() >= Math.max(5, Math.min(x, z) / 4),
                        "Contour lacks width variation at diameter " + Math.max(x, z));
            }
    }

    @Test
    void changingVerticalWavesDoesNotChangeAnyHorizontalColumn() {
        for (boolean large : new boolean[] {false, true})
            for (int seed = 0; seed < 10; seed++) {
                var a = new CompactDepositGeometry(seed, large, 1, 0);
                var b = new CompactDepositGeometry(seed, large, 1, 8199);
                assertEquals(footprint(a), footprint(b));
                assertNotEquals(a.cells(), b.cells());
            }
    }

    @Test
    void richnessIsNotUniformAndCenterIsRicherThanEdges() {
        double center = 0, edge = 0;
        int n = 0, m = 0;
        for (int seed = 0; seed < 12; seed++) {
            var g = new CompactDepositGeometry(seed, true, 1);
            for (var c : g.cells()) {
                double h = g.column(c.x(), c.z()).horizontal();
                if (h > g.diameter() * .22) {
                    center += c.weight();
                    n++;
                }
                if (h < 1) {
                    edge += c.weight();
                    m++;
                }
            }
        }
        assertTrue(n > 0 && m > 0);
        assertTrue(center / n > edge / m + .10);
    }

    @Test
    void contoursStayDiverseAtNewResolution() {
        for (boolean large : new boolean[] {false, true}) {
            Set<Set<String>> shapes = new HashSet<>();
            for (int seed = 0; seed < 100; seed++)
                shapes.add(footprint(new CompactDepositGeometry(seed, large, 1)));
            assertEquals(100, shapes.size());
        }
    }

    @Test
    void customWorldHeightsFitWholeBodiesAndNonFiniteSizeCannotHangTheMask() {
        for (boolean large : new boolean[] {false, true})
            for (int seed = 0; seed < 5; seed++) {
                var g = new CompactDepositGeometry(seed, large, Double.NaN);
                assertEquals(new CompactDepositGeometry(seed, large, 1).cells(), g.cells());
                for (int[] b : new int[][] {{-2032, -1024}, {512, 1024}, {-64, 320}})
                    for (int requested : new int[] {Integer.MIN_VALUE, 0, Integer.MAX_VALUE}) {
                        int origin = g.fitOriginY(requested, b[0], b[1]).orElseThrow();
                        for (var c : g.cells())
                            assertTrue(origin + c.y() >= b[0] && origin + c.y() < b[1]);
                    }
                assertTrue(g.fitOriginY(0, 0, 1).isEmpty());
            }
    }

    @Test
    void integerAllocationRespectsBlockCapacityEvenForExtremeWeights() {
        int[] saturated =
                DepositTierMath.distributeOre(12000, new double[] {1e300, 1, 0, Double.NaN}, 5000);
        assertEquals(12000, Arrays.stream(saturated).sum());
        assertTrue(Arrays.stream(saturated).allMatch(n -> n >= 1 && n <= 5000));
        Random r = new Random(73577);
        for (int run = 0; run < 500; run++) {
            double[] weights = r.doubles(3 + r.nextInt(20), 0, 1).toArray();
            int q = r.nextInt(weights.length * 5000 + 1);
            var amounts = DepositTierMath.distributeOre(q, weights, 5000);
            assertEquals(Math.max(q, weights.length), Arrays.stream(amounts).sum());
            assertArrayEquals(amounts, DepositTierMath.distributeOre(q, weights, 5000));
        }
    }

    @Test
    void hostFilteredBodiesCannotPutTheirReserveInOneRichBlock() {
        for (int n = 6; n <= 100; n++) {
            double[] w = new double[n];
            Arrays.fill(w, .0001);
            w[0] = 1;
            int q = n * 20 + 1;
            var a =
                    DepositTierMath.distributeOre(
                            q, w, CompactDepositGeometry.maximumBlockReserve(q, n));
            assertEquals(q, Arrays.stream(a).sum());
            assertTrue(Arrays.stream(a).max().orElseThrow() <= Math.ceil(q * .20));
        }
    }

    @Test
    void chunkPartitionPreservesEveryBlockAndUnitIncludingNegativeCoordinates() {
        for (int seed = 0; seed < 20; seed++) {
            var g = new CompactDepositGeometry(seed, true, 1);
            int q = CompactDepositGeometry.totalOre(seed, true, 0, 1);
            var ore =
                    CompactDepositGeometry.selectOreCells(
                            g.cells(), CompactDepositGeometry.oreBlockBudget(q), -17, -65);
            assertEquals(
                    ore,
                    CompactDepositGeometry.selectOreCells(
                            g.cells(), CompactDepositGeometry.oreBlockBudget(q), -17, -65));
            Map<String, Integer> chunks = new HashMap<>();
            for (var c : ore)
                chunks.merge(
                        Math.floorDiv(c.x() - 17, 16) + ":" + Math.floorDiv(c.z() - 65, 16),
                        1,
                        Integer::sum);
            assertTrue(chunks.size() >= 4);
            assertEquals(ore.size(), chunks.values().stream().mapToInt(n -> n).sum());
            assertTrue(
                    chunks.values().stream()
                            .allMatch(n -> n <= CompactDepositGeometry.MAX_ORE_BLOCKS_PER_CHUNK));
            assertConnected(ore);
        }
    }

    @Test
    void extremeSizeAndRichnessNeverIncreaseBoundsOrInventResources() {
        for (boolean large : new boolean[] {false, true})
            for (double size : new double[] {.1, 1, 6})
                for (int seed = 0; seed < 6; seed++) {
                    var g = new CompactDepositGeometry(seed, large, size);
                    assertTrue(
                            diameter(g.cells()) >= (large ? 40 : 20)
                                    && diameter(g.cells()) <= (large ? 60 : 30));
                    for (float richness : new float[] {.1f, 6f}) {
                        int q = CompactDepositGeometry.totalOre(seed, large, 0, richness);
                        var ore =
                                CompactDepositGeometry.selectOreCells(
                                        g.cells(), CompactDepositGeometry.oreBlockBudget(q));
                        if (ore.isEmpty()) {
                            assertTrue(richness < 1);
                            continue;
                        }
                        assertConnected(ore);
                        assertTrue(ore.size() <= q);
                        Map<String, Integer> perChunk = new HashMap<>();
                        for (var cell : ore)
                            perChunk.merge(
                                    Math.floorDiv(cell.x(), 16) + ":" + Math.floorDiv(cell.z(), 16),
                                    1,
                                    Integer::sum);
                        assertTrue(
                                perChunk.values().stream()
                                        .allMatch(
                                                n ->
                                                        n
                                                                <= CompactDepositGeometry
                                                                        .MAX_ORE_BLOCKS_PER_CHUNK),
                                "Richness exceeded the geological thickness bound");
                        if (q >= g.cells().size())
                            assertEquals(g.cells(), ore, "Sufficient reserve must fill the body");
                        assertEquals(
                                diameter(g.cells()),
                                diameter(ore),
                                "Sparse selection changed the footprint diameter");
                        assertEquals(
                                q,
                                Arrays.stream(
                                                DepositTierMath.distributeOre(
                                                        q,
                                                        ore.stream()
                                                                .mapToDouble(
                                                                        CompactDepositGeometry.Cell
                                                                                ::weight)
                                                                .toArray(),
                                                        CompactDepositGeometry.maximumBlockReserve(
                                                                q, ore.size())))
                                        .sum());
                    }
                }
    }

    @Test
    void disconnectedHostsAreReducedAndImpossibleSparseSpansAreRejected() {
        var g = new CompactDepositGeometry(1324, true, 1);
        var clipped = g.cells().stream().filter(c -> c.x() != 0).toList();
        var repaired = CompactDepositGeometry.connected(clipped, clipped.size());
        assertTrue(repaired.size() < clipped.size());
        assertConnected(repaired);
        assertTrue(CompactDepositGeometry.selectOreCells(g.cells(), 10).isEmpty());
    }

    @Test
    void virtualFactorioQuantityAndActualResourcesAreIndependent() {
        for (boolean large : new boolean[] {false, true}) {
            var g = new CompactDepositGeometry(21, large, 1);
            assertTrue(g.diameter() >= (large ? 40 : 20) && g.diameter() <= (large ? 60 : 30));
            assertEquals(
                    CompactDepositGeometry.totalOre(21, large, 1, 1),
                    CompactDepositGeometry.totalOre(21, large, 100000, 1));
        }
    }

    private static Set<String> footprint(CompactDepositGeometry g) {
        Set<String> out = new HashSet<>();
        for (var c : g.cells()) out.add(c.x() + ":" + c.z());
        return out;
    }

    static int[] bounds(List<CompactDepositGeometry.Cell> cells) {
        return new int[] {
            cells.stream().mapToInt(CompactDepositGeometry.Cell::x).min().orElseThrow(),
            cells.stream().mapToInt(CompactDepositGeometry.Cell::x).max().orElseThrow(),
            cells.stream().mapToInt(CompactDepositGeometry.Cell::z).min().orElseThrow(),
            cells.stream().mapToInt(CompactDepositGeometry.Cell::z).max().orElseThrow()
        };
    }

    static int diameter(List<CompactDepositGeometry.Cell> cells) {
        int[] b = bounds(cells);
        return Math.max(b[1] - b[0] + 1, b[3] - b[2] + 1);
    }

    private static void assertConnected(List<CompactDepositGeometry.Cell> cells) {
        assertFalse(cells.isEmpty());
        assertEquals(cells.size(), CompactDepositGeometry.connected(cells, cells.size()).size());
    }
}

package dev.world.geology;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class CaveAnalysisTest {
    private static final CaveAnalysis.Settings EXACT =
            new CaveAnalysis.Settings(0.0D, 0.0D, 1.0E-3D, 1.0D, 100_000);

    /** Two spherical caves of radius 4 probes, their surfaces one rock probe apart along X. */
    private static CaveAnalysis.Grid twoCaves() {
        int n = 24;
        float[] density = new float[n * n * n];
        byte[] fill = new byte[n * n * n];
        CaveAnalysis.Grid grid =
                new CaveAnalysis.Grid(0.0D, 100.0D, 0.0D, 1.0D, n, n, n, density, fill);
        for (int ix = 0; ix < n; ix++) {
            for (int iy = 0; iy < n; iy++) {
                for (int iz = 0; iz < n; iz++) {
                    double a = Math.sqrt(sq(ix - 6) + sq(iy - 12) + sq(iz - 12));
                    double b = Math.sqrt(sq(ix - 16) + sq(iy - 12) + sq(iz - 12));
                    double d = Math.min(a, b) - 4.0D;
                    int index = grid.index(ix, iy, iz);
                    density[index] = (float) d;
                    fill[index] = d <= 0.0D ? CaveAnalysis.AIR : CaveAnalysis.SOLID;
                }
            }
        }
        return grid;
    }

    private static double sq(double v) {
        return v * v;
    }

    @Test
    void findsSeparateCavesAndTheThinWallBetweenThem() {
        CaveAnalysis.Result result = CaveAnalysis.analyse(twoCaves(), EXACT, 1L, () -> false);
        assertNotNull(result);
        assertEquals(2, result.caves().size());
        assertEquals(1, result.links().size());
        for (CaveAnalysis.Cave cave : result.caves()) {
            assertEquals(CaveAnalysis.Shape.CHAMBER, cave.shape());
            assertTrue(cave.volume() > 200.0D && cave.volume() < 400.0D, "volume " + cave.volume());
            assertEquals(12.0D, cave.z(), 0.01D);
        }
    }

    @Test
    void wallPointsLieOnTheCaveSurfaces() {
        CaveAnalysis.Result result = CaveAnalysis.analyse(twoCaves(), EXACT, 1L, () -> false);
        assertFalse(result.walls().isEmpty());
        for (CaveAnalysis.Wall wall : result.walls()) {
            double y = 100.0D - wall.y();
            double a = Math.sqrt(sq(wall.x() - 6) + sq(y - 12) + sq(wall.z() - 12));
            double b = Math.sqrt(sq(wall.x() - 16) + sq(y - 12) + sq(wall.z() - 12));
            // Interpolated crossing plus the in-cell spread of half a probe spacing.
            assertEquals(4.0D, Math.min(a, b), 1.0D);
        }
    }

    @Test
    void smallVoidsAreOftenMissedByAWeakAnalyser() {
        int n = 16;
        float[] density = new float[n * n * n];
        byte[] fill = new byte[n * n * n];
        Arrays.fill(density, 1.0F);
        CaveAnalysis.Grid grid =
                new CaveAnalysis.Grid(0.0D, 50.0D, 0.0D, 1.0D, n, n, n, density, fill);
        // Many single-probe voids spread through the rock.
        int voids = 0;
        for (int ix = 1; ix < n; ix += 3) {
            for (int iz = 1; iz < n; iz += 3) {
                int index = grid.index(ix, 8, iz);
                fill[index] = CaveAnalysis.AIR;
                density[index] = -1.0F;
                voids++;
            }
        }
        int weak = CaveAnalysis.analyse(grid, settings(1), 7L, () -> false).caves().size();
        int strong = CaveAnalysis.analyse(grid, settings(3), 7L, () -> false).caves().size();
        assertTrue(weak < strong, weak + " vs " + strong);
        assertTrue(strong <= voids);
    }

    private static CaveAnalysis.Settings settings(int level) {
        // The level's detection, without false readings, so only real voids count.
        CaveAnalysis.Settings of = CaveAnalysis.Settings.of(level);
        return new CaveAnalysis.Settings(0.0D, 0.0D, of.detectionCells(), of.weakFactor(), 100_000);
    }

    @Test
    void falseReadingsAreRarerAtHigherLevels() {
        int n = 32;
        float[] density = new float[n * n * n];
        Arrays.fill(density, 1.0F);
        CaveAnalysis.Grid grid =
                new CaveAnalysis.Grid(
                        0.0D, 50.0D, 0.0D, 1.0D, n, n, n, density, new byte[n * n * n]);
        int low =
                CaveAnalysis.analyse(grid, CaveAnalysis.Settings.of(1), 3L, () -> false)
                        .walls()
                        .size();
        int high =
                CaveAnalysis.analyse(grid, CaveAnalysis.Settings.of(3), 3L, () -> false)
                        .walls()
                        .size();
        assertTrue(low > high, low + " vs " + high);
    }

    @Test
    void analysisIsDeterministic() {
        CaveAnalysis.Settings noisy = CaveAnalysis.Settings.of(1);
        CaveAnalysis.Result first = CaveAnalysis.analyse(twoCaves(), noisy, 9L, () -> false);
        CaveAnalysis.Result second = CaveAnalysis.analyse(twoCaves(), noisy, 9L, () -> false);
        assertEquals(first.walls(), second.walls());
        assertEquals(first.links(), second.links());
    }

    @Test
    void unknownColumnsAreAnsweredTopDownFromTheTerrain() {
        int n = 4;
        byte[] fill = new byte[n * n * n];
        Arrays.fill(fill, CaveAnalysis.UNKNOWN);
        CaveAnalysis.Grid grid =
                new CaveAnalysis.Grid(0.0D, 3.0D, 0.0D, 1.0D, n, n, n, new float[n * n * n], fill);
        // Open above Y 2 (sky), rock at Y 2 and Y 1, a void at Y 0.
        CaveAnalysis.Terrain terrain =
                new CaveAnalysis.Terrain() {
                    @Override
                    public double density(int x, int y, int z) {
                        return y == 3 || y == 0 ? -0.5D : 0.5D;
                    }

                    @Override
                    public byte fill(int y) {
                        return CaveAnalysis.WATER;
                    }
                };
        CaveAnalysis.resolveUnknown(grid, terrain, () -> false);
        assertEquals(CaveAnalysis.SKY, fill[grid.index(1, 0, 1)]);
        assertEquals(CaveAnalysis.SOLID, fill[grid.index(1, 1, 1)]);
        assertEquals(CaveAnalysis.WATER, fill[grid.index(1, 3, 1)]);
    }

    @Test
    void eigenvaluesOfADiagonalMatrixAreItsEntries() {
        double[] values = CaveAnalysis.eigenvalues(new double[] {2.0D, 9.0D, 4.0D, 0, 0, 0});
        assertEquals(9.0D, values[0], 1.0E-6D);
        assertEquals(4.0D, values[1], 1.0E-6D);
        assertEquals(2.0D, values[2], 1.0E-6D);
        double[] rotated = CaveAnalysis.eigenvalues(new double[] {5.0D, 5.0D, 1.0D, 4.0D, 0, 0});
        assertEquals(9.0D, rotated[0], 1.0E-6D);
        assertEquals(1.0D, rotated[2], 1.0E-6D);
    }

    @Test
    void elongatedCavesAreTunnels() {
        assertEquals(
                CaveAnalysis.Shape.TUNNEL, CaveAnalysis.shape(500.0D, new double[] {30, 3, 3}));
        assertEquals(
                CaveAnalysis.Shape.SMALL_VOID, CaveAnalysis.shape(20.0D, new double[] {2, 2, 2}));
        assertEquals(
                CaveAnalysis.Shape.CAVERN, CaveAnalysis.shape(9000.0D, new double[] {20, 15, 10}));
    }
}

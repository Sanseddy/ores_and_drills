package dev.world.geology;

import java.util.function.BooleanSupplier;

/**
 * Void fraction of the subsurface on a world-aligned lattice: one jittered probe per lattice cell
 * and depth bin, weighted by how strongly the void's filling contrasts with rock (see {@link
 * CavityProbe#contrast}). Open points above a column's first rock are the sky or the sea, not
 * caves, and are not counted. The grid is coarse on purpose: a seismic wave averages voids over its
 * Fresnel zone and a quarter wavelength, which {@link #smoothed} reproduces.
 */
public final class CavityGrid {
    private static final long JITTER_SALT = 0x434156495459L;

    private final double originX;
    private final double originZ;
    private final double spacing;
    private final int sizeX;
    private final int sizeZ;
    private final double topY;
    private final double binHeight;
    private final int bins;
    private final float[] voids;

    private CavityGrid(
            double originX,
            double originZ,
            double spacing,
            int sizeX,
            int sizeZ,
            double topY,
            double binHeight,
            int bins,
            float[] voids) {
        this.originX = originX;
        this.originZ = originZ;
        this.spacing = spacing;
        this.sizeX = sizeX;
        this.sizeZ = sizeZ;
        this.topY = topY;
        this.binHeight = binHeight;
        this.bins = bins;
        this.voids = voids;
    }

    /** A grid without voids, for generators the model cannot probe. */
    public static CavityGrid empty(double topY, double binHeight, int bins) {
        return new CavityGrid(0.0D, 0.0D, 1.0E9D, 1, 1, topY, binHeight, bins, new float[bins]);
    }

    /**
     * Probes the lattice covering [minX, maxX] × [minZ, maxZ]. The spacing grows until the probe
     * count fits {@code sampleBudget}. Returns {@code null} when {@code cancelled} reports true.
     */
    public static CavityGrid sample(
            CavityProbe probe,
            long seed,
            double minX,
            double minZ,
            double maxX,
            double maxZ,
            double minimumSpacing,
            double topY,
            double binHeight,
            int bins,
            int sampleBudget,
            BooleanSupplier cancelled) {
        double width = Math.max(1.0D, maxX - minX);
        double depth = Math.max(1.0D, maxZ - minZ);
        double spacing = Math.max(1.0D, minimumSpacing);
        int budget = Math.max(bins, sampleBudget);
        while ((Math.ceil(width / spacing) + 1) * (Math.ceil(depth / spacing) + 1) * bins
                > budget) {
            spacing *= 1.25D;
        }
        int sizeX = (int) Math.ceil(width / spacing) + 1;
        int sizeZ = (int) Math.ceil(depth / spacing) + 1;
        float[] voids = new float[sizeX * sizeZ * bins];
        for (int ix = 0; ix < sizeX; ix++) {
            if (cancelled.getAsBoolean()) {
                return null;
            }
            for (int iz = 0; iz < sizeZ; iz++) {
                double cellX = minX + ix * spacing;
                double cellZ = minZ + iz * spacing;
                boolean belowGround = false;
                for (int bin = 0; bin < bins; bin++) {
                    long cell = SeedHash.combine(seed ^ JITTER_SALT, Math.round(cellX), bin);
                    cell = SeedHash.combine(cell, Math.round(cellZ));
                    int x = (int) Math.floor(cellX + (SeedHash.unit(cell, 1) - 0.5D) * spacing);
                    int z = (int) Math.floor(cellZ + (SeedHash.unit(cell, 2) - 0.5D) * spacing);
                    int y = (int) Math.floor(topY - (bin + SeedHash.unit(cell, 3)) * binHeight);
                    double contrast = probe.contrast(x, y, z);
                    if (contrast <= 0.0D) {
                        belowGround = true;
                    } else if (belowGround) {
                        voids[index(sizeX, sizeZ, ix, iz, bin)] = (float) contrast;
                    }
                }
            }
        }
        return new CavityGrid(minX, minZ, spacing, sizeX, sizeZ, topY, binHeight, bins, voids);
    }

    private static int index(int sizeX, int sizeZ, int ix, int iz, int bin) {
        return (bin * sizeX + ix) * sizeZ + iz;
    }

    public int bins() {
        return bins;
    }

    public double topY() {
        return topY;
    }

    public double binHeight() {
        return binHeight;
    }

    public double spacing() {
        return spacing;
    }

    /** Raw void fraction of the lattice cell nearest to (x, z) in {@code bin}. */
    public double rawVoid(double x, double z, int bin) {
        if (bin < 0 || bin >= bins) {
            return 0.0D;
        }
        int ix = clamp((int) Math.round((x - originX) / spacing), sizeX);
        int iz = clamp((int) Math.round((z - originZ) / spacing), sizeZ);
        return voids[index(sizeX, sizeZ, ix, iz, bin)];
    }

    /**
     * Void fraction of {@code bin} averaged over the given vertical window and blurred laterally by
     * a Gaussian of {@code lateralSigma} blocks, on the lattice. Sample it with {@link #at}.
     */
    public float[] smoothed(int bin, int verticalWindowBins, double lateralSigma) {
        int half = Math.max(0, verticalWindowBins / 2);
        return smoothedRange(bin - half, bin + half, lateralSigma);
    }

    /**
     * Void fraction averaged over bins {@code firstBin}…{@code lastBin} (clamped to the grid) and
     * blurred laterally like {@link #smoothed}. An empty range gives zeros.
     */
    public float[] smoothedRange(int firstBin, int lastBin, double lateralSigma) {
        float[] layer = new float[sizeX * sizeZ];
        int first = Math.max(0, firstBin);
        int last = Math.min(bins - 1, lastBin);
        if (last < first) {
            return layer;
        }
        int count = last - first + 1;
        for (int b = first; b <= last; b++) {
            int offset = b * sizeX * sizeZ;
            for (int i = 0; i < layer.length; i++) {
                layer[i] += voids[offset + i];
            }
        }
        for (int i = 0; i < layer.length; i++) {
            layer[i] /= count;
        }
        double sigmaCells = lateralSigma / spacing;
        if (sigmaCells < 0.35D) {
            return layer;
        }
        float[] kernel = gaussianKernel(sigmaCells);
        float[] pass = new float[layer.length];
        int radius = kernel.length / 2;
        for (int ix = 0; ix < sizeX; ix++) {
            for (int iz = 0; iz < sizeZ; iz++) {
                double sum = 0.0D;
                double weight = 0.0D;
                for (int k = -radius; k <= radius; k++) {
                    int jx = ix + k;
                    if (jx < 0 || jx >= sizeX) {
                        continue;
                    }
                    sum += kernel[k + radius] * layer[jx * sizeZ + iz];
                    weight += kernel[k + radius];
                }
                pass[ix * sizeZ + iz] = (float) (sum / weight);
            }
        }
        float[] result = new float[layer.length];
        for (int ix = 0; ix < sizeX; ix++) {
            for (int iz = 0; iz < sizeZ; iz++) {
                double sum = 0.0D;
                double weight = 0.0D;
                for (int k = -radius; k <= radius; k++) {
                    int jz = iz + k;
                    if (jz < 0 || jz >= sizeZ) {
                        continue;
                    }
                    sum += kernel[k + radius] * pass[ix * sizeZ + jz];
                    weight += kernel[k + radius];
                }
                result[ix * sizeZ + iz] = (float) (sum / weight);
            }
        }
        return result;
    }

    /** Bilinear lookup of a layer produced by {@link #smoothed}. */
    public double at(float[] layer, double x, double z) {
        double fx = Math.max(0.0D, Math.min(sizeX - 1.0D, (x - originX) / spacing));
        double fz = Math.max(0.0D, Math.min(sizeZ - 1.0D, (z - originZ) / spacing));
        int x0 = (int) Math.floor(fx);
        int z0 = (int) Math.floor(fz);
        int x1 = Math.min(sizeX - 1, x0 + 1);
        int z1 = Math.min(sizeZ - 1, z0 + 1);
        double tx = fx - x0;
        double tz = fz - z0;
        double a = layer[x0 * sizeZ + z0] * (1.0D - tz) + layer[x0 * sizeZ + z1] * tz;
        double b = layer[x1 * sizeZ + z0] * (1.0D - tz) + layer[x1 * sizeZ + z1] * tz;
        return a * (1.0D - tx) + b * tx;
    }

    /**
     * Effective number of independent probes behind one value of {@link #smoothedRange}: the probes
     * of the bins in range under the lateral kernel, counted as (Σw)²/Σw². The sampling variance of
     * that value is about φ(1−φ) divided by it.
     */
    public double effectiveSamples(int firstBin, int lastBin, double lateralSigma) {
        int binCount = Math.min(bins - 1, lastBin) - Math.max(0, firstBin) + 1;
        if (binCount <= 0) {
            return 0.0D;
        }
        double sigmaCells = lateralSigma / spacing;
        if (sigmaCells < 0.35D) {
            return binCount;
        }
        double sum = 0.0D;
        double squares = 0.0D;
        for (float weight : gaussianKernel(sigmaCells)) {
            sum += weight;
            squares += weight * weight;
        }
        double perAxis = sum * sum / squares;
        return binCount * perAxis * perAxis;
    }

    private static float[] gaussianKernel(double sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 2.5D));
        float[] kernel = new float[radius * 2 + 1];
        for (int i = -radius; i <= radius; i++) {
            kernel[i + radius] = (float) Math.exp(-0.5D * i * i / (sigma * sigma));
        }
        return kernel;
    }

    private static int clamp(int value, int size) {
        return Math.max(0, Math.min(size - 1, value));
    }
}

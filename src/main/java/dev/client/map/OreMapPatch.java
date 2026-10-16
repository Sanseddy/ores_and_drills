package dev.client.map;

import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.List;

/**
 * Render-ready geometry for one discovered deposit: a dense lookup grid of its block columns for hovering,
 * plus lazily built zoom levels for drawing (see {@link OreMapTiling}).
 */
public final class OreMapPatch {
    private final DiscoveredOreDeposit deposit;
    private final int minX;
    private final int minZ;
    private final int width;
    private final int depth;
    private final int[] cellIndex;
    private final int extent;
    private final OreMapTiling.Tiles[] levels = new OreMapTiling.Tiles[OreMapTiling.MAX_LEVEL + 1];
    private final long receivedAtMillis;
    private int baseColor;

    public OreMapPatch(DiscoveredOreDeposit deposit) {
        this(deposit, System.currentTimeMillis());
    }

    OreMapPatch(DiscoveredOreDeposit deposit, long receivedAtMillis) {
        this(deposit, receivedAtMillis, -1);
    }

    /** @param baseColor a fixed RGB colour, or -1 to resolve it from the ore on first use */
    OreMapPatch(DiscoveredOreDeposit deposit, long receivedAtMillis, int baseColor) {
        this.deposit = deposit;
        this.receivedAtMillis = receivedAtMillis;
        this.baseColor = baseColor;
        int lowX = 0;
        int lowZ = 0;
        int highX = 0;
        int highZ = 0;
        for (DiscoveredOreDeposit.OreMapCell cell : deposit.cells()) {
            lowX = Math.min(lowX, cell.offsetX());
            lowZ = Math.min(lowZ, cell.offsetZ());
            highX = Math.max(highX, cell.offsetX());
            highZ = Math.max(highZ, cell.offsetZ());
        }
        minX = lowX;
        minZ = lowZ;
        width = highX - lowX + 1;
        depth = highZ - lowZ + 1;
        cellIndex = new int[width * depth];
        Arrays.fill(cellIndex, -1);
        List<DiscoveredOreDeposit.OreMapCell> cells = deposit.cells();
        for (int index = 0; index < cells.size(); index++) {
            DiscoveredOreDeposit.OreMapCell cell = cells.get(index);
            cellIndex[(cell.offsetX() - minX) + (cell.offsetZ() - minZ) * width] = index;
        }
        // Half-size of a square centred on the deposit centre that contains every cell edge.
        extent = Math.max(Math.max(-lowX, highX + 1), Math.max(-lowZ, highZ + 1));
    }

    public DiscoveredOreDeposit deposit() {
        return deposit;
    }

    public long id() {
        return deposit.id();
    }

    public ResourceLocation dimension() {
        return deposit.dimension();
    }

    public BlockPos center() {
        return deposit.center();
    }

    public ResourceLocation oreId() {
        return deposit.oreId();
    }

    /** Blocks from the centre to the farthest cell edge along either axis. */
    public int extent() {
        return extent;
    }

    long receivedAtMillis() {
        return receivedAtMillis;
    }

    public boolean isEmpty() {
        return deposit.cells().isEmpty() || deposit.remainingOre() <= 0;
    }

    public int baseColor() {
        if (baseColor < 0) {
            baseColor = OreMapColors.colorFor(deposit.oreId());
        }
        return baseColor;
    }

    /** The cell covering this block column, or null when the column is not part of the deposit. */
    public DiscoveredOreDeposit.OreMapCell cellAt(int blockX, int blockZ) {
        int x = blockX - deposit.center().getX() - minX;
        int z = blockZ - deposit.center().getZ() - minZ;
        if (x < 0 || z < 0 || x >= width || z >= depth) {
            return null;
        }
        int index = cellIndex[x + z * width];
        return index < 0 ? null : deposit.cells().get(index);
    }

    /** Tiles to draw at this zoom; never smaller than one screen pixel. */
    OreMapTiling.Tiles tilesFor(double pixelsPerBlock) {
        int level = OreMapTiling.levelFor(pixelsPerBlock);
        OreMapTiling.Tiles tiles = levels[level];
        if (tiles == null) {
            List<DiscoveredOreDeposit.OreMapCell> cells = deposit.cells();
            int[] xs = new int[cells.size()];
            int[] zs = new int[cells.size()];
            long[] remaining = new long[cells.size()];
            long[] initial = new long[cells.size()];
            for (int index = 0; index < cells.size(); index++) {
                DiscoveredOreDeposit.OreMapCell cell = cells.get(index);
                xs[index] = cell.offsetX();
                zs[index] = cell.offsetZ();
                remaining[index] = cell.remainingOre();
                initial[index] = cell.initialOre();
            }
            tiles = OreMapTiling.tile(xs, zs, remaining, initial, 1 << level, baseColor());
            levels[level] = tiles;
        }
        return tiles;
    }
}

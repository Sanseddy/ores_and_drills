package dev.client.map;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

import java.util.Arrays;
import java.util.ArrayList;
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
    private int baseColor;

    public OreMapPatch(DiscoveredOreDeposit deposit) {
        this(deposit, -1);
    }

    /** @param baseColor a fixed RGB colour, or -1 to resolve it from the ore on first use */
    OreMapPatch(DiscoveredOreDeposit deposit, int baseColor) {
        this.deposit = deposit;
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

    /** Packs an x/z pair (a column offset from the centre, or a world column) into one map key. */
    static long columnKey(int x, int z) {
        return (long) x << 32 | z & 0xFFFFFFFFL;
    }

    /**
     * Returns a refreshed immutable patch with new {remaining, initial} amounts for the given column offsets
     * (keys from {@link #columnKey}), or this patch when nothing changed. As in the server's snapshot, a column
     * whose ore runs out leaves the map while its initial amount keeps counting towards the total.
     */
    OreMapPatch withColumns(Long2ObjectMap<int[]> columns) {
        if (columns.isEmpty()) {
            return this;
        }
        List<DiscoveredOreDeposit.OreMapCell> oldCells = deposit.cells();
        ArrayList<DiscoveredOreDeposit.OreMapCell> cells = new ArrayList<>(oldCells.size() + columns.size());
        LongOpenHashSet known = new LongOpenHashSet(Math.min(oldCells.size(), columns.size()));
        long initialDelta = 0L;
        boolean changed = false;
        for (DiscoveredOreDeposit.OreMapCell cell : oldCells) {
            long key = columnKey(cell.offsetX(), cell.offsetZ());
            int[] amounts = columns.get(key);
            if (amounts == null) {
                cells.add(cell);
                continue;
            }
            known.add(key);
            if (amounts[0] == cell.remainingOre() && amounts[1] == cell.initialOre()) {
                cells.add(cell);
                continue;
            }
            changed = true;
            initialDelta += amounts[1] - cell.initialOre();
            if (amounts[0] > 0) {
                cells.add(new DiscoveredOreDeposit.OreMapCell(cell.offsetX(), cell.offsetZ(), amounts[0], amounts[1]));
            }
        }
        for (Long2ObjectMap.Entry<int[]> column : columns.long2ObjectEntrySet()) {
            int[] amounts = column.getValue();
            if (amounts[0] <= 0 || known.contains(column.getLongKey())) {
                continue;
            }
            changed = true;
            initialDelta += amounts[1];
            cells.add(new DiscoveredOreDeposit.OreMapCell(
                    (int) (column.getLongKey() >> 32), (int) column.getLongKey(), amounts[0], amounts[1]
            ));
        }
        if (!changed) {
            return this;
        }
        long remaining = 0L;
        for (DiscoveredOreDeposit.OreMapCell cell : cells) {
            remaining += cell.remainingOre();
        }
        DiscoveredOreDeposit updated = new DiscoveredOreDeposit(
                deposit.id(), deposit.dimension(), deposit.center(), deposit.oreId(), deposit.tier(), deposit.color(),
                remaining, Math.max(remaining, deposit.initialOre() + initialDelta), cells
        );
        return new OreMapPatch(updated, baseColor);
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

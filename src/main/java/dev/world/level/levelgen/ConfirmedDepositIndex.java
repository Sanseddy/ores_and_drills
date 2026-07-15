package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Per-dimension SavedData index of deposits that were actually placed. */
public final class ConfirmedDepositIndex extends SavedData {
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_confirmed_deposits";
    private static final String TAG_DEPOSITS = "Deposits";
    private static final int CELL_SIZE = 1_024;
    private static final SavedData.Factory<ConfirmedDepositIndex> FACTORY =
            new SavedData.Factory<>(ConfirmedDepositIndex::new, ConfirmedDepositIndex::load);

    private final Map<Long, ConfirmedDeposit> byId = new LinkedHashMap<>();
    private final Map<Long, List<ConfirmedDeposit>> byCell = new HashMap<>();

    private ConfirmedDepositIndex() {
    }

    public static ConfirmedDepositIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public synchronized boolean confirm(
            DepositCandidate candidate,
            BlockPos actualCenter,
            int placedBlocks,
            int occupiedSections,
            boolean exposedToCave
    ) {
        if (!meetsConfirmationThreshold(candidate, placedBlocks, occupiedSections, exposedToCave)) {
            return false;
        }
        return add(new ConfirmedDeposit(
                candidate.depositId(), candidate.oreId(), candidate.tier(), actualCenter,
                placedBlocks, occupiedSections, candidate.wallDirection(), exposedToCave
        ));
    }

    /**
     * Confirmation describes what was physically written, not how that shape happened to straddle
     * 16-block section boundaries. A complete LARGE lens can legitimately fit into one or two sections;
     * rejecting it after placement left real deposits unindexed and made /locate skip them. Section count
     * remains persisted as useful shape metadata, while block count and cave-wall exposure are the actual
     * validity conditions shared by generated records and the locator.
     */
    static boolean meetsConfirmationThreshold(
            DepositCandidate candidate,
            int placedBlocks,
            int occupiedSections,
            boolean exposedToCave
    ) {
        return DepositConfirmationMath.meetsThreshold(
                placedBlocks,
                TieredDepositSampler.minimumPlacedBlocks(candidate),
                occupiedSections,
                candidate.wallDirection() != null,
                exposedToCave
        );
    }

    public synchronized boolean add(ConfirmedDeposit deposit) {
        ConfirmedDeposit previous = byId.put(deposit.depositId(), deposit);
        if (previous != null) {
            removeFromCell(previous);
        }
        byCell.computeIfAbsent(cellKey(deposit.center()), ignored -> new ArrayList<>()).add(deposit);
        if (!deposit.equals(previous)) {
            setDirty();
            return true;
        }
        return false;
    }

    public synchronized List<ConfirmedDeposit> recordsWithin(
            BlockPos origin,
            int radius,
            @Nullable ResourceLocation oreFilter
    ) {
        return recordsWithin(origin, radius, oreFilter, null);
    }

    public synchronized List<ConfirmedDeposit> recordsWithin(
            BlockPos origin,
            int radius,
            @Nullable ResourceLocation oreFilter,
            @Nullable OreDepositTier tierFilter
    ) {
        int minCellX = Math.floorDiv(origin.getX() - radius, CELL_SIZE);
        int maxCellX = Math.floorDiv(origin.getX() + radius, CELL_SIZE);
        int minCellZ = Math.floorDiv(origin.getZ() - radius, CELL_SIZE);
        int maxCellZ = Math.floorDiv(origin.getZ() + radius, CELL_SIZE);
        double radiusSqr = (double) radius * radius;
        List<ConfirmedDeposit> result = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                List<ConfirmedDeposit> records = byCell.get(cellKey(cellX, cellZ));
                if (records == null) {
                    continue;
                }
                for (ConfirmedDeposit record : records) {
                    if ((oreFilter == null || oreFilter.equals(record.oreId()))
                            && (tierFilter == null || tierFilter == record.tier())
                            && horizontalDistanceSqr(origin, record.center()) <= radiusSqr) {
                        result.add(record);
                    }
                }
            }
        }
        result.sort(Comparator.comparingDouble(record -> horizontalDistanceSqr(origin, record.center())));
        return List.copyOf(result);
    }

    /** Keeps the authoritative record alive until its final tracked block is removed. */
    public synchronized void markBlockDepleted(BlockPos pos, ResourceLocation oreId) {
        int radius = OreDepositFeature.MAX_DEPOSIT_FOOTPRINT_DIAMETER;
        ConfirmedDeposit nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ConfirmedDeposit candidate : recordsWithin(pos, radius, oreId)) {
            if (Math.abs(candidate.center().getY() - pos.getY()) > OreDepositFeature.MAX_UNDERGROUND_DEPTH) {
                continue;
            }
            double distance = horizontalDistanceSqr(pos, candidate.center());
            if (distance < nearestDistance) {
                nearest = candidate;
                nearestDistance = distance;
            }
        }
        if (nearest == null) {
            return;
        }

        removeFromCell(nearest);
        if (nearest.placedBlocks() <= 1) {
            byId.remove(nearest.depositId());
        } else {
            ConfirmedDeposit updated = new ConfirmedDeposit(
                    nearest.depositId(), nearest.oreId(), nearest.tier(), nearest.center(),
                    nearest.placedBlocks() - 1, nearest.occupiedSections(),
                    nearest.wallDirection(), nearest.exposedToCave()
            );
            byId.put(updated.depositId(), updated);
            byCell.computeIfAbsent(cellKey(updated.center()), ignored -> new ArrayList<>()).add(updated);
        }
        setDirty();
    }

    private static ConfirmedDepositIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        ConfirmedDepositIndex index = new ConfirmedDepositIndex();
        ListTag records = tag.getList(TAG_DEPOSITS, Tag.TAG_COMPOUND);
        for (int i = 0; i < records.size(); i++) {
            CompoundTag record = records.getCompound(i);
            ResourceLocation oreId = ResourceLocation.tryParse(record.getString("Ore"));
            OreDepositTier tier = record.contains("Tier", Tag.TAG_INT)
                    ? OreDepositTier.fromIndex(record.getInt("Tier")).orElse(null)
                    : null;
            int placedBlocks = record.getInt("PlacedBlocks");
            if (oreId == null || tier == null || placedBlocks < 1) {
                continue;
            }
            Direction wallDirection = record.contains("WallDirection", Tag.TAG_INT)
                    ? Direction.from3DDataValue(record.getInt("WallDirection"))
                    : null;
            if (wallDirection != null && wallDirection.getAxis().isVertical()) {
                wallDirection = null;
            }
            boolean exposedToCave = record.getBoolean("ExposedToCave");
            if (wallDirection != null && !exposedToCave) {
                continue;
            }
            ConfirmedDeposit deposit = new ConfirmedDeposit(
                    record.getLong("Id"),
                    oreId,
                    tier,
                    new BlockPos(record.getInt("X"), record.getInt("Y"), record.getInt("Z")),
                    placedBlocks,
                    Math.max(1, record.contains("OccupiedSections", Tag.TAG_INT)
                            ? record.getInt("OccupiedSections")
                            : 1),
                    wallDirection,
                    exposedToCave
            );
            index.byId.put(deposit.depositId(), deposit);
            index.byCell.computeIfAbsent(cellKey(deposit.center()), ignored -> new ArrayList<>()).add(deposit);
        }
        return index;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag records = new ListTag();
        for (ConfirmedDeposit deposit : byId.values()) {
            CompoundTag record = new CompoundTag();
            record.putLong("Id", deposit.depositId());
            record.putString("Ore", deposit.oreId().toString());
            record.putInt("Tier", deposit.tier().index());
            record.putInt("X", deposit.center().getX());
            record.putInt("Y", deposit.center().getY());
            record.putInt("Z", deposit.center().getZ());
            record.putInt("PlacedBlocks", deposit.placedBlocks());
            record.putInt("OccupiedSections", deposit.occupiedSections());
            if (deposit.wallDirection() != null) {
                record.putInt("WallDirection", deposit.wallDirection().get3DDataValue());
            }
            record.putBoolean("ExposedToCave", deposit.exposedToCave());
            records.add(record);
        }
        tag.put(TAG_DEPOSITS, records);
        return tag;
    }

    private void removeFromCell(ConfirmedDeposit deposit) {
        long key = cellKey(deposit.center());
        List<ConfirmedDeposit> records = byCell.get(key);
        if (records == null) {
            return;
        }
        records.removeIf(existing -> existing.depositId() == deposit.depositId());
        if (records.isEmpty()) {
            byCell.remove(key);
        }
    }

    private static long cellKey(BlockPos pos) {
        return cellKey(Math.floorDiv(pos.getX(), CELL_SIZE), Math.floorDiv(pos.getZ(), CELL_SIZE));
    }

    private static long cellKey(int cellX, int cellZ) {
        return ((long) cellX << 32) ^ (cellZ & 0xFFFFFFFFL);
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return (double) dx * dx + (double) dz * dz;
    }
}

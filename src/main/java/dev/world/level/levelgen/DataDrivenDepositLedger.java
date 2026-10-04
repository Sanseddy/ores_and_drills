package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.LongArrayTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.saveddata.SavedData;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-world count of forced datapack deposits in every biome instance (one cave, one biome patch).
 * A chunk reserves a slot before it builds a deposit and commits it only after the deposit is placed, so a
 * failed attempt leaves the slot for another chunk of the same instance and the total never exceeds count.
 */
public final class DataDrivenDepositLedger extends SavedData {
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_data_driven_deposits";
    private static final SavedData.Factory<DataDrivenDepositLedger> FACTORY =
            new SavedData.Factory<>(DataDrivenDepositLedger::new, DataDrivenDepositLedger::load);

    private final Map<String, List<Long>> placed = new HashMap<>();
    private final Map<String, List<Long>> reserved = new HashMap<>();

    private DataDrivenDepositLedger() {
    }

    public static synchronized DataDrivenDepositLedger get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    /**
     * Reserves one of {@code count} slots of the instance for a deposit centered at {@code center}, or returns
     * {@code null} when the instance is full or another deposit of it is closer than {@code spacing}.
     */
    public synchronized Reservation tryReserve(String instanceKey, int count, BlockPos center, int spacing) {
        List<Long> done = placed.getOrDefault(instanceKey, List.of());
        List<Long> pending = reserved.computeIfAbsent(instanceKey, ignored -> new ArrayList<>());
        if (done.size() + pending.size() >= Math.max(1, count)
                || tooClose(done, center, spacing) || tooClose(pending, center, spacing)) {
            if (pending.isEmpty()) {
                reserved.remove(instanceKey);
            }
            return null;
        }
        pending.add(center.asLong());
        return new Reservation(this, instanceKey, center.asLong());
    }

    public synchronized int placedCount(String instanceKey) {
        return placed.getOrDefault(instanceKey, List.of()).size();
    }

    private synchronized void finish(Reservation reservation, boolean success) {
        List<Long> pending = reserved.get(reservation.instanceKey());
        if (pending != null) {
            pending.remove(Long.valueOf(reservation.center()));
            if (pending.isEmpty()) {
                reserved.remove(reservation.instanceKey());
            }
        }
        if (success) {
            placed.computeIfAbsent(reservation.instanceKey(), ignored -> new ArrayList<>()).add(reservation.center());
            setDirty();
        }
    }

    private static boolean tooClose(List<Long> centers, BlockPos center, int spacing) {
        long spacingSquared = (long) spacing * spacing;
        for (long packed : centers) {
            long dx = (long) BlockPos.getX(packed) - center.getX();
            long dz = (long) BlockPos.getZ(packed) - center.getZ();
            if (dx * dx + dz * dz < spacingSquared) {
                return true;
            }
        }
        return false;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        CompoundTag instances = new CompoundTag();
        placed.forEach((key, centers) -> instances.put(
                key, new LongArrayTag(centers.stream().mapToLong(Long::longValue).toArray())
        ));
        tag.put("Instances", instances);
        return tag;
    }

    private static DataDrivenDepositLedger load(CompoundTag tag, HolderLookup.Provider registries) {
        DataDrivenDepositLedger ledger = new DataDrivenDepositLedger();
        CompoundTag instances = tag.getCompound("Instances");
        for (String key : instances.getAllKeys()) {
            if (instances.contains(key, Tag.TAG_LONG_ARRAY)) {
                List<Long> centers = new ArrayList<>();
                for (long center : instances.getLongArray(key)) {
                    centers.add(center);
                }
                ledger.placed.put(key, centers);
            }
        }
        return ledger;
    }

    public record Reservation(DataDrivenDepositLedger ledger, String instanceKey, long center) {
        public void commit() {
            ledger.finish(this, true);
        }

        public void release() {
            ledger.finish(this, false);
        }
    }
}

package dev.world.level.levelgen;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

public final class OreDepositChunkData {
    private static final int AMOUNT_BITS = 23;
    private static final long AMOUNT_MASK = (1L << AMOUNT_BITS) - 1L;
    private static final int INITIAL_SHIFT = AMOUNT_BITS;
    private static final int TIER_SHIFT = AMOUNT_BITS * 2;
    private static final int ORE_SHIFT = TIER_SHIFT + 2;
    private static final int TIER_BITS = 2;
    private static final long TIER_MASK = (1L << TIER_BITS) - 1L;
    private static final int ORE_BITS = 8;
    private static final long ORE_MASK = (1L << ORE_BITS) - 1L;
    private static final int BASE_SHIFT = ORE_SHIFT + ORE_BITS;
    private static final long MISSING = Long.MIN_VALUE;
    private static final Snapshot EMPTY_SNAPSHOT = new Snapshot(new int[0], new long[0]);
    private static final AtomicLong INSTANCES = new AtomicLong();

    private final Int2LongOpenHashMap entries = new Int2LongOpenHashMap();
    private Snapshot cachedSnapshot;
    private DiskSnapshot cachedDiskSnapshot;
    private final long instanceId = INSTANCES.incrementAndGet();

    public OreDepositChunkData() {
        entries.defaultReturnValue(MISSING);
    }

    /**
     * Unique per instance and never 0. Every attachment sync hands the client a new instance, so a
     * changed id tells client code that a chunk's data was replaced without it holding on to the
     * old copy.
     */
    public long instanceId() {
        return instanceId;
    }

    public synchronized Entry get(BlockPos pos) {
        int key = localKey(pos);
        long packed = entries.get(key);
        return packed == MISSING ? null : unpack(packed);
    }

    public synchronized void put(
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness) {
        put(
                pos,
                baseIndex,
                oreIndex,
                remainingOre,
                initialOre,
                initialRichness,
                Math.max(1, (int) Math.ceil(initialOre * 0.5D)));
    }

    public synchronized void put(
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness,
            int richnessReferenceAmount) {
        put(
                pos,
                baseIndex,
                oreIndex,
                remainingOre,
                initialOre,
                initialRichness,
                richnessReferenceAmount,
                -1);
    }

    public synchronized void put(
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness,
            int richnessReferenceAmount,
            int tier) {
        putIfChanged(
                pos,
                baseIndex,
                oreIndex,
                remainingOre,
                initialOre,
                initialRichness,
                richnessReferenceAmount,
                tier);
    }

    synchronized boolean putIfChanged(
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness,
            int richnessReferenceAmount,
            int tier) {
        int key = localKey(pos);
        long packed = pack(baseIndex, oreIndex, remainingOre, initialOre, tier);
        long previous = entries.put(key, packed);
        if (previous != MISSING && previous == packed) {
            return false;
        }
        cachedSnapshot = null;
        cachedDiskSnapshot = null;
        return true;
    }

    /**
     * Applies the completed world-generation batch while holding the attachment lock only once.
     * Worldgen commonly puts hundreds of blocks into the same chunk; taking the monitor for every
     * one of them would make that local operation needlessly expensive on decoration workers.
     */
    public synchronized void putGeneratedBatch(Iterable<OreDepositData.GeneratedDeposit> deposits) {
        putGeneratedBatchIfChanged(deposits);
    }

    synchronized boolean putGeneratedBatchIfChanged(
            Iterable<OreDepositData.GeneratedDeposit> deposits) {
        boolean changed = false;
        if (deposits instanceof java.util.Collection<?> batch)
            entries.ensureCapacity(entries.size() + batch.size());
        for (OreDepositData.GeneratedDeposit deposit : deposits) {
            BlockPos pos = deposit.pos();
            int amount = Math.max(0, deposit.amount());
            long packed =
                    pack(deposit.baseIndex(), deposit.oreIndex(), amount, amount, deposit.tier());
            long previous = entries.put(localKey(pos), packed);
            changed |= previous == MISSING || previous != packed;
        }
        if (changed) {
            cachedSnapshot = null;
            cachedDiskSnapshot = null;
        }
        return changed;
    }

    public synchronized boolean remove(BlockPos pos) {
        int key = localKey(pos);
        if (entries.remove(key) == MISSING) {
            return false;
        }
        cachedSnapshot = null;
        cachedDiskSnapshot = null;
        return true;
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    /**
     * @param chunkX/chunkZ the chunk's own coordinates, needed to turn each entry's packed local
     *     position back into a world {@link BlockPos}
     */
    public synchronized void forEach(int chunkX, int chunkZ, BiConsumer<BlockPos, Entry> consumer) {
        for (Int2LongMap.Entry mapEntry : entries.int2LongEntrySet()) {
            int key = mapEntry.getIntKey();
            long packed = mapEntry.getLongValue();
            if (packed == MISSING) {
                continue;
            }
            consumer.accept(posFromKey(chunkX, chunkZ, key), unpack(packed));
        }
    }

    private static BlockPos posFromKey(int chunkX, int chunkZ, int key) {
        int localX = key & 15;
        int localZ = (key >> 4) & 15;
        int y = (short) ((key >> 8) & 0xFFFF);
        return new BlockPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }

    /**
     * Returns immutable-by-convention arrays shared by disk serialization and attachment sync. A
     * new snapshot is materialized only after the map has actually changed.
     */
    public synchronized Snapshot snapshot() {
        if (cachedSnapshot != null) {
            return cachedSnapshot;
        }
        if (entries.isEmpty()) {
            cachedSnapshot = EMPTY_SNAPSHOT;
            return cachedSnapshot;
        }

        int size = entries.size();
        int[] keys = new int[size];
        long[] values = new long[size];
        int index = 0;
        var iterator = entries.int2LongEntrySet().fastIterator();
        while (iterator.hasNext()) {
            Int2LongMap.Entry entry = iterator.next();
            keys[index] = entry.getIntKey();
            values[index] = entry.getLongValue();
            index++;
        }
        cachedSnapshot = new Snapshot(keys, values);
        return cachedSnapshot;
    }

    public synchronized void load(int[] keys, long[] values) {
        entries.clear();
        cachedSnapshot = null;
        cachedDiskSnapshot = null;
        int size = Math.min(keys.length, values.length);
        entries.ensureCapacity(size);
        for (int index = 0; index < size; index++) {
            if (values[index] != MISSING) {
                entries.put(keys[index], values[index]);
            }
        }
    }

    /** Disk format only; the attachment network protocol stays unchanged. */
    public synchronized CompoundTag saveTag() {
        if (cachedDiskSnapshot == null) cachedDiskSnapshot = encodeDisk(snapshot());
        DiskSnapshot disk = cachedDiskSnapshot;
        CompoundTag tag = new CompoundTag();
        tag.putInt("Format", 1);
        tag.putByteArray("PackedKeys", disk.keys());
        if (disk.indices() == null) tag.putLongArray("Values", disk.values());
        else {
            tag.putLongArray("ValuePalette", disk.values());
            tag.putByteArray("ValueIndices", disk.indices());
        }
        return tag;
    }

    public synchronized void loadTag(CompoundTag tag) {
        if (tag.getInt("Format") != 1 || !tag.contains("PackedKeys", Tag.TAG_BYTE_ARRAY))
            throw new IllegalArgumentException(
                    "Unsupported ore deposit data format; a new world is required");
        byte[] packed = tag.getByteArray("PackedKeys");
        if (packed.length % 3 != 0)
            throw new IllegalArgumentException("Invalid ore deposit positions");
        int[] keys = new int[packed.length / 3];
        for (int i = 0; i < keys.length; i++)
            keys[i] =
                    (packed[i * 3] & 255)
                            | ((packed[i * 3 + 1] & 255) << 8)
                            | ((packed[i * 3 + 2] & 255) << 16);
        long[] values;
        if (tag.contains("ValuePalette", Tag.TAG_LONG_ARRAY)) {
            long[] palette = tag.getLongArray("ValuePalette");
            byte[] indices = tag.getByteArray("ValueIndices");
            int width = indexBytes(palette.length);
            if (indices.length != (long) keys.length * width)
                throw new IllegalArgumentException("Invalid ore deposit value indices");
            values = new long[keys.length];
            for (int i = 0; i < values.length; i++) {
                int index = 0;
                for (int b = 0; b < width; b++) index |= (indices[i * width + b] & 255) << (b * 8);
                if (index < 0 || index >= palette.length)
                    throw new IllegalArgumentException("Invalid ore deposit value palette");
                values[i] = palette[index];
            }
        } else values = tag.getLongArray("Values");
        if (values.length != keys.length)
            throw new IllegalArgumentException("Invalid ore deposit values");
        load(keys, values);
    }

    private static DiskSnapshot encodeDisk(Snapshot snapshot) {
        int[] keys = snapshot.keys();
        long[] values = snapshot.values();
        byte[] packedKeys = new byte[keys.length * 3];
        for (int i = 0; i < keys.length; i++)
            for (int b = 0; b < 3; b++) packedKeys[i * 3 + b] = (byte) (keys[i] >>> (b * 8));
        var paletteIndices = new it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap();
        paletteIndices.defaultReturnValue(-1);
        var palette = new it.unimi.dsi.fastutil.longs.LongArrayList();
        for (long value : values)
            if (paletteIndices.get(value) < 0) {
                paletteIndices.put(value, palette.size());
                palette.add(value);
            }
        int width = indexBytes(palette.size());
        // High-entropy, partially mined chunks can be smaller without a palette.
        if ((long) palette.size() * 8 + (long) values.length * width >= values.length * 8L)
            return new DiskSnapshot(packedKeys, values, null);
        byte[] indices = new byte[values.length * width];
        for (int i = 0; i < values.length; i++) {
            int index = paletteIndices.get(values[i]);
            for (int b = 0; b < width; b++) indices[i * width + b] = (byte) (index >>> (b * 8));
        }
        return new DiskSnapshot(packedKeys, palette.toLongArray(), indices);
    }

    private static int indexBytes(int paletteSize) {
        return paletteSize <= 256 ? 1 : paletteSize <= 65536 ? 2 : 4;
    }

    private record DiskSnapshot(byte[] keys, long[] values, byte[] indices) {}

    private static int localKey(BlockPos pos) {
        return (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 0xFFFF) << 8);
    }

    private static long pack(
            int baseIndex, int oreIndex, int remainingOre, int initialOre, int tier) {
        long remaining = clampAmount(remainingOre);
        long initial = Math.max(remaining, clampAmount(initialOre));
        long encodedTier = Math.max(0, Math.min(TIER_MASK, tier));
        long ore = Math.max(0, Math.min(OreDepositOrePalette.MAX_ORES - 1, oreIndex));
        long base = Math.max(0, Math.min(OreDepositStonePalette.MAX_BASES - 1, baseIndex));
        return remaining
                | (initial << INITIAL_SHIFT)
                | (encodedTier << TIER_SHIFT)
                | (ore << ORE_SHIFT)
                | (base << BASE_SHIFT);
    }

    private static Entry unpack(long packed) {
        int remaining = (int) (packed & AMOUNT_MASK);
        int initial = (int) ((packed >> INITIAL_SHIFT) & AMOUNT_MASK);
        int tier = (int) ((packed >> TIER_SHIFT) & TIER_MASK);
        int oreIndex = (int) ((packed >> ORE_SHIFT) & ORE_MASK);
        int baseIndex = (int) (packed >> BASE_SHIFT);
        return new Entry(baseIndex, oreIndex, remaining, initial, 0, 0, tier);
    }

    private static long clampAmount(int amount) {
        return Math.max(0L, Math.min(AMOUNT_MASK, amount));
    }

    public record Entry(
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness,
            int richnessReferenceAmount,
            int tier) {}

    public record Snapshot(int[] keys, long[] values) {}
}

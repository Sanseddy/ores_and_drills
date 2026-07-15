package dev.world.level.levelgen;

import it.unimi.dsi.fastutil.ints.Int2LongMap;
import it.unimi.dsi.fastutil.ints.Int2LongOpenHashMap;
import net.minecraft.core.BlockPos;

import java.util.function.BiConsumer;

public final class OreDepositChunkData {
    private static final int AMOUNT_BITS = 23;
    private static final long AMOUNT_MASK = (1L << AMOUNT_BITS) - 1L;
    private static final int INITIAL_SHIFT = AMOUNT_BITS;
    private static final int TIER_SHIFT = AMOUNT_BITS * 2;
    private static final int ORE_SHIFT = TIER_SHIFT + 2;
    private static final int TIER_BITS = 2;
    private static final long TIER_MASK = (1L << TIER_BITS) - 1L;
    // Read-only compatibility constants for chunks saved before fill_stage became absolute.
    private static final int LEGACY_RICHNESS_REFERENCE_RICHNESS_SHIFT = 24;
    private static final int LEGACY_RICHNESS_REFERENCE_TIER_SHIFT = LEGACY_RICHNESS_REFERENCE_RICHNESS_SHIFT + 4;
    private static final int MAX_RICHNESS = 7;
    private static final int ORE_BITS = 8;
    private static final long ORE_MASK = (1L << ORE_BITS) - 1L;
    private static final int BASE_SHIFT = ORE_SHIFT + ORE_BITS;
    private static final long MISSING = Long.MIN_VALUE;

    private final Int2LongOpenHashMap entries = new Int2LongOpenHashMap();

    public OreDepositChunkData() {
        entries.defaultReturnValue(MISSING);
    }

    public synchronized Entry get(BlockPos pos) {
        int key = localKey(pos);
        long packed = entries.get(key);
        return packed == MISSING ? null : unpack(packed, 0);
    }

    public synchronized void put(BlockPos pos, int baseIndex, int oreIndex, int remainingOre, int initialOre, int initialRichness) {
        put(pos, baseIndex, oreIndex, remainingOre, initialOre, initialRichness, Math.max(1, (int)Math.ceil(initialOre * 0.5D)));
    }

    public synchronized void put(
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness,
            int richnessReferenceAmount
    ) {
        put(pos, baseIndex, oreIndex, remainingOre, initialOre, initialRichness, richnessReferenceAmount, -1);
    }

    public synchronized void put(
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness,
            int richnessReferenceAmount,
            int tier
    ) {
        int key = localKey(pos);
        entries.put(key, pack(baseIndex, oreIndex, remainingOre, initialOre, tier));
    }

    /**
     * Applies the completed world-generation batch while holding the attachment lock only once.
     * Worldgen commonly puts hundreds of blocks into the same chunk; taking the monitor for every
     * one of them made that otherwise local operation needlessly expensive on decoration workers.
     */
    public synchronized void putGeneratedBatch(Iterable<OreDepositData.GeneratedDeposit> deposits) {
        for (OreDepositData.GeneratedDeposit deposit : deposits) {
            BlockPos pos = deposit.pos();
            int amount = Math.max(0, deposit.amount());
            entries.put(
                    localKey(pos),
                    pack(deposit.baseIndex(), deposit.oreIndex(), amount, amount, deposit.tier())
            );
        }
    }

    public synchronized boolean remove(BlockPos pos) {
        int key = localKey(pos);
        return entries.remove(key) != MISSING;
    }

    public synchronized boolean isEmpty() {
        return entries.isEmpty();
    }

    public synchronized int size() {
        return entries.size();
    }

    /** @param chunkX/chunkZ the chunk's own coordinates, needed to turn each entry's packed local position back into a world {@link BlockPos} */
    public synchronized void forEach(int chunkX, int chunkZ, BiConsumer<BlockPos, Entry> consumer) {
        for (Int2LongMap.Entry mapEntry : entries.int2LongEntrySet()) {
            int key = mapEntry.getIntKey();
            long packed = mapEntry.getLongValue();
            if (packed == MISSING) {
                continue;
            }
            consumer.accept(posFromKey(chunkX, chunkZ, key), unpack(packed, 0));
        }
    }

    private static BlockPos posFromKey(int chunkX, int chunkZ, int key) {
        int localX = key & 15;
        int localZ = (key >> 4) & 15;
        int y = (short)((key >> 8) & 0xFFFF);
        return new BlockPos((chunkX << 4) + localX, y, (chunkZ << 4) + localZ);
    }

    public synchronized Snapshot snapshot() {
        int size = entries.size();
        int[] keys = new int[size];
        long[] values = new long[size];
        int index = 0;
        for (Int2LongMap.Entry entry : entries.int2LongEntrySet()) {
            keys[index] = entry.getIntKey();
            values[index] = entry.getLongValue();
            index++;
        }
        return new Snapshot(keys, values);
    }

    public synchronized void load(int[] keys, long[] values) {
        load(keys, values, new int[0]);
    }

    public synchronized void load(int[] keys, long[] values, int[] richnessReferenceValues) {
        entries.clear();
        int size = Math.min(keys.length, values.length);
        for (int index = 0; index < size; index++) {
            if (values[index] != MISSING) {
                long migrated = migrateLegacyTier(values[index], index < richnessReferenceValues.length ? richnessReferenceValues[index] : 0);
                entries.put(keys[index], migrated);
            }
        }
    }

    private static int localKey(BlockPos pos) {
        return (pos.getX() & 15) | ((pos.getZ() & 15) << 4) | ((pos.getY() & 0xFFFF) << 8);
    }

    private static long pack(int baseIndex, int oreIndex, int remainingOre, int initialOre, int tier) {
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

    private static Entry unpack(long packed, int ignoredLegacyReference) {
        int remaining = (int)(packed & AMOUNT_MASK);
        int initial = (int)((packed >> INITIAL_SHIFT) & AMOUNT_MASK);
        int tier = (int)((packed >> TIER_SHIFT) & TIER_MASK);
        int oreIndex = (int)((packed >> ORE_SHIFT) & ORE_MASK);
        int baseIndex = (int)(packed >> BASE_SHIFT);
        return new Entry(baseIndex, oreIndex, remaining, initial, 0, 0, tier);
    }

    private static long clampAmount(int amount) {
        return Math.max(0L, Math.min(AMOUNT_MASK, amount));
    }

    private static long migrateLegacyTier(long packed, int legacyReference) {
        if (legacyReference == 0) {
            return packed;
        }

        int encodedTier = (legacyReference >> LEGACY_RICHNESS_REFERENCE_TIER_SHIFT) & 15;
        int tier = encodedTier > 0 ? encodedTier - 1 : 0;
        return (packed & ~(TIER_MASK << TIER_SHIFT)) | ((long)Math.min(TIER_MASK, tier) << TIER_SHIFT);
    }

    public record Entry(int baseIndex, int oreIndex, int remainingOre, int initialOre, int initialRichness, int richnessReferenceAmount, int tier) {
    }

    public record Snapshot(int[] keys, long[] values) {
    }
}

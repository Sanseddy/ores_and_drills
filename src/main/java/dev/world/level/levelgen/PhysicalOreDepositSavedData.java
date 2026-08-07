package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.saveddata.SavedData;

/**
 * Durable mirror for ore metadata moved into Sable's hidden plot chunks.
 *
 * <p>Sable persists the blocks that make up a sub-level, but its storage path does not serialize
 * arbitrary NeoForge chunk attachments. Normal deposits continue to use their chunk attachment;
 * only entries that pass through a physical assembly are mirrored here.</p>
 */
public final class PhysicalOreDepositSavedData extends SavedData {
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_physical_ore_deposits";
    private static final String TAG_CHUNKS = "Chunks";
    private static final String TAG_CHUNK_X = "ChunkX";
    private static final String TAG_CHUNK_Z = "ChunkZ";
    private static final String TAG_KEYS = "Keys";
    private static final String TAG_VALUES = "Values";
    private static final SavedData.Factory<PhysicalOreDepositSavedData> FACTORY =
            new SavedData.Factory<>(PhysicalOreDepositSavedData::new, PhysicalOreDepositSavedData::load);

    private final Long2ObjectOpenHashMap<OreDepositChunkData> entriesByChunk = new Long2ObjectOpenHashMap<>();

    PhysicalOreDepositSavedData() {
    }

    public static PhysicalOreDepositSavedData get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    public synchronized void put(BlockPos pos, OreDepositChunkData.Entry entry) {
        OreDepositChunkData data = entriesByChunk.computeIfAbsent(chunkKey(pos), ignored -> new OreDepositChunkData());
        if (data.putIfChanged(
                pos,
                entry.baseIndex(),
                entry.oreIndex(),
                entry.remainingOre(),
                entry.initialOre(),
                entry.initialRichness(),
                entry.richnessReferenceAmount(),
                entry.tier()
        )) {
            setDirty();
        }
    }

    public synchronized void updateIfTracked(BlockPos pos, OreDepositChunkData.Entry entry) {
        OreDepositChunkData data = entriesByChunk.get(chunkKey(pos));
        if (data != null && data.get(pos) != null && data.putIfChanged(
                pos,
                entry.baseIndex(),
                entry.oreIndex(),
                entry.remainingOre(),
                entry.initialOre(),
                entry.initialRichness(),
                entry.richnessReferenceAmount(),
                entry.tier()
        )) {
            setDirty();
        }
    }

    public synchronized boolean remove(BlockPos pos) {
        long chunkKey = chunkKey(pos);
        OreDepositChunkData data = entriesByChunk.get(chunkKey);
        if (data == null || !data.remove(pos)) {
            return false;
        }
        if (data.isEmpty()) {
            entriesByChunk.remove(chunkKey);
        }
        setDirty();
        return true;
    }

    /** Restores one authoritative mirrored entry before legacy fallback data can be synthesized. */
    public synchronized OreDepositChunkData.Entry restoreEntry(
            ServerLevel level,
            ChunkAccess targetChunk,
            BlockPos pos
    ) {
        OreDepositChunkData persisted = entriesByChunk.get(chunkKey(pos));
        OreDepositChunkData.Entry entry = persisted != null ? persisted.get(pos) : null;
        if (entry == null) {
            return null;
        }
        if (!isDepositState(level.getBlockState(pos))) {
            remove(pos);
            return null;
        }

        OreDepositChunkData target = targetChunk.getData(ModAttachments.ORE_DEPOSITS);
        if (put(target, pos, entry)) {
            targetChunk.setUnsaved(true);
        }
        return entry;
    }

    /** Rehydrates an entire plot chunk before Sable sends its initial attachment packet. */
    public synchronized boolean restoreChunk(ServerLevel level, ChunkAccess targetChunk) {
        OreDepositChunkData persisted = entriesByChunk.get(targetChunk.getPos().toLong());
        if (persisted == null || persisted.isEmpty()) {
            return false;
        }

        OreDepositChunkData target = targetChunk.getData(ModAttachments.ORE_DEPOSITS);
        boolean[] changed = {false};
        java.util.ArrayList<BlockPos> stale = new java.util.ArrayList<>();
        persisted.forEach(targetChunk.getPos().x, targetChunk.getPos().z, (pos, entry) -> {
            BlockState state = level.getBlockState(pos);
            if (!isDepositState(state)) {
                stale.add(pos);
                return;
            }
            changed[0] |= put(target, pos, entry);
        });
        for (BlockPos pos : stale) {
            remove(pos);
        }
        if (changed[0]) {
            targetChunk.setUnsaved(true);
        }
        return changed[0];
    }

    private static boolean put(OreDepositChunkData target, BlockPos pos, OreDepositChunkData.Entry entry) {
        return target.putIfChanged(
                pos,
                entry.baseIndex(),
                entry.oreIndex(),
                entry.remainingOre(),
                entry.initialOre(),
                entry.initialRichness(),
                entry.richnessReferenceAmount(),
                entry.tier()
        );
    }

    private static boolean isDepositState(BlockState state) {
        return state.is(ModBlocks.ORE_DEPOSIT.get()) || state.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get());
    }

    private static long chunkKey(BlockPos pos) {
        return ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
    }

    static PhysicalOreDepositSavedData load(CompoundTag tag, HolderLookup.Provider registries) {
        PhysicalOreDepositSavedData result = new PhysicalOreDepositSavedData();
        ListTag chunks = tag.getList(TAG_CHUNKS, Tag.TAG_COMPOUND);
        for (int index = 0; index < chunks.size(); index++) {
            CompoundTag chunkTag = chunks.getCompound(index);
            OreDepositChunkData data = new OreDepositChunkData();
            data.load(chunkTag.getIntArray(TAG_KEYS), chunkTag.getLongArray(TAG_VALUES));
            if (!data.isEmpty()) {
                result.entriesByChunk.put(ChunkPos.asLong(
                        chunkTag.getInt(TAG_CHUNK_X),
                        chunkTag.getInt(TAG_CHUNK_Z)
                ), data);
            }
        }
        return result;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        ListTag chunks = new ListTag();
        entriesByChunk.long2ObjectEntrySet().forEach(mapEntry -> {
            OreDepositChunkData.Snapshot snapshot = mapEntry.getValue().snapshot();
            if (snapshot.keys().length == 0) {
                return;
            }
            ChunkPos chunkPos = new ChunkPos(mapEntry.getLongKey());
            CompoundTag chunkTag = new CompoundTag();
            chunkTag.putInt(TAG_CHUNK_X, chunkPos.x);
            chunkTag.putInt(TAG_CHUNK_Z, chunkPos.z);
            chunkTag.putIntArray(TAG_KEYS, snapshot.keys());
            chunkTag.putLongArray(TAG_VALUES, snapshot.values());
            chunks.add(chunkTag);
        });
        tag.put(TAG_CHUNKS, chunks);
        return tag;
    }
}

package dev.client.map;

import dev.registry.ModAttachments;
import dev.world.level.levelgen.OreDepositChunkData;
import dev.world.level.levelgen.OreDepositFeature;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreUnifier;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongIterator;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Keeps discovered patches current every tick from the ore data the client already holds for
 * rendering deposits, so the server is asked only to discover a patch and never to refresh one. Two
 * client-side signals drive the updates, and neither costs the server anything:
 *
 * <ul>
 *   <li>a synced block amount, which re-sums that one column (a few hash lookups);
 *   <li>a chunk whose ore attachment was replaced by a fresh sync, e.g. when it loads, which
 *       re-sums the patch's slice of that chunk and so also fills in columns that were not loaded
 *       at discovery.
 * </ul>
 *
 * Columns are summed exactly like the server's {@code OreDepositData.mapSnapshot}.
 */
final class OreMapLiveRefresh {
    private static final int RADIUS = OreDepositFeature.MAX_DEPOSIT_FOOTPRINT_DIAMETER;
    private static final long RADIUS_SQR = (long) RADIUS * RADIUS;
    private static final int VERTICAL_REACH = 12;

    /**
     * Chunk slices re-summed per tick; the rest wait a tick, so loading a whole area never stalls a
     * frame.
     */
    private static final int SLICE_BUDGET = 16;

    private static final long NEVER_SEEN = 0L;

    private final Map<Long, Tracked> tracked = new HashMap<>();
    private final Long2ObjectOpenHashMap<List<Slot>> slotsByChunk = new Long2ObjectOpenHashMap<>();
    private final LongOpenHashSet dirtyColumns = new LongOpenHashSet();
    private final BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
    private ClientLevel level;
    private ResourceLocation dimension;

    /** Forgets everything; the next tick starts over from the stored patches. */
    void reset() {
        tracked.clear();
        slotsByChunk.clear();
        dirtyColumns.clear();
        level = null;
        dimension = null;
    }

    /** A block amount arrived; its column is re-summed on the next tick. */
    void markColumn(ResourceLocation columnDimension, int blockX, int blockZ) {
        if (columnDimension.equals(dimension)) {
            dirtyColumns.add(OreMapPatch.columnKey(blockX, blockZ));
        }
    }

    /** The server just described this patch, so the loaded chunks it covers need no re-summing. */
    void discovered(OreMapPatch patch) {
        if (level == null || !patch.dimension().equals(dimension)) {
            return;
        }
        Tracked existing = tracked.get(patch.id());
        if (existing != null && !existing.center.equals(patch.center())) {
            untrack(existing);
            existing = null;
        }
        if (existing == null) {
            existing = track(patch);
        }
        for (Slot slot : existing.slots) {
            OreDepositChunkData data = loadedData(slot.chunkX, slot.chunkZ);
            if (data != null) {
                slot.seen = data.instanceId();
            }
        }
    }

    /** Applies this tick's changes to {@code patches}; true when any patch changed. */
    boolean tick(Map<Long, OreMapPatch> patches) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level != level) {
            reset();
            if (minecraft.level == null) {
                return false;
            }
            level = minecraft.level;
            dimension = level.dimension().location();
            for (OreMapPatch patch : patches.values()) {
                if (patch.dimension().equals(dimension)) {
                    track(patch);
                }
            }
        }
        LocalPlayer player = minecraft.player;
        if (tracked.isEmpty() || player == null) {
            dirtyColumns.clear();
            return false;
        }

        Map<Long, Long2ObjectOpenHashMap<int[]>> updates = new HashMap<>();
        resumReplacedSlices(
                player.chunkPosition(),
                minecraft.options.getEffectiveRenderDistance() + 2,
                patches,
                updates);
        // After the slices: a column sum overwrites, while a slice adds onto the columns it zeroed.
        resumDirtyColumns(updates);

        boolean changed = false;
        for (Map.Entry<Long, Long2ObjectOpenHashMap<int[]>> update : updates.entrySet()) {
            OreMapPatch patch = patches.get(update.getKey());
            if (patch == null) {
                continue;
            }
            OreMapPatch refreshed = patch.withColumns(update.getValue());
            if (refreshed != patch) {
                patches.put(update.getKey(), refreshed);
                changed = true;
            }
        }
        return changed;
    }

    /**
     * Only chunks near the player can be loaded, so only those are checked for a fresh ore sync.
     */
    private void resumReplacedSlices(
            ChunkPos center,
            int radius,
            Map<Long, OreMapPatch> patches,
            Map<Long, Long2ObjectOpenHashMap<int[]>> updates) {
        int budget = SLICE_BUDGET;
        for (int chunkX = center.x - radius; chunkX <= center.x + radius; chunkX++) {
            for (int chunkZ = center.z - radius; chunkZ <= center.z + radius; chunkZ++) {
                List<Slot> slots = slotsByChunk.get(ChunkPos.asLong(chunkX, chunkZ));
                if (slots == null) {
                    continue;
                }
                OreDepositChunkData data = loadedData(chunkX, chunkZ);
                if (data == null) {
                    continue;
                }
                for (Slot slot : slots) {
                    if (slot.seen == data.instanceId()) {
                        continue;
                    }
                    if (budget-- <= 0) {
                        return;
                    }
                    slot.seen = data.instanceId();
                    OreMapPatch patch = patches.get(slot.owner.id);
                    if (patch != null) {
                        resumSlice(
                                slot.owner,
                                patch,
                                chunkX,
                                chunkZ,
                                data,
                                columnsFor(updates, slot.owner.id));
                    }
                }
            }
        }
    }

    private static void resumSlice(
            Tracked owner,
            OreMapPatch patch,
            int chunkX,
            int chunkZ,
            OreDepositChunkData data,
            Long2ObjectOpenHashMap<int[]> columns) {
        int centerX = owner.center.getX();
        int centerZ = owner.center.getZ();
        // Known cells of this chunk start from zero, so a column whose blocks are gone leaves the
        // map.
        for (DiscoveredOreDeposit.OreMapCell cell : patch.deposit().cells()) {
            if (centerX + cell.offsetX() >> 4 == chunkX
                    && centerZ + cell.offsetZ() >> 4 == chunkZ) {
                columns.put(OreMapPatch.columnKey(cell.offsetX(), cell.offsetZ()), new int[2]);
            }
        }
        data.forEach(
                chunkX,
                chunkZ,
                (pos, entry) -> {
                    int dx = pos.getX() - centerX;
                    int dz = pos.getZ() - centerZ;
                    if (Math.abs(pos.getY() - owner.center.getY()) > VERTICAL_REACH
                            || (long) dx * dx + (long) dz * dz > RADIUS_SQR
                            || !owner.accepts(entry)) {
                        return;
                    }
                    int[] amounts =
                            columns.computeIfAbsent(
                                    OreMapPatch.columnKey(dx, dz), ignored -> new int[2]);
                    amounts[0] += Math.max(0, entry.remainingOre());
                    amounts[1] += Math.max(0, entry.initialOre());
                });
    }

    private void resumDirtyColumns(Map<Long, Long2ObjectOpenHashMap<int[]>> updates) {
        for (LongIterator iterator = dirtyColumns.iterator(); iterator.hasNext(); ) {
            long column = iterator.nextLong();
            int x = (int) (column >> 32);
            int z = (int) column;
            List<Slot> slots = slotsByChunk.get(ChunkPos.asLong(x >> 4, z >> 4));
            if (slots == null) {
                continue;
            }
            OreDepositChunkData data = loadedData(x >> 4, z >> 4);
            if (data == null) {
                continue;
            }
            for (Slot slot : slots) {
                Tracked owner = slot.owner;
                int dx = x - owner.center.getX();
                int dz = z - owner.center.getZ();
                if ((long) dx * dx + (long) dz * dz > RADIUS_SQR) {
                    continue;
                }
                int[] amounts = new int[2];
                int centerY = owner.center.getY();
                for (int y = centerY - VERTICAL_REACH; y <= centerY + VERTICAL_REACH; y++) {
                    OreDepositChunkData.Entry entry = data.get(cursor.set(x, y, z));
                    if (entry != null && owner.accepts(entry)) {
                        amounts[0] += Math.max(0, entry.remainingOre());
                        amounts[1] += Math.max(0, entry.initialOre());
                    }
                }
                columnsFor(updates, owner.id).put(OreMapPatch.columnKey(dx, dz), amounts);
            }
        }
        dirtyColumns.clear();
    }

    private static Long2ObjectOpenHashMap<int[]> columnsFor(
            Map<Long, Long2ObjectOpenHashMap<int[]>> updates, long id) {
        return updates.computeIfAbsent(id, ignored -> new Long2ObjectOpenHashMap<>());
    }

    /** The ore data of a chunk the client has loaded, or null; never loads or creates anything. */
    private OreDepositChunkData loadedData(int chunkX, int chunkZ) {
        LevelChunk chunk = level.getChunkSource().getChunk(chunkX, chunkZ, false);
        return chunk == null ? null : chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
    }

    private Tracked track(OreMapPatch patch) {
        Tracked owner = new Tracked(patch);
        tracked.put(owner.id, owner);
        int minChunkX = Math.floorDiv(owner.center.getX() - RADIUS, 16);
        int maxChunkX = Math.floorDiv(owner.center.getX() + RADIUS, 16);
        int minChunkZ = Math.floorDiv(owner.center.getZ() - RADIUS, 16);
        int maxChunkZ = Math.floorDiv(owner.center.getZ() + RADIUS, 16);
        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                Slot slot = new Slot(owner, chunkX, chunkZ);
                owner.slots.add(slot);
                slotsByChunk
                        .computeIfAbsent(
                                ChunkPos.asLong(chunkX, chunkZ), ignored -> new ArrayList<>(2))
                        .add(slot);
            }
        }
        return owner;
    }

    private void untrack(Tracked owner) {
        tracked.remove(owner.id);
        for (Slot slot : owner.slots) {
            long key = ChunkPos.asLong(slot.chunkX, slot.chunkZ);
            List<Slot> slots = slotsByChunk.get(key);
            if (slots != null && slots.remove(slot) && slots.isEmpty()) {
                slotsByChunk.remove(key);
            }
        }
    }

    /** What the server counts as part of one patch: its tier and any host variant of its ore. */
    private static final class Tracked {
        private final long id;
        private final BlockPos center;
        private final int tier;
        private final ResourceLocation oreId;
        private final String material;
        private final List<Slot> slots = new ArrayList<>();
        private boolean[] acceptedOres;
        private int paletteRevision = -1;

        private Tracked(OreMapPatch patch) {
            id = patch.id();
            center = patch.center();
            tier = patch.deposit().tier();
            oreId = patch.oreId();
            material = OreUnifier.materialKeyFor(oreId);
        }

        boolean accepts(OreDepositChunkData.Entry entry) {
            if (entry.tier() != tier) {
                return false;
            }
            int revision = OreDepositOrePalette.clientRevision();
            if (acceptedOres == null || revision != paletteRevision) {
                acceptedOres = new boolean[OreDepositOrePalette.MAX_ORES];
                for (int index = 0; index < acceptedOres.length; index++) {
                    Block ore = OreDepositOrePalette.oreAt(index);
                    ResourceLocation id = ore == null ? null : BuiltInRegistries.BLOCK.getKey(ore);
                    acceptedOres[index] =
                            id != null
                                    && (id.equals(oreId)
                                            || !material.isEmpty()
                                                    && material.equals(
                                                            OreUnifier.materialKeyFor(id)));
                }
                paletteRevision = revision;
            }
            int oreIndex = entry.oreIndex();
            return oreIndex >= 0 && oreIndex < acceptedOres.length && acceptedOres[oreIndex];
        }
    }

    /**
     * One patch's share of one chunk, remembering which copy of the chunk's ore data it last
     * summed.
     */
    private static final class Slot {
        private final Tracked owner;
        private final int chunkX;
        private final int chunkZ;
        private long seen = NEVER_SEEN;

        private Slot(Tracked owner, int chunkX, int chunkZ) {
            this.owner = owner;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
    }
}

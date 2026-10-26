package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.world.level.storage.FastSavedData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.nbt.StringTag;
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
import java.util.Set;
import java.util.HashSet;
import net.minecraft.world.level.ChunkPos;

/** Per-dimension SavedData index of deposits that were actually placed. */
public final class ConfirmedDepositIndex extends FastSavedData {
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_confirmed_deposits";
    private static final int CELL_SIZE = 1_024;
    private static final SavedData.Factory<ConfirmedDepositIndex> FACTORY =
            new SavedData.Factory<>(ConfirmedDepositIndex::new, ConfirmedDepositIndex::load);

    private final Map<Long, ConfirmedDeposit> byId = new LinkedHashMap<>();
    private final Map<Long, List<ConfirmedDeposit>> byCell = new HashMap<>();
    // Completion survives depletion and restart, so another decoration call cannot respawn mined ore.
    private final Map<Long, Set<Long>> compactSlices = new HashMap<>();
    // Transient, nonblocking ownership of current-chunk commits. Never hold this index's
    // monitor across world access: a setBlock callback can wait for neighbouring worldgen.
    private final Map<Long, Long> activeCompactChunks = new HashMap<>();
    // Only unfinished forced plans: necessary because their centers are chosen by the existing ledger.
    private final Map<Long, OreDepositFeature.CompactPlan> forcedPlans = new LinkedHashMap<>();
    private final Set<Long> startingForcedPlans = new HashSet<>();
    private final Map<Long,CompoundTag> serializedForcedPlans=new HashMap<>();
    private final Map<Long, Set<Long>> forcedByChunk = new HashMap<>();
    private long revision;

    private ConfirmedDepositIndex() {
    }

    public static synchronized ConfirmedDepositIndex get(ServerLevel level) {
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

    /** Record only blocks actually written by a chunk slice; later slices extend the same identity. */
    public synchronized boolean hasSlice(long depositId, ChunkPos chunk) {
        return hasSlice(depositId,chunk.x,chunk.z);
    }

    public synchronized boolean hasSlice(long depositId, int chunkX, int chunkZ) {
        Set<Long> slices = compactSlices.get(depositId);
        return slices != null && slices.contains(ChunkPos.asLong(chunkX,chunkZ));
    }

    synchronized boolean tryBeginSlice(long depositId, ChunkPos chunk) {
        if (hasSlice(depositId, chunk) || activeCompactChunks.containsKey(chunk.toLong())) return false;
        activeCompactChunks.put(chunk.toLong(), depositId);
        return true;
    }

    synchronized void endSlice(long depositId, ChunkPos chunk) {
        activeCompactChunks.remove(chunk.toLong(), depositId);
    }

    public synchronized void confirmSlice(DepositCandidate candidate, int placedBlocks, ChunkPos chunk, int occupiedSections) {
        if (placedBlocks < 1) return;
        if (!compactSlices.computeIfAbsent(candidate.depositId(), ignored -> new HashSet<>()).add(chunk.toLong())) return;
        startingForcedPlans.remove(candidate.depositId());
        ConfirmedDeposit previous = byId.get(candidate.depositId());
        add(new ConfirmedDeposit(candidate.depositId(), candidate.oreId(), candidate.tier(), candidate.center(),
                placedBlocks + (previous == null ? 0 : previous.placedBlocks()),
                Math.max(1,occupiedSections) + (previous == null ? 0 : previous.occupiedSections()), null, false));
        OreDepositFeature.CompactPlan plan=forcedPlans.get(candidate.depositId());
        if(plan!=null && compactSlices.get(candidate.depositId()).containsAll(plan.slices().keySet()))
            removeForcedPlan(candidate.depositId());
    }

    synchronized boolean registerForcedPlan(OreDepositFeature.CompactPlan plan) {
        return registerForcedPlan(plan,false);
    }

    synchronized boolean registerStartingForcedPlan(OreDepositFeature.CompactPlan plan) {
        return registerForcedPlan(plan,true);
    }

    private boolean registerForcedPlan(OreDepositFeature.CompactPlan plan,boolean starting) {
        long id=plan.candidate().depositId();
        if(forcedPlans.containsKey(id)||compactSlices.containsKey(id))return false;
        forcedPlans.put(id,plan);
        if(starting)startingForcedPlans.add(id);
        indexForcedPlan(plan);
        setDirty();return true;
    }

    synchronized boolean forcedPlanReady(long id) {
        return forcedPlans.containsKey(id) && !startingForcedPlans.contains(id);
    }

    private void indexForcedPlan(OreDepositFeature.CompactPlan plan) {
        for(long chunk:plan.slices().keySet()) {
            forcedByChunk.computeIfAbsent(chunk,ignored -> new HashSet<>()).add(plan.candidate().depositId());
        }
    }

    synchronized void removeForcedPlan(long id) {
        var plan=forcedPlans.remove(id);
        if(plan==null)return;
        startingForcedPlans.remove(id);
        serializedForcedPlans.remove(id);
        for(long chunk:plan.slices().keySet()) {
            Set<Long> ids=forcedByChunk.get(chunk);
            if(ids!=null) {ids.remove(id);if(ids.isEmpty())forcedByChunk.remove(chunk);}
        }
        setDirty();
    }

    synchronized List<OreDepositFeature.CompactPlan> forcedPlansFor(ChunkPos chunk) {
        Set<Long> ids=forcedByChunk.get(chunk.toLong());
        return ids==null?List.of():ids.stream().sorted().map(forcedPlans::get).toList();
    }

    synchronized List<OreDepositFeature.CompactPlan> forcedPlansNear(DepositCandidate candidate) {
        Set<Long> ids=new HashSet<>();int reach=CompactDepositGeometry.MAX_HORIZONTAL_REACH*2+1;
        for(int x=Math.floorDiv(candidate.center().getX()-reach,16);x<=Math.floorDiv(candidate.center().getX()+reach,16);x++)
            for(int z=Math.floorDiv(candidate.center().getZ()-reach,16);z<=Math.floorDiv(candidate.center().getZ()+reach,16);z++) {
                var chunks=forcedByChunk.get(ChunkPos.asLong(x,z));if(chunks!=null)ids.addAll(chunks);
            }
        return ids.stream().sorted().map(forcedPlans::get).toList();
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
            revision++;
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
        // Blocks take their host rock's ore variant (deepslate ore in deepslate) while a record keeps one
        // representative ore, so the filter matches by material.
        String filterMaterial = oreFilter == null ? "" : OreUnifier.materialKeyFor(oreFilter);
        List<ConfirmedDeposit> result = new ArrayList<>();
        for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
            for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                List<ConfirmedDeposit> records = byCell.get(cellKey(cellX, cellZ));
                if (records == null) {
                    continue;
                }
                for (ConfirmedDeposit record : records) {
                    if ((oreFilter == null || oreFilter.equals(record.oreId())
                            || !filterMaterial.isEmpty()
                            && filterMaterial.equals(OreUnifier.materialKeyFor(record.oreId())))
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

    /**
     * Keeps the authoritative record alive until its final tracked block is removed. Only a record of the
     * block's own tier may be decremented: unrecorded (vanilla-like) small deposits must not drain a
     * neighbouring record of the same ore.
     */
    public synchronized void markBlockDepleted(BlockPos pos, ResourceLocation oreId, int tier) {
        int radius = OreDepositFeature.MAX_DEPOSIT_FOOTPRINT_DIAMETER;
        ConfirmedDeposit nearest = null;
        double nearestDistance = Double.MAX_VALUE;
        for (ConfirmedDeposit candidate : recordsWithin(pos, radius, oreId)) {
            if (candidate.tier().index() != tier) {
                continue;
            }
            int verticalReach = candidate.tier().index() >= OreDepositFeature.TIER_MEDIUM
                    ? CompactDepositGeometry.MAX_VERTICAL_REACH : OreDepositFeature.MAX_UNDERGROUND_DEPTH;
            if (Math.abs(candidate.center().getY() - pos.getY()) > verticalReach) {
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
        revision++;
        setDirty();
    }

    /** Monotonic in-memory version used to keep locate-result caches coherent with mining/worldgen. */
    public synchronized long revision() {
        return revision;
    }

    static ConfirmedDepositIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        if(!tag.isEmpty() && tag.getInt("Format")!=1)throw new IllegalArgumentException("Unsupported deposit index format; a new world is required");
        ConfirmedDepositIndex index = new ConfirmedDepositIndex();
        ListTag pending=tag.getList("ForcedCompactPlans",Tag.TAG_COMPOUND);
        for(int i=0;i<pending.size();i++) {
            try {
                var plan=OreDepositFeature.loadCompactPlan(pending.getCompound(i),registries);
                index.forcedPlans.put(plan.candidate().depositId(),plan);index.indexForcedPlan(plan);
            } catch(RuntimeException invalid) {
                OresAndDrillsMod.LOGGER.warn("Invalid saved forced ore plan: {}",invalid.getMessage());
            }
        }
        if(tag.contains("SliceIds",Tag.TAG_LONG_ARRAY)) {
            long[] sliceIds=tag.getLongArray("SliceIds"),sliceChunks=tag.getLongArray("SliceChunks");
            int[] sliceCounts=tag.getIntArray("SliceCounts");
            if(sliceCounts.length!=sliceIds.length)throw new IllegalArgumentException("Invalid packed compact slices");
            int offset=0;
            for(int i=0;i<sliceIds.length;i++) {
                if(sliceCounts[i]<0||sliceCounts[i]>sliceChunks.length-offset)throw new IllegalArgumentException("Invalid packed compact slices");
                Set<Long> chunks=new HashSet<>(Math.max(4,sliceCounts[i]*2));
                for(int end=offset+sliceCounts[i];offset<end;offset++)chunks.add(sliceChunks[offset]);
                index.compactSlices.put(sliceIds[i],chunks);
            }
        } else {
            // Earlier Format=1 saves wrote one compound per deposit; read them, the next save repacks them.
            ListTag completed = tag.getList("CompactSlices", Tag.TAG_COMPOUND);
            for (int i = 0; i < completed.size(); i++) {
                CompoundTag record = completed.getCompound(i);
                Set<Long> chunks = new HashSet<>();
                for (long key : record.getLongArray("Chunks")) chunks.add(key);
                index.compactSlices.put(record.getLong("Id"), chunks);
            }
        }
        if(tag.contains("DepositIds",Tag.TAG_LONG_ARRAY)) {
            long[] ids=tag.getLongArray("DepositIds"),centers=tag.getLongArray("DepositCenters");
            int[] counts=tag.getIntArray("DepositCounts"),kinds=tag.getIntArray("DepositKinds");
            ListTag ores=tag.getList("DepositOres",Tag.TAG_STRING);
            if(centers.length!=ids.length||kinds.length!=ids.length||counts.length!=(long)ids.length*2)
                throw new IllegalArgumentException("Invalid packed deposit index");
            for(int i=0;i<ids.length;i++) {
                int ore=kinds[i]>>>6,wall=(kinds[i]>>>2)&7;
                ResourceLocation oreId=ore<ores.size()?ResourceLocation.tryParse(ores.getString(ore)):null;
                OreDepositTier tier=OreDepositTier.fromIndex(kinds[i]&3).orElse(null);
                Direction direction=wall==0?null:Direction.from3DDataValue(wall-1);
                boolean exposed=(kinds[i]&32)!=0;
                if(oreId==null||tier==null||counts[i*2]<1||direction!=null&&(direction.getAxis().isVertical()||!exposed))continue;
                ConfirmedDeposit deposit=new ConfirmedDeposit(ids[i],oreId,tier,BlockPos.of(centers[i]),
                        counts[i*2],Math.max(1,counts[i*2+1]),direction,exposed);
                ConfirmedDeposit previous=index.byId.put(ids[i],deposit);
                if(previous!=null)index.removeFromCell(previous);
                index.byCell.computeIfAbsent(cellKey(deposit.center()),ignored -> new ArrayList<>()).add(deposit);
            }
        }
        return index;
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        int size=byId.size(),i=0;
        long[] ids=new long[size],centers=new long[size];int[] counts=new int[size*2],kinds=new int[size];
        ListTag ores=new ListTag();Map<ResourceLocation,Integer> oreIndices=new HashMap<>();
        for (ConfirmedDeposit deposit : byId.values()) {
            int ore=oreIndices.computeIfAbsent(deposit.oreId(),id -> {int value=ores.size();ores.add(StringTag.valueOf(id.toString()));return value;});
            ids[i]=deposit.depositId();centers[i]=deposit.center().asLong();
            counts[i*2]=deposit.placedBlocks();counts[i*2+1]=deposit.occupiedSections();
            kinds[i]=deposit.tier().index()|(deposit.wallDirection()==null?0:(deposit.wallDirection().get3DDataValue()+1)<<2)
                    |(deposit.exposedToCave()?32:0)|(ore<<6);
            i++;
        }
        tag.putInt("Format",1);
        tag.putLongArray("DepositIds",ids);tag.putLongArray("DepositCenters",centers);
        tag.putIntArray("DepositCounts",counts);tag.putIntArray("DepositKinds",kinds);tag.put("DepositOres",ores);
        // Completed slices of every MEDIUM/LARGE deposit only grow, so a large world holds many of them:
        // three flat arrays instead of one compound per deposit keep both the build and the gzip pass short.
        int sliceTotal=0;
        for(Set<Long> chunks:compactSlices.values())sliceTotal+=chunks.size();
        long[] sliceIds=new long[compactSlices.size()],sliceChunks=new long[sliceTotal];int[] sliceCounts=new int[sliceIds.length];
        int slice=0,offset=0;
        for(Map.Entry<Long,Set<Long>> entry:compactSlices.entrySet()) {
            sliceIds[slice]=entry.getKey();sliceCounts[slice++]=entry.getValue().size();
            for(long chunk:entry.getValue())sliceChunks[offset++]=chunk;
        }
        tag.putLongArray("SliceIds",sliceIds);tag.putIntArray("SliceCounts",sliceCounts);tag.putLongArray("SliceChunks",sliceChunks);
        ListTag pending=new ListTag();
        // An initial attempt is visible to gravel protection/arbitration, but cannot be
        // continued or saved before its first slice succeeds and earns the forced slot.
        forcedPlans.values().stream().filter(plan -> !startingForcedPlans.contains(plan.candidate().depositId()))
                .forEach(plan -> pending.add(serializedForcedPlans.computeIfAbsent(plan.candidate().depositId(),
                ignored -> OreDepositFeature.saveCompactPlan(plan,registries)).copy()));
        tag.put("ForcedCompactPlans",pending);
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

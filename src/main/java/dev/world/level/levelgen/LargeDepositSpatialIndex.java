package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.saveddata.SavedData;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicInteger;

/** Persistent spatial hash shared by MEDIUM and LARGE deposits in one dimension. */
public final class LargeDepositSpatialIndex extends SavedData {
    private static final String FILE_ID = OresAndDrillsMod.MOD_ID + "_large_deposit_index";
    private static final String TAG_DEPOSITS = "Deposits";
    private static final String TAG_DATA_VERSION = "DataVersion";
    private static final String TAG_MATERIALS = "Materials";
    private static final String TAG_LARGE_X = "LargeX";
    private static final String TAG_LARGE_Y = "LargeY";
    private static final String TAG_LARGE_Z = "LargeZ";
    private static final String TAG_LARGE_TIER_MATERIAL = "LargeTierMaterial";
    private static final String TAG_LARGE_SCALE = "LargeScale";
    private static final String TAG_LARGE_MIN_X = "LargeMinX";
    private static final String TAG_LARGE_MAX_X = "LargeMaxX";
    private static final String TAG_LARGE_MIN_Z = "LargeMinZ";
    private static final String TAG_LARGE_MAX_Z = "LargeMaxZ";
    private static final String TAG_LARGE_REMAINING_BLOCKS = "LargeRemainingBlocks";
    /**
     * Version 3 replaced the one-CompoundTag-per-record list (which duplicated the material string and an
     * always-zero Scale field on every TINY/SMALL entry) with packed primitive arrays plus a deduplicated
     * material pool. On worlds explored for a long time this list only ever grows (TINY/SMALL records are
     * never pruned automatically), so the old format made every autosave/quit re-serialize a huge number of
     * individually-allocated NBT compounds. Loading still understands the old format so existing worlds
     * migrate in place on the next save.
     *
     * Version 4 stops persisting TINY/SMALL locate hints. They do not participate in spacing and the
     * command already has a bounded deterministic predictor for them. Keeping every such hint forever
     * made this file grow with every explored chunk, so its complete re-serialization dominated Save and
     * Quit in long-lived worlds.
     */
    /** Version 5 adds a tracked footprint so a depleted deposit releases its spacing reservation exactly. */
    private static final int CURRENT_DATA_VERSION = 5;
    private static final int TIER_BITS = 4;
    private static final int TIER_MASK = (1 << TIER_BITS) - 1;
    private static final int CELL_SIZE = OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING_LIMIT;
    private static final int MAX_RECORD_CENTER_TO_FOOTPRINT_DISTANCE = OreDepositFeature.MAX_DEPOSIT_FOOTPRINT_DIAMETER;
    private static final int MAX_DEBUG_SPACING_LOGS = 256;
    private static final AtomicInteger DEBUG_SPACING_LOGS = new AtomicInteger();
    private static final SavedData.Factory<LargeDepositSpatialIndex> FACTORY =
            new SavedData.Factory<>(LargeDepositSpatialIndex::new, LargeDepositSpatialIndex::load);

    /** Only MEDIUM/LARGE records live here, so spacing checks never walk TINY/SMALL locate data. */
    private final Map<Long, List<DepositRecord>> largeDepositsByRegion = new HashMap<>();
    private LargeDepositSpatialIndex() {
    }

    public static LargeDepositSpatialIndex get(ServerLevel level) {
        return level.getDataStorage().computeIfAbsent(FACTORY, FILE_ID);
    }

    /** Atomically checks all nearby MEDIUM/LARGE records and reserves the accepted center. */
    public synchronized boolean tryAdd(
            BlockPos center,
            int tier,
            double normalizedScale,
            String materialKey,
            List<OreDepositFeature.DepositPosition> positions,
            RandomSource random
    ) {
        int cellX = Math.floorDiv(center.getX(), CELL_SIZE);
        int cellZ = Math.floorDiv(center.getZ(), CELL_SIZE);
        int minimumSpacing = minimumSpacing();
        int maximumSpacing = maximumSpacing(minimumSpacing);

        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                List<DepositRecord> records = largeDepositsByRegion.get(regionKey(cellX + offsetX, cellZ + offsetZ));
                if (records == null) {
                    continue;
                }
                for (DepositRecord nearby : records) {
                    if (nearby.tier() < OreDepositFeature.TIER_MEDIUM) {
                        continue;
                    }
                    DepositSpacingMath.SpacingBounds pairBounds = DepositSpacingMath.tierPairBounds(
                            tier, nearby.tier(), OreDepositFeature.TIER_MEDIUM, OreDepositFeature.TIER_LARGE,
                            minimumSpacing, maximumSpacing
                    );
                    DepositSpacingMath.SpacingCalculation spacing = DepositSpacingMath.requiredDistance(
                            normalizedScale,
                            nearby.normalizedScale(),
                            pairBounds.minimum(),
                            pairBounds.maximum(),
                            OreDepositConfig.LARGE_DEPOSIT_SPACING_CURVE,
                            OreDepositConfig.LARGE_DEPOSIT_SPACING_VARIATION,
                            random.nextDouble() * 2.0D - 1.0D
                    );
                    double actualDistance = horizontalDistance(center, nearby.center());
                    boolean accepted = actualDistance >= spacing.requiredDistance();
                    logSpacing(tier, normalizedScale, nearby, spacing.combinedScale(),
                            spacing.requiredDistance(), actualDistance, accepted);
                    if (!accepted) {
                        return false;
                    }
                }
            }
        }

        DepositFootprint footprint = DepositFootprint.from(positions);
        if (footprint == null) {
            return false;
        }
        largeDepositsByRegion.computeIfAbsent(regionKey(cellX, cellZ), ignored -> new ArrayList<>())
                .add(new DepositRecord(
                        center.immutable(), tier, Mth.clamp(normalizedScale, 0.0D, 1.0D), materialKey,
                        footprint.minX(), footprint.maxX(), footprint.minZ(), footprint.maxZ(), footprint.blockCount()
                ));
        setDirty();
        return true;
    }

    /**
     * Called when a physical MEDIUM/LARGE deposit block disappears. New records carry their exact footprint
     * and block count, so this is an identity check rather than a proximity heuristic. Old records remain
     * conservative reservations because their footprint was never persisted.
     */
    public synchronized void markBlockDepleted(BlockPos pos, int tier, String materialKey) {
        if (tier < OreDepositFeature.TIER_MEDIUM || materialKey == null || materialKey.isEmpty()) {
            return;
        }

        List<DepositRecord> matches = recordsWithin(
                pos, MAX_RECORD_CENTER_TO_FOOTPRINT_DISTANCE, tier, materialKey
        );
        for (DepositRecord record : matches) {
            if (!record.isTracked() || !record.contains(pos)) {
                continue;
            }
            remove(record);
            if (record.remainingBlocks() > 1) {
                addRecord(record.withRemainingBlocks(record.remainingBlocks() - 1));
            }
            return;
        }
    }

    /**
     * Pre-v5 worlds have no persisted footprint/count. A /locate that already loaded every possible
     * footprint chunk can still safely drop a record when none of those chunks contains a live matching
     * deposit. Missing chunks deliberately keep the reservation: guessing would allow overlapping veins.
     */
    public synchronized boolean pruneLegacyRecordIfExhausted(ServerLevel level, DepositRecord record) {
        if (record.isTracked()) {
            return false;
        }
        List<net.minecraft.resources.ResourceLocation> ores = OreDepositPaletteData.get(level).ores();
        int chunkRadius = (MAX_RECORD_CENTER_TO_FOOTPRINT_DISTANCE + 15) / 16;
        int centerChunkX = record.center().getX() >> 4;
        int centerChunkZ = record.center().getZ() >> 4;
        for (int offsetX = -chunkRadius; offsetX <= chunkRadius; offsetX++) {
            for (int offsetZ = -chunkRadius; offsetZ <= chunkRadius; offsetZ++) {
                ChunkAccess chunk = level.getChunkSource().getChunk(
                        centerChunkX + offsetX, centerChunkZ + offsetZ, ChunkStatus.FULL, false
                );
                if (chunk == null) {
                    return false;
                }
                OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
                if (data == null) {
                    continue;
                }
                boolean[] liveMatch = {false};
                data.forEach(centerChunkX + offsetX, centerChunkZ + offsetZ, (pos, entry) -> {
                    if (entry.tier() != record.tier() || entry.remainingOre() <= 0
                            || !chunk.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
                        return;
                    }
                    if (entry.oreIndex() >= 0 && entry.oreIndex() < ores.size()) {
                        var ore = net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(ores.get(entry.oreIndex()));
                        liveMatch[0] |= record.materialKey().equals(OreUnifier.materialKeyFor(ore));
                    }
                });
                if (liveMatch[0]) {
                    return false;
                }
            }
        }
        return remove(record);
    }

    private void addRecord(DepositRecord record) {
        int cellX = Math.floorDiv(record.center().getX(), CELL_SIZE);
        int cellZ = Math.floorDiv(record.center().getZ(), CELL_SIZE);
        largeDepositsByRegion.computeIfAbsent(regionKey(cellX, cellZ), ignored -> new ArrayList<>()).add(record);
        setDirty();
    }

    /**
     * TINY/SMALL deposits are found through the command's bounded predictor rather than retained in
     * SavedData forever. Kept as a no-op bridge so worlds/mod integrations compiled against older builds
     * continue to load cleanly.
     */
    public synchronized void recordUnrestricted(
            BlockPos center,
            int tier,
            String materialKey
    ) {
        // Intentionally not persisted: this was an unbounded /locate-only cache.
    }

    /** Removes a persisted locate record after its center and complete footprint were verified as depleted. */
    public synchronized boolean remove(DepositRecord record) {
        if (record.tier() < OreDepositFeature.TIER_MEDIUM) {
            return false;
        }
        Map<Long, List<DepositRecord>> recordsByRegion = largeDepositsByRegion;
        int cellX = Math.floorDiv(record.center().getX(), CELL_SIZE);
        int cellZ = Math.floorDiv(record.center().getZ(), CELL_SIZE);
        long key = regionKey(cellX, cellZ);
        List<DepositRecord> records = recordsByRegion.get(key);
        if (records == null || !records.remove(record)) {
            return false;
        }
        if (records.isEmpty()) {
            recordsByRegion.remove(key);
        }
        setDirty();
        return true;
    }

    /** Conservative early-out used before any cave blocks are scanned. */
    public synchronized boolean canPossiblyFit(BlockPos center, int tier, double relocationAllowance) {
        int cellX = Math.floorDiv(center.getX(), CELL_SIZE);
        int cellZ = Math.floorDiv(center.getZ(), CELL_SIZE);
        int minimumSpacing = minimumSpacing();
        int maximumSpacing = maximumSpacing(minimumSpacing);

        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                List<DepositRecord> records = largeDepositsByRegion.get(regionKey(cellX + offsetX, cellZ + offsetZ));
                if (records == null) {
                    continue;
                }
                for (DepositRecord nearby : records) {
                    if (nearby.tier() < OreDepositFeature.TIER_MEDIUM) {
                        continue;
                    }
                    DepositSpacingMath.SpacingBounds pairBounds = DepositSpacingMath.tierPairBounds(
                            tier, nearby.tier(), OreDepositFeature.TIER_MEDIUM, OreDepositFeature.TIER_LARGE,
                            minimumSpacing, maximumSpacing
                    );
                    // New scale 0 and minimum random variation form the smallest legal distance this pair
                    // could ever request. If even that cannot fit after center relocation, rejection is exact.
                    double minimumRequired = DepositSpacingMath.requiredDistance(
                            0.0D,
                            nearby.normalizedScale(),
                            pairBounds.minimum(),
                            pairBounds.maximum(),
                            OreDepositConfig.LARGE_DEPOSIT_SPACING_CURVE,
                            OreDepositConfig.LARGE_DEPOSIT_SPACING_VARIATION,
                            -1.0D
                    ).requiredDistance();
                    if (horizontalDistance(center, nearby.center()) + relocationAllowance < minimumRequired) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Returns indexed centers near the query, ordered nearest first for /locate. */
    public synchronized List<DepositRecord> recordsWithin(BlockPos origin, double radius, int tierFilter) {
        return recordsWithin(origin, radius, tierFilter, null);
    }

    /** Material-filtered lookup avoids loading every unrelated large-deposit chunk during /locate. */
    public synchronized List<DepositRecord> recordsWithin(
            BlockPos origin,
            double radius,
            int tierFilter,
            String materialKey
    ) {
        int minimumCellX = Math.floorDiv(Mth.floor(origin.getX() - radius), CELL_SIZE);
        int maximumCellX = Math.floorDiv(Mth.floor(origin.getX() + radius), CELL_SIZE);
        int minimumCellZ = Math.floorDiv(Mth.floor(origin.getZ() - radius), CELL_SIZE);
        int maximumCellZ = Math.floorDiv(Mth.floor(origin.getZ() + radius), CELL_SIZE);
        double radiusSqr = radius * radius;
        List<DepositRecord> result = new ArrayList<>();
        boolean includeLarge = tierFilter < 0 || tierFilter >= OreDepositFeature.TIER_MEDIUM;
        for (int cellX = minimumCellX; cellX <= maximumCellX; cellX++) {
            for (int cellZ = minimumCellZ; cellZ <= maximumCellZ; cellZ++) {
                long regionKey = regionKey(cellX, cellZ);
                if (includeLarge) {
                    addMatchingRecords(largeDepositsByRegion.get(regionKey), origin, radiusSqr,
                            tierFilter, materialKey, result);
                }
            }
        }
        result.sort(Comparator.comparingDouble(record -> horizontalDistanceSqr(origin, record.center())));
        return List.copyOf(result);
    }

    private static void addMatchingRecords(
            List<DepositRecord> records,
            BlockPos origin,
            double radiusSqr,
            int tierFilter,
            String materialKey,
            List<DepositRecord> result
    ) {
        if (records == null) {
            return;
        }
        for (DepositRecord record : records) {
            if ((tierFilter < 0 || record.tier() == tierFilter)
                    && (materialKey == null || materialKey.equals(record.materialKey()))
                    && horizontalDistanceSqr(origin, record.center()) <= radiusSqr) {
                result.add(record);
            }
        }
    }

    private static LargeDepositSpatialIndex load(CompoundTag tag, HolderLookup.Provider registries) {
        LargeDepositSpatialIndex data = new LargeDepositSpatialIndex();
        int version = tag.getInt(TAG_DATA_VERSION);
        if (version >= 3) {
            loadCompact(data, tag);
        } else {
            loadLegacy(data, tag, version);
        }
        // Version 4 deliberately drops the old unbounded TINY/SMALL /locate cache. Mark an existing
        // version-3 file dirty immediately so its next normal save compacts it even if no new large
        // deposit happens to generate before the player exits.
        if (version < CURRENT_DATA_VERSION) {
            data.setDirty();
        }
        return data;
    }

    private static void loadCompact(LargeDepositSpatialIndex data, CompoundTag tag) {
        ListTag materialsTag = tag.getList(TAG_MATERIALS, Tag.TAG_STRING);
        List<String> materials = new ArrayList<>(materialsTag.size());
        for (int index = 0; index < materialsTag.size(); index++) {
            materials.add(materialsTag.getString(index));
        }

        boolean hasTrackedFootprints = tag.contains(TAG_LARGE_REMAINING_BLOCKS, Tag.TAG_INT_ARRAY);
        loadCompactRecords(data.largeDepositsByRegion, materials,
                tag.getIntArray(TAG_LARGE_X), tag.getIntArray(TAG_LARGE_Y), tag.getIntArray(TAG_LARGE_Z),
                tag.getIntArray(TAG_LARGE_TIER_MATERIAL), tag.getLongArray(TAG_LARGE_SCALE),
                hasTrackedFootprints ? tag.getIntArray(TAG_LARGE_MIN_X) : null,
                hasTrackedFootprints ? tag.getIntArray(TAG_LARGE_MAX_X) : null,
                hasTrackedFootprints ? tag.getIntArray(TAG_LARGE_MIN_Z) : null,
                hasTrackedFootprints ? tag.getIntArray(TAG_LARGE_MAX_Z) : null,
                hasTrackedFootprints ? tag.getIntArray(TAG_LARGE_REMAINING_BLOCKS) : null);
    }

    private static void loadCompactRecords(
            Map<Long, List<DepositRecord>> target,
            List<String> materials,
            int[] xs,
            int[] ys,
            int[] zs,
            int[] tierMaterials,
            @Nullable long[] scaleBits,
            @Nullable int[] minXs,
            @Nullable int[] maxXs,
            @Nullable int[] minZs,
            @Nullable int[] maxZs,
            @Nullable int[] remainingBlocks
    ) {
        int size = Math.min(xs.length, Math.min(ys.length, Math.min(zs.length, tierMaterials.length)));
        for (int index = 0; index < size; index++) {
            int packed = tierMaterials[index];
            int tier = packed & TIER_MASK;
            int materialIndex = packed >>> TIER_BITS;
            if (tier < 0 || tier >= OreDepositFeature.TIER_COUNT || materialIndex < 0 || materialIndex >= materials.size()) {
                continue;
            }
            BlockPos center = new BlockPos(xs[index], ys[index], zs[index]);
            double scale = scaleBits != null && index < scaleBits.length
                    ? Mth.clamp(Double.longBitsToDouble(scaleBits[index]), 0.0D, 1.0D)
                    : 0.0D;
            int remaining = remainingBlocks != null && index < remainingBlocks.length
                    ? Math.max(0, remainingBlocks[index])
                    : DepositRecord.UNKNOWN_REMAINING_BLOCKS;
            int minX = minXs != null && index < minXs.length ? minXs[index] : center.getX();
            int maxX = maxXs != null && index < maxXs.length ? maxXs[index] : center.getX();
            int minZ = minZs != null && index < minZs.length ? minZs[index] : center.getZ();
            int maxZ = maxZs != null && index < maxZs.length ? maxZs[index] : center.getZ();
            int cellX = Math.floorDiv(center.getX(), CELL_SIZE);
            int cellZ = Math.floorDiv(center.getZ(), CELL_SIZE);
            target.computeIfAbsent(regionKey(cellX, cellZ), ignored -> new ArrayList<>())
                    .add(new DepositRecord(center, tier, scale, materials.get(materialIndex), minX, maxX, minZ, maxZ, remaining));
        }
    }

    /** Reads the pre-3 one-CompoundTag-per-record format so existing worlds migrate on their next save. */
    private static void loadLegacy(LargeDepositSpatialIndex data, CompoundTag tag, int version) {
        boolean migrateLegacySpacingScale = version < 2;
        ListTag records = tag.getList(TAG_DEPOSITS, Tag.TAG_COMPOUND);
        for (int index = 0; index < records.size(); index++) {
            CompoundTag recordTag = records.getCompound(index);
            int tier = recordTag.getInt("Tier");
            double scale = recordTag.getDouble("Scale");
            String materialKey = recordTag.getString("Material");
            if (tier < 0 || tier >= OreDepositFeature.TIER_COUNT || !Double.isFinite(scale)) {
                continue;
            }
            BlockPos center = new BlockPos(recordTag.getInt("X"), recordTag.getInt("Y"), recordTag.getInt("Z"));
            int cellX = Math.floorDiv(center.getX(), CELL_SIZE);
            int cellZ = Math.floorDiv(center.getZ(), CELL_SIZE);
            if (tier < OreDepositFeature.TIER_MEDIUM) {
                continue;
            }
            Map<Long, List<DepositRecord>> target = data.largeDepositsByRegion;
            double persistedScale = migrateLegacySpacingScale
                    ? migrateLegacySpacingScale()
                    : Mth.clamp(scale, 0.0D, 1.0D);
            target.computeIfAbsent(regionKey(cellX, cellZ), ignored -> new ArrayList<>())
                    .add(DepositRecord.untracked(center, tier, persistedScale, materialKey));
        }
        // Always resave in the compact format, regardless of whether the scale itself needed migrating.
        data.setDirty();
    }

    @Override
    public synchronized CompoundTag save(CompoundTag tag, HolderLookup.Provider registries) {
        Map<String, Integer> materialIndices = new HashMap<>();
        List<String> materials = new ArrayList<>();

        RecordArrays largeArrays = packRecords(largeDepositsByRegion, materialIndices, materials, true);
        ListTag materialsTag = new ListTag();
        for (String material : materials) {
            materialsTag.add(StringTag.valueOf(material));
        }

        tag.putInt(TAG_DATA_VERSION, CURRENT_DATA_VERSION);
        tag.put(TAG_MATERIALS, materialsTag);
        tag.putIntArray(TAG_LARGE_X, largeArrays.x());
        tag.putIntArray(TAG_LARGE_Y, largeArrays.y());
        tag.putIntArray(TAG_LARGE_Z, largeArrays.z());
        tag.putIntArray(TAG_LARGE_TIER_MATERIAL, largeArrays.tierMaterial());
        tag.putLongArray(TAG_LARGE_SCALE, largeArrays.scaleBits());
        tag.putIntArray(TAG_LARGE_MIN_X, largeArrays.minX());
        tag.putIntArray(TAG_LARGE_MAX_X, largeArrays.maxX());
        tag.putIntArray(TAG_LARGE_MIN_Z, largeArrays.minZ());
        tag.putIntArray(TAG_LARGE_MAX_Z, largeArrays.maxZ());
        tag.putIntArray(TAG_LARGE_REMAINING_BLOCKS, largeArrays.remainingBlocks());
        return tag;
    }

    /**
     * Version 1 stored a scale calculated from block count and reserve. Neutralize those records when
     * loading so newly generated deposits do not inherit slider-dependent spacing from old neighbours.
     */
    private static double migrateLegacySpacingScale() {
        return 0.5D;
    }

    private static RecordArrays packRecords(
            Map<Long, List<DepositRecord>> recordsByRegion,
            Map<String, Integer> materialIndices,
            List<String> materials,
            boolean includeScale
    ) {
        int size = 0;
        for (List<DepositRecord> regionRecords : recordsByRegion.values()) {
            size += regionRecords.size();
        }
        int[] xs = new int[size];
        int[] ys = new int[size];
        int[] zs = new int[size];
        int[] tierMaterials = new int[size];
        long[] scaleBits = includeScale ? new long[size] : new long[0];
        int[] minXs = new int[size];
        int[] maxXs = new int[size];
        int[] minZs = new int[size];
        int[] maxZs = new int[size];
        int[] remainingBlocks = new int[size];
        int index = 0;
        for (List<DepositRecord> regionRecords : recordsByRegion.values()) {
            for (DepositRecord record : regionRecords) {
                int materialIndex = materialIndices.computeIfAbsent(record.materialKey(), key -> {
                    materials.add(key);
                    return materials.size() - 1;
                });
                xs[index] = record.center().getX();
                ys[index] = record.center().getY();
                zs[index] = record.center().getZ();
                tierMaterials[index] = (materialIndex << TIER_BITS) | (record.tier() & TIER_MASK);
                if (includeScale) {
                    scaleBits[index] = Double.doubleToLongBits(record.normalizedScale());
                }
                minXs[index] = record.minX();
                maxXs[index] = record.maxX();
                minZs[index] = record.minZ();
                maxZs[index] = record.maxZ();
                remainingBlocks[index] = record.remainingBlocks();
                index++;
            }
        }
        return new RecordArrays(xs, ys, zs, tierMaterials, scaleBits, minXs, maxXs, minZs, maxZs, remainingBlocks);
    }

    private record RecordArrays(
            int[] x, int[] y, int[] z, int[] tierMaterial, long[] scaleBits,
            int[] minX, int[] maxX, int[] minZ, int[] maxZ, int[] remainingBlocks
    ) {
    }

    private static long regionKey(int x, int z) {
        return ((long)x << 32) ^ (z & 0xFFFFFFFFL);
    }

    private static int minimumSpacing() {
        return Mth.clamp(
                OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING.get(),
                OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING_LIMIT,
                OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING_LIMIT
        );
    }

    private static int maximumSpacing(int minimumSpacing) {
        return Mth.clamp(
                OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING.get(),
                minimumSpacing,
                OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING_LIMIT
        );
    }

    private static double horizontalDistance(BlockPos first, BlockPos second) {
        return Math.sqrt(horizontalDistanceSqr(first, second));
    }

    private static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        long dx = (long)first.getX() - second.getX();
        long dz = (long)first.getZ() - second.getZ();
        return (double)dx * dx + (double)dz * dz;
    }

    private static String tierName(int tier) {
        return DepositTier.byIndex(tier).name();
    }

    private static String format(double value) {
        return String.format(java.util.Locale.ROOT, "%.3f", value);
    }

    private static void logSpacing(
            int tier,
            double normalizedScale,
            DepositRecord nearby,
            double combinedScale,
            double requiredDistance,
            double actualDistance,
            boolean accepted
    ) {
        if (!OresAndDrillsMod.LOGGER.isDebugEnabled()) {
            return;
        }
        int logIndex = DEBUG_SPACING_LOGS.getAndIncrement();
        if (logIndex >= MAX_DEBUG_SPACING_LOGS) {
            if (logIndex == MAX_DEBUG_SPACING_LOGS) {
                OresAndDrillsMod.LOGGER.debug("Ore deposit spacing: further diagnostics suppressed for this session");
            }
            return;
        }
        OresAndDrillsMod.LOGGER.debug(
                "Ore deposit spacing: new tier={} scale={} nearby tier={} scale={} combined={} required={} actual={} result={}",
                tierName(tier), format(normalizedScale), tierName(nearby.tier()),
                format(nearby.normalizedScale()), format(combinedScale), format(requiredDistance),
                format(actualDistance), accepted ? "accepted" : "rejected"
        );
    }

    public record DepositRecord(
            BlockPos center,
            int tier,
            double normalizedScale,
            String materialKey,
            int minX,
            int maxX,
            int minZ,
            int maxZ,
            int remainingBlocks
    ) {
        static final int UNKNOWN_REMAINING_BLOCKS = -1;

        static DepositRecord untracked(BlockPos center, int tier, double normalizedScale, String materialKey) {
            return new DepositRecord(
                    center, tier, normalizedScale, materialKey,
                    center.getX(), center.getX(), center.getZ(), center.getZ(), UNKNOWN_REMAINING_BLOCKS
            );
        }

        public boolean isTracked() {
            return remainingBlocks > 0;
        }

        public boolean contains(BlockPos pos) {
            return pos.getX() >= minX && pos.getX() <= maxX
                    && pos.getZ() >= minZ && pos.getZ() <= maxZ;
        }

        DepositRecord withRemainingBlocks(int remaining) {
            return new DepositRecord(center, tier, normalizedScale, materialKey, minX, maxX, minZ, maxZ, remaining);
        }
    }

    private record DepositFootprint(int minX, int maxX, int minZ, int maxZ, int blockCount) {
        @Nullable
        private static DepositFootprint from(List<OreDepositFeature.DepositPosition> positions) {
            if (positions.isEmpty()) {
                return null;
            }
            int minX = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE;
            int maxZ = Integer.MIN_VALUE;
            for (OreDepositFeature.DepositPosition position : positions) {
                BlockPos pos = position.pos();
                minX = Math.min(minX, pos.getX());
                maxX = Math.max(maxX, pos.getX());
                minZ = Math.min(minZ, pos.getZ());
                maxZ = Math.max(maxZ, pos.getZ());
            }
            return new DepositFootprint(minX, maxX, minZ, maxZ, positions.size());
        }
    }
}

package dev.world.level.levelgen;

import dev.network.OreDepositRemainingPayload;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import dev.world.block.entity.deposit.OreDepositDrops;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import it.unimi.dsi.fastutil.longs.LongSets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.neoforged.neoforge.network.PacketDistributor;

public final class OreDepositData {
    public static final int MINIMUM_ORE_AMOUNT = 1;
    public static final int MAXIMUM_ORE_AMOUNT = 5_000;
    public static final int FILL_STAGE_COUNT = 4;
    private static final int MIN_RICHNESS = 0;
    private static final int MAX_RICHNESS = FILL_STAGE_COUNT - 1;
    private static final ThreadLocal<Deque<PhysicalTransferBatch>> PHYSICAL_TRANSFERS =
            new ThreadLocal<>();

    /**
     * Last complete client entries, retained across Sable plot-chunk replacement during block
     * updates.
     */
    private static final Map<Level, ConcurrentMap<Long, OreDepositChunkData.Entry>>
            CLIENT_ENTRY_CACHE = Collections.synchronizedMap(new WeakHashMap<>());

    private OreDepositData() {}

    public static void set(
            WorldGenLevel level,
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialRichness,
            int richnessReferenceAmount) {
        set(
                level,
                pos,
                baseIndex,
                oreIndex,
                remainingOre,
                initialRichness,
                richnessReferenceAmount,
                -1);
    }

    public static void set(
            WorldGenLevel level,
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialRichness,
            int richnessReferenceAmount,
            int tier) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        OreDepositChunkData data = chunk.getData(ModAttachments.ORE_DEPOSITS);
        int clampedOre = Math.max(0, remainingOre);
        if (data.putIfChanged(
                pos,
                baseIndex,
                oreIndex,
                clampedOre,
                clampedOre,
                initialRichness,
                richnessReferenceAmount,
                tier)) {
            chunk.setUnsaved(true);
            chunk.syncData(ModAttachments.ORE_DEPOSITS);
        }
    }

    /**
     * Worldgen creates many blocks in one operation. Mutate their attachments first and dirty each
     * affected chunk once. Initial attachment data is sent naturally when the completed chunk
     * starts being watched; forcing an early sync here only creates a redundant network/save
     * backlog.
     */
    public static void setGeneratedBatch(WorldGenLevel level, List<GeneratedDeposit> deposits) {
        if (deposits.isEmpty()) {
            return;
        }
        List<GeneratedDeposit> hostMatched = matchOreVariantsToHost(level, deposits);

        // A lens frequently crosses chunk borders, but it still contains many positions from each
        // affected chunk. Resolve each chunk and take its attachment monitor once, rather than
        // doing both for every block in the lens.
        Long2ObjectOpenHashMap<List<GeneratedDeposit>> depositsByChunk =
                new Long2ObjectOpenHashMap<>();
        for (GeneratedDeposit deposit : hostMatched) {
            BlockPos pos = deposit.pos();
            long chunkKey = ChunkPos.asLong(pos.getX() >> 4, pos.getZ() >> 4);
            depositsByChunk.computeIfAbsent(chunkKey, ignored -> new ArrayList<>()).add(deposit);
        }

        for (List<GeneratedDeposit> chunkDeposits : depositsByChunk.values()) {
            BlockPos pos = chunkDeposits.getFirst().pos();
            ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            OreDepositChunkData data = chunk.getData(ModAttachments.ORE_DEPOSITS);
            if (data.putGeneratedBatchIfChanged(chunkDeposits)) {
                chunk.setUnsaved(true);
            }
        }
    }

    /**
     * Every generator picks one ore per deposit or per source feature, so a vein crossing from
     * stone into deepslate would keep the stone ore. Like vanilla, each block instead takes its
     * material's variant for the rock it replaced: deepslate ore in deepslate, stone ore in stone.
     */
    private static List<GeneratedDeposit> matchOreVariantsToHost(
            WorldGenLevel level, List<GeneratedDeposit> deposits) {
        OreDepositPaletteData palette = OreDepositPaletteData.get(level.getLevel());
        List<ResourceLocation> ores = palette.ores();
        List<ResourceLocation> bases = palette.bases();
        Map<Long, Integer> variants = new HashMap<>();
        List<GeneratedDeposit> matched = new ArrayList<>(deposits.size());
        for (GeneratedDeposit deposit : deposits) {
            int oreIndex =
                    variants.computeIfAbsent(
                            ((long) deposit.oreIndex() << 32) | (deposit.baseIndex() & 0xFFFFFFFFL),
                            ignored ->
                                    hostVariantIndex(
                                            palette,
                                            ores,
                                            bases,
                                            deposit.oreIndex(),
                                            deposit.baseIndex()));
            matched.add(
                    oreIndex == deposit.oreIndex()
                            ? deposit
                            : new GeneratedDeposit(
                                    deposit.pos(),
                                    deposit.baseIndex(),
                                    oreIndex,
                                    deposit.amount(),
                                    deposit.tier()));
        }
        return matched;
    }

    /** A deposit's blocks may hold different host variants of its one recorded ore. */
    private static boolean isSameMaterial(
            ResourceLocation recordOre, @Nullable ResourceLocation blockOre) {
        if (blockOre == null) {
            return false;
        }
        String material = OreUnifier.materialKeyFor(recordOre);
        return recordOre.equals(blockOre)
                || !material.isEmpty() && material.equals(OreUnifier.materialKeyFor(blockOre));
    }

    private static int hostVariantIndex(
            OreDepositPaletteData palette,
            List<ResourceLocation> ores,
            List<ResourceLocation> bases,
            int oreIndex,
            int baseIndex) {
        if (oreIndex < 0 || oreIndex >= ores.size() || baseIndex < 0 || baseIndex >= bases.size()) {
            return oreIndex;
        }
        Block ore = BuiltInRegistries.BLOCK.get(ores.get(oreIndex));
        Block variant =
                OreUnifier.variantForHost(ore, BuiltInRegistries.BLOCK.get(bases.get(baseIndex)));
        int variantIndex = variant == ore ? -1 : palette.oreIndex(variant);
        return variantIndex >= 0 ? variantIndex : oreIndex;
    }

    public static void remove(ServerLevel level, BlockPos pos) {
        if (isPhysicalTransferSource(level, pos)) {
            return;
        }

        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos);
        PhysicalOreDepositSavedData.get(level).remove(pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (entry != null && data.remove(pos)) {
            if (entry.remainingOre() > 0) {
                markIndexesDepleted(level, pos, entry);
            }
            chunk.setUnsaved(true);
            chunk.syncData(ModAttachments.ORE_DEPOSITS);
        }
    }

    /**
     * Captures active and exhausted ore entries before Sable replaces their source blocks with air.
     * Both blocks need the position-keyed attachment: active ore uses the ore/amount fields, while
     * an exhausted block still needs {@code baseIndex} for its texture, hardness and drops. Sable
     * cannot move that attachment as part of its normal block/block-entity copy pass.
     */
    public static void beginPhysicalTransfer(
            ServerLevel sourceLevel,
            ServerLevel destinationLevel,
            Function<BlockPos, BlockPos> positionTransform,
            Iterable<BlockPos> positions) {
        List<PhysicalTransfer> transfers = new ArrayList<>();
        Set<Long> sourcePositions = new HashSet<>();

        for (BlockPos sourcePos : positions) {
            if (!isDepositState(sourceLevel.getBlockState(sourcePos))) {
                continue;
            }

            OreDepositChunkData.Entry entry = entry(sourceLevel, sourcePos);
            if (entry == null) {
                continue;
            }

            BlockPos immutableSource = sourcePos.immutable();
            transfers.add(
                    new PhysicalTransfer(
                            immutableSource,
                            positionTransform.apply(immutableSource).immutable(),
                            entry));
            sourcePositions.add(immutableSource.asLong());
        }

        Deque<PhysicalTransferBatch> batches = PHYSICAL_TRANSFERS.get();
        if (batches == null) {
            batches = new ArrayDeque<>();
            PHYSICAL_TRANSFERS.set(batches);
        }
        batches.push(
                new PhysicalTransferBatch(
                        sourceLevel, destinationLevel, sourcePositions, transfers));
    }

    /**
     * Completes the most recent Sable assembly transfer and synchronizes every affected chunk once.
     */
    public static void finishPhysicalTransfer() {
        Deque<PhysicalTransferBatch> batches = PHYSICAL_TRANSFERS.get();
        if (batches == null || batches.isEmpty()) {
            return;
        }

        PhysicalTransferBatch batch = batches.pop();
        Set<ChunkAccess> changedChunks = new HashSet<>();
        for (PhysicalTransfer transfer : batch.transfers()) {
            if (isDepositState(batch.sourceLevel().getBlockState(transfer.sourcePos()))
                    || !isDepositState(
                            batch.destinationLevel().getBlockState(transfer.destinationPos()))) {
                continue;
            }

            ChunkAccess sourceChunk = chunk(batch.sourceLevel(), transfer.sourcePos());
            OreDepositChunkData sourceData =
                    sourceChunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
            if (sourceData != null && sourceData.remove(transfer.sourcePos())) {
                sourceChunk.setUnsaved(true);
                changedChunks.add(sourceChunk);
            }

            ChunkAccess destinationChunk =
                    chunk(batch.destinationLevel(), transfer.destinationPos());
            OreDepositChunkData destinationData =
                    destinationChunk.getData(ModAttachments.ORE_DEPOSITS);
            OreDepositChunkData.Entry entry = transfer.entry();
            destinationData.put(
                    transfer.destinationPos(),
                    entry.baseIndex(),
                    entry.oreIndex(),
                    entry.remainingOre(),
                    entry.initialOre(),
                    entry.initialRichness(),
                    entry.richnessReferenceAmount(),
                    entry.tier());
            PhysicalOreDepositSavedData.get(batch.sourceLevel()).remove(transfer.sourcePos());
            PhysicalOreDepositSavedData.get(batch.destinationLevel())
                    .put(transfer.destinationPos(), entry);
            destinationChunk.setUnsaved(true);
            changedChunks.add(destinationChunk);
        }

        for (ChunkAccess changedChunk : changedChunks) {
            changedChunk.syncData(ModAttachments.ORE_DEPOSITS);
        }
        if (batches.isEmpty()) {
            PHYSICAL_TRANSFERS.remove();
        }
    }

    public static boolean isPhysicalTransferSource(ServerLevel level, BlockPos pos) {
        Deque<PhysicalTransferBatch> batches = PHYSICAL_TRANSFERS.get();
        if (batches == null) {
            return false;
        }
        for (PhysicalTransferBatch batch : batches) {
            if (batch.sourceLevel() == level && batch.sourcePositions().contains(pos.asLong())) {
                return true;
            }
        }
        return false;
    }

    private static boolean isDepositState(BlockState state) {
        return state.is(ModBlocks.ORE_DEPOSIT.get())
                || state.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get());
    }

    public static ResourceLocation oreBlockIdAt(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos);
        return entry != null ? oreId(level, entry.oreIndex()) : null;
    }

    /**
     * Tier stored for this physical deposit block, or {@code -1} for a non-deposit/missing entry.
     */
    public static int tierAt(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos);
        return entry == null ? -1 : entry.tier();
    }

    /** Medium and large deposits are mined by drills only: explosions leave them untouched. */
    public static boolean ignoresExplosions(ServerLevel level, BlockPos pos) {
        return tierAt(level, pos) >= OreDepositTier.MEDIUM.index();
    }

    /**
     * Builds a top-down resource patch from already-loaded physical blocks without loading any
     * extra chunks. Vertical blocks in the same X/Z column are combined into one Factorio-style map
     * cell.
     */
    public static DepositMapSnapshot mapSnapshot(ServerLevel level, ConfirmedDeposit deposit) {
        int radius = OreDepositFeature.MAX_DEPOSIT_FOOTPRINT_DIAMETER;
        BlockPos center = deposit.center();
        Map<Long, int[]> columns = new HashMap<>();
        int minChunkX = Math.floorDiv(center.getX() - radius, 16);
        int maxChunkX = Math.floorDiv(center.getX() + radius, 16);
        int minChunkZ = Math.floorDiv(center.getZ() - radius, 16);
        int maxChunkZ = Math.floorDiv(center.getZ() + radius, 16);
        long radiusSqr = (long) radius * radius;

        for (int chunkX = minChunkX; chunkX <= maxChunkX; chunkX++) {
            for (int chunkZ = minChunkZ; chunkZ <= maxChunkZ; chunkZ++) {
                ChunkAccess chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
                if (chunk == null) {
                    continue;
                }
                OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
                if (data == null) {
                    continue;
                }
                data.forEach(
                        chunkX,
                        chunkZ,
                        (pos, entry) -> {
                            int dx = pos.getX() - center.getX();
                            int dz = pos.getZ() - center.getZ();
                            if (entry.tier() != deposit.tier().index()
                                    || (long) dx * dx + (long) dz * dz > radiusSqr
                                    || Math.abs(pos.getY() - center.getY()) > 12
                                    || !isSameMaterial(
                                            deposit.oreId(), oreId(level, entry.oreIndex()))) {
                                return;
                            }
                            long key = ChunkPos.asLong(dx, dz);
                            int[] amounts = columns.computeIfAbsent(key, ignored -> new int[2]);
                            amounts[0] =
                                    Math.addExact(amounts[0], Math.max(0, entry.remainingOre()));
                            amounts[1] = Math.addExact(amounts[1], Math.max(0, entry.initialOre()));
                        });
            }
        }

        long initial = columns.values().stream().mapToLong(amounts -> amounts[1]).sum();
        List<DepositMapCell> cells =
                columns.entrySet().stream()
                        .filter(entry -> entry.getValue()[0] > 0)
                        .map(
                                entry ->
                                        new DepositMapCell(
                                                ChunkPos.getX(entry.getKey()),
                                                ChunkPos.getZ(entry.getKey()),
                                                entry.getValue()[0],
                                                entry.getValue()[1]))
                        .sorted(
                                Comparator.comparingInt(DepositMapCell::offsetX)
                                        .thenComparingInt(DepositMapCell::offsetZ))
                        .toList();
        long remaining = cells.stream().mapToLong(DepositMapCell::remainingOre).sum();
        return new DepositMapSnapshot(remaining, initial, cells);
    }

    public record DepositMapCell(int offsetX, int offsetZ, int remainingOre, int initialOre) {}

    public record DepositMapSnapshot(
            long remainingOre, long initialOre, List<DepositMapCell> cells) {
        public DepositMapSnapshot {
            cells = List.copyOf(cells);
        }
    }

    /**
     * Resolves a deposit's real ore on either side. The client uses its synchronized palette
     * because the per-position attachment is intentionally not encoded into the shared block state.
     */
    @Nullable
    public static Block oreBlockAt(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            ResourceLocation oreId = oreBlockIdAt(serverLevel, pos);
            if (oreId == null) {
                return null;
            }
            Block ore = BuiltInRegistries.BLOCK.get(oreId);
            return ore == Blocks.AIR ? null : ore;
        }

        Visual visual = visualAt(level, pos);
        return visual == null ? null : OreDepositOrePalette.oreAt(visual.oreIndex());
    }

    public static DepositStats statsAt(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos);
        if (entry == null) {
            return DepositStats.EMPTY;
        }
        return new DepositStats(entry.remainingOre(), entry.initialOre());
    }

    /**
     * Tier of a deposit block as this side knows it (the client from its synced chunk data), or
     * {@code -1}.
     */
    public static int readTierAt(Level level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entryForRead(level, pos);
        return entry == null ? -1 : entry.tier();
    }

    public static int remainingOreAt(Level level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entryForRead(level, pos);
        return entry == null ? 0 : Math.max(0, entry.remainingOre());
    }

    public static void applyClientEntry(
            Level level,
            BlockPos pos,
            int baseIndex,
            int oreIndex,
            int remainingOre,
            int initialOre,
            int initialRichness,
            int richnessReferenceAmount,
            int tier) {
        if (!level.isClientSide) {
            return;
        }

        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData data = chunk.getData(ModAttachments.ORE_DEPOSITS);
        data.put(
                pos,
                baseIndex,
                oreIndex,
                Math.max(0, remainingOre),
                Math.max(0, initialOre),
                initialRichness,
                richnessReferenceAmount,
                tier);
        cacheClientEntry(level, pos, data.get(pos));
        // Sable observes this client-side notification and dirties the owning sub-level render
        // section. Do not change a real block-state property: its plot update can resolve against
        // the backing world and replace the client ore state while the authoritative server state
        // remains intact.
        BlockState state = level.getBlockState(pos);
        level.sendBlockUpdated(pos, state, state, Block.UPDATE_CLIENTS);
    }

    @Nullable
    public static Visual visualAt(Level level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entryForRead(level, pos);
        if (entry == null) {
            return null;
        }

        return new Visual(
                entry.baseIndex(),
                entry.oreIndex(),
                OreVisualStages.stageFor(entry.remainingOre()),
                OreVisualStages.stageFor(entry.initialOre()),
                entry.remainingOre() < entry.initialOre(),
                entry.tier());
    }

    @Nullable
    private static OreDepositChunkData.Entry entryForRead(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            return entry(serverLevel, pos);
        }

        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        OreDepositChunkData.Entry entry = data != null ? data.get(pos) : null;
        if (entry != null) {
            cacheClientEntry(level, pos, entry);
            return entry;
        }

        ConcurrentMap<Long, OreDepositChunkData.Entry> cache = clientCache(level);
        long key = pos.asLong();
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.ORE_DEPOSIT.get())
                && !state.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())) {
            cache.remove(key);
            return null;
        }
        return cache.get(key);
    }

    private static void cacheClientEntry(
            Level level, BlockPos pos, @Nullable OreDepositChunkData.Entry entry) {
        if (level.isClientSide && entry != null) {
            clientCache(level).put(pos.asLong(), entry);
        }
    }

    private static ConcurrentMap<Long, OreDepositChunkData.Entry> clientCache(Level level) {
        synchronized (CLIENT_ENTRY_CACHE) {
            return CLIENT_ENTRY_CACHE.computeIfAbsent(level, ignored -> new ConcurrentHashMap<>());
        }
    }

    public static BlockState baseBlockStateAt(LevelReader level, BlockPos pos) {
        if (!(level instanceof Level concreteLevel)) {
            return Blocks.STONE.defaultBlockState();
        }

        Visual visual = visualAt(concreteLevel, pos);
        if (visual == null) {
            return Blocks.STONE.defaultBlockState();
        }

        return concreteLevel instanceof ServerLevel serverLevel
                ? baseState(serverLevel, visual.baseIndex())
                : OreDepositStonePalette.stoneAt(visual.baseIndex()).defaultBlockState();
    }

    public static void playVirtualBreakEffect(ServerLevel level, BlockPos pos) {
        level.levelEvent(null, 2001, pos, Block.getId(baseBlockStateAt(level, pos)));
    }

    public static ItemStack previewResult(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos);
        if (entry == null || entry.remainingOre() <= 0) {
            return ItemStack.EMPTY;
        }

        ResourceLocation oreBlockId = oreId(level, entry.oreIndex());
        if (oreBlockId == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = dropFor(level, pos, oreBlockId);
        if (!result.isEmpty()) {
            result.setCount(1);
        }
        return result;
    }

    public static boolean mineByPlayer(
            ServerLevel level, BlockPos pos, BlockState state, Player player) {
        return mineByPlayer(level, pos, state, player, true);
    }

    /**
     * Player-breaking entry point used from {@code OreDepositBlock#playerWillDestroy}. Vanilla
     * damages the tool immediately after that callback, so this variant performs the virtual mining
     * and player bookkeeping but deliberately leaves tool durability to the normal breaking
     * pipeline.
     */
    public static boolean mineByPlayerBeforeRemoval(
            ServerLevel level, BlockPos pos, BlockState state, Player player) {
        return mineByPlayer(level, pos, state, player, false);
    }

    private static boolean mineByPlayer(
            ServerLevel level, BlockPos pos, BlockState state, Player player, boolean damageTool) {
        OreDepositChunkData.Entry entry = entry(level, pos);
        if (entry == null || entry.remainingOre() <= 0) {
            return false;
        }

        ResourceLocation oreBlockId = oreId(level, entry.oreIndex());
        if (oreBlockId == null) {
            return false;
        }
        ItemStack tool = player.getMainHandItem();
        BlockState oreState = oreState(oreBlockId);
        if (dev.recipe.DrillMiningRecipes.requiresFluidOutsideDrills(level, oreState)
                || !oreState.canHarvestBlock(level, pos, player)) {
            // Fluid-locked ore and weak tools waste only one unit, with no drop or XP. Keep the
            // same depletion path for ordinary breaking and area-mining tools.
            depleteWithoutDrop(level, pos);
            if (damageTool) {
                tool.mineBlock(level, state, pos, player);
            }
            player.awardStat(Stats.BLOCK_MINED.get(state.getBlock()));
            player.causeFoodExhaustion(0.005F);
            return true;
        }

        ItemStack mined = mineOne(level, pos, player, tool);
        if (!mined.isEmpty()) {
            Block.popResourceFromFace(level, pos, minedDropFace(pos, player), mined);
        }

        if (damageTool) {
            tool.mineBlock(level, state, pos, player);
        }
        player.awardStat(Stats.BLOCK_MINED.get(state.getBlock()));
        player.causeFoodExhaustion(0.005F);
        return true;
    }

    public static boolean mineByWorld(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos);
        if (entry == null || entry.remainingOre() <= 0) {
            return false;
        }

        ItemStack mined = mineOne(level, pos);
        if (!mined.isEmpty()) {
            Block.popResource(level, pos, mined);
        }
        return true;
    }

    /**
     * Up to {@code units} units in one go for an explosion, with one update and one break effect.
     * Returns their loot, rolled per unit; fluid-locked ore loses the units with nothing to show
     * for it.
     */
    public static List<ItemStack> mineByExplosion(ServerLevel level, BlockPos pos, int units) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (units <= 0 || entry == null || data == null || entry.remainingOre() <= 0) {
            return List.of();
        }
        ResourceLocation oreBlockId = oreId(level, entry.oreIndex());
        if (oreBlockId == null) {
            return List.of();
        }

        int amount = Math.min(units, entry.remainingOre());
        List<ItemStack> drops = new ArrayList<>(amount);
        if (!dev.recipe.DrillMiningRecipes.requiresFluidOutsideDrills(
                level, oreState(oreBlockId))) {
            for (int unit = 0; unit < amount; unit++) {
                ItemStack drop = dropFor(level, pos, oreBlockId);
                if (!drop.isEmpty()) {
                    drops.add(drop);
                }
            }
            if (drops.isEmpty()) {
                // As in mineOne: an ore without loot is not mined at all.
                return List.of();
            }
        }
        deplete(level, chunk, data, pos, entry, amount);
        return drops;
    }

    /**
     * A tiny or small deposit hit by another mod's explosion of unknown strength (see {@link
     * ForeignExplosionDetector}) loses all its ore without loot, as if the blast outlasted it, and
     * becomes an exhausted deposit for that explosion to destroy. Returns whether it did.
     */
    public static boolean wipeByExplosion(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (entry == null
                || data == null
                || entry.remainingOre() <= 0
                || entry.tier() >= OreDepositTier.MEDIUM.index()) {
            return false;
        }
        deplete(level, chunk, data, pos, entry, entry.remainingOre());
        return true;
    }

    /**
     * A deposit carried by a Sable sub-level knocked against blocks loses up to {@code units} units
     * of ore with nothing to show for it, and becomes an exhausted deposit once empty. Returns
     * whether it lost any.
     */
    public static boolean depleteByImpact(ServerLevel level, BlockPos pos, int units) {
        return depleteByImpact(level, pos, units, true);
    }

    private static boolean depleteByImpact(
            ServerLevel level, BlockPos pos, int units, boolean breakEffect) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (units <= 0 || entry == null || data == null || entry.remainingOre() <= 0) {
            return false;
        }
        deplete(level, chunk, data, pos, entry, Math.min(units, entry.remainingOre()), breakEffect);
        return true;
    }

    /**
     * The veins one physical body carries, one per ore and tier: every deposit block found in that
     * body's chunks, exhausted ones included in the vein's shape. Veins with no ore left are left
     * out.
     */
    public static List<PhysicalVein> physicalVeins(
            ServerLevel level, Iterable<? extends ChunkAccess> bodyChunks) {
        Map<VeinIdentity, List<VeinBlock>> activeBlocks = new LinkedHashMap<>();
        Map<VeinIdentity, LongOpenHashSet> shapes = new HashMap<>();
        for (ChunkAccess chunk : bodyChunks) {
            restorePhysicalChunkEntries(level, chunk);
            OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
            if (data == null) {
                continue;
            }
            data.forEach(
                    chunk.getPos().x,
                    chunk.getPos().z,
                    (pos, entry) -> {
                        if (!isDepositState(chunk.getBlockState(pos))) {
                            return;
                        }
                        VeinIdentity identity = new VeinIdentity(entry.oreIndex(), entry.tier());
                        shapes.computeIfAbsent(identity, ignored -> new LongOpenHashSet())
                                .add(pos.asLong());
                        if (entry.remainingOre() > 0) {
                            activeBlocks
                                    .computeIfAbsent(identity, ignored -> new ArrayList<>())
                                    .add(
                                            new VeinBlock(
                                                    pos, entry.remainingOre(), entry.initialOre()));
                        }
                    });
        }
        List<PhysicalVein> veins = new ArrayList<>(activeBlocks.size());
        activeBlocks.forEach(
                (identity, blocks) ->
                        veins.add(
                                new PhysicalVein(
                                        identity,
                                        List.copyOf(blocks),
                                        LongSets.unmodifiable(shapes.get(identity)))));
        return veins;
    }

    /**
     * Takes {@code losses[i]} units from {@code vein.activeBlocks().get(i)} with nothing to show
     * for it. The break effect plays once, on the block that lost the most: a large vein would
     * otherwise burst into particles everywhere at once.
     */
    public static void depleteVeinByImpact(ServerLevel level, PhysicalVein vein, int[] losses) {
        List<VeinBlock> blocks = vein.activeBlocks();
        int mostDamaged = 0;
        for (int index = 1; index < losses.length; index++) {
            if (losses[index] > losses[mostDamaged]) {
                mostDamaged = index;
            }
        }
        for (int index = 0; index < blocks.size(); index++) {
            depleteByImpact(level, blocks.get(index).pos(), losses[index], index == mostDamaged);
        }
    }

    public record VeinIdentity(int oreIndex, int tier) {}

    public record VeinBlock(BlockPos pos, int remainingOre, int initialOre) {}

    /**
     * @param shape {@link BlockPos#asLong} of every block of the vein, exhausted ones included:
     *     they still make up its body, so the blocks behind them are not its edge
     */
    public record PhysicalVein(
            VeinIdentity identity, List<VeinBlock> activeBlocks, LongSet shape) {}

    /**
     * The deposit's ore needs a mining fluid, so only Ores & Drills drills get anything out of it.
     */
    public static boolean isFluidLocked(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos);
        if (entry == null || entry.remainingOre() <= 0) {
            return false;
        }
        ResourceLocation oreBlockId = oreId(level, entry.oreIndex());
        return oreBlockId != null
                && dev.recipe.DrillMiningRecipes.requiresFluidOutsideDrills(
                        level, oreState(oreBlockId));
    }

    /**
     * One unit for any mining other than an Ores & Drills drill: explosions, world destruction and
     * other mods' machines. Fluid-locked ore loses the unit without a drop, as when a player breaks
     * it by hand.
     */
    public static ItemStack mineOne(ServerLevel level, BlockPos pos) {
        if (isFluidLocked(level, pos)) {
            depleteWithoutDrop(level, pos);
            return ItemStack.EMPTY;
        }
        return mineOne(level, pos, null, ItemStack.EMPTY);
    }

    public static ItemStack mineOne(
            ServerLevel level, BlockPos pos, @Nullable Player player, ItemStack tool) {
        return mineOne(level, pos, player, tool, stack -> true);
    }

    public static ItemStack mineOneIfAccepted(
            ServerLevel level, BlockPos pos, Predicate<ItemStack> canAccept) {
        return mineOne(level, pos, null, ItemStack.EMPTY, canAccept);
    }

    /** A recipe may replace the loot without replacing the deposit's reserve accounting. */
    public static ItemStack mineOneIfAccepted(
            ServerLevel level,
            BlockPos pos,
            @Nullable ItemStack result,
            Predicate<ItemStack> canAccept) {
        return mineOne(level, pos, null, ItemStack.EMPTY, canAccept, result);
    }

    private static ItemStack mineOne(
            ServerLevel level,
            BlockPos pos,
            @Nullable Player player,
            ItemStack tool,
            Predicate<ItemStack> canAccept) {
        return mineOne(level, pos, player, tool, canAccept, null);
    }

    private static ItemStack mineOne(
            ServerLevel level,
            BlockPos pos,
            @Nullable Player player,
            ItemStack tool,
            Predicate<ItemStack> canAccept,
            @Nullable ItemStack recipeResult) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (entry == null || data == null || entry.remainingOre() <= 0) {
            return ItemStack.EMPTY;
        }

        ResourceLocation oreBlockId = oreId(level, entry.oreIndex());
        if (oreBlockId == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result =
                recipeResult == null
                        ? dropFor(level, pos, oreBlockId, player, tool)
                        : recipeResult.copy();
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!canAccept.test(result)) {
            return ItemStack.EMPTY;
        }

        deplete(level, chunk, data, pos, entry, 1);

        if (player != null) {
            BlockState oreBlockState = oreState(oreBlockId);
            int rawXp = oreBlockState.getExpDrop(level, pos, null, player, tool);
            int actualXp = EnchantmentHelper.processBlockExperience(level, tool, rawXp);
            if (actualXp > 0) {
                oreBlockState.getBlock().popExperience(level, pos, actualXp);
            }
        }

        return result;
    }

    /**
     * Wastes one unit without loot or XP; the physical deposit remains until its reserve is
     * exhausted.
     */
    private static void depleteWithoutDrop(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (entry == null || data == null || entry.remainingOre() <= 0) {
            return;
        }

        deplete(level, chunk, data, pos, entry, 1);
    }

    private static void deplete(
            ServerLevel level,
            ChunkAccess chunk,
            OreDepositChunkData data,
            BlockPos pos,
            OreDepositChunkData.Entry entry,
            int amount) {
        deplete(level, chunk, data, pos, entry, amount, true);
    }

    private static void deplete(
            ServerLevel level,
            ChunkAccess chunk,
            OreDepositChunkData data,
            BlockPos pos,
            OreDepositChunkData.Entry entry,
            int amount,
            boolean breakEffect) {
        if (breakEffect) {
            playVirtualBreakEffect(level, pos);
        }
        int remaining = entry.remainingOre() - amount;
        data.put(
                pos,
                entry.baseIndex(),
                entry.oreIndex(),
                Math.max(0, remaining),
                entry.initialOre(),
                entry.initialRichness(),
                entry.richnessReferenceAmount(),
                entry.tier());
        OreDepositChunkData.Entry updatedEntry = data.get(pos);
        PhysicalOreDepositSavedData.get(level).updateIfTracked(pos, updatedEntry);
        PacketDistributor.sendToPlayersTrackingChunk(
                level,
                chunk.getPos(),
                new OreDepositRemainingPayload(
                        pos,
                        updatedEntry.baseIndex(),
                        updatedEntry.oreIndex(),
                        updatedEntry.remainingOre(),
                        updatedEntry.initialOre(),
                        updatedEntry.initialRichness(),
                        updatedEntry.richnessReferenceAmount(),
                        updatedEntry.tier()));
        if (remaining <= 0) {
            markIndexesDepleted(level, pos, entry);
            level.setBlock(
                    pos,
                    ModBlocks.EXHAUSTED_ORE_DEPOSIT.get().defaultBlockState(),
                    Block.UPDATE_ALL);
        }
        chunk.setUnsaved(true);
    }

    private static void markIndexesDepleted(
            ServerLevel level, BlockPos pos, OreDepositChunkData.Entry entry) {
        ResourceLocation oreId = oreId(level, entry.oreIndex());
        if (oreId != null) {
            ConfirmedDepositIndex.get(level).markBlockDepleted(pos, oreId, entry.tier());
        }
        if (entry.tier() >= OreDepositFeature.TIER_MEDIUM) {
            Block ore = oreId == null ? null : BuiltInRegistries.BLOCK.get(oreId);
            if (ore != null) {
                LargeDepositSpatialIndex.get(level)
                        .markBlockDepleted(pos, entry.tier(), OreUnifier.materialKeyFor(ore));
            }
        }
    }

    private static OreDepositChunkData.Entry entry(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry persisted =
                PhysicalOreDepositSavedData.get(level).restoreEntry(level, chunk, pos);
        if (persisted != null) {
            return persisted;
        }
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        return data != null ? data.get(pos) : null;
    }

    /**
     * Restores Sable plot metadata before its custom chunk sender builds the initial sync packet.
     */
    public static void restorePhysicalChunkEntries(ServerLevel level, ChunkAccess chunk) {
        PhysicalOreDepositSavedData.get(level).restoreChunk(level, chunk);
    }

    /**
     * Maps an absolute amount in the global 1..5000 range onto four logarithmic fill stages.
     * Deposit tier, ore type and a block's original amount deliberately do not take part here.
     */
    public static int calculateFillStage(
            int remainingOre, int minimumOre, int maximumOre, int stageCount) {
        return DepositTierMath.calculateFillStage(remainingOre, minimumOre, maximumOre, stageCount);
    }

    private static ResourceLocation oreId(ServerLevel level, int oreIndex) {
        List<ResourceLocation> ores = OreDepositPaletteData.get(level).ores();
        return oreIndex >= 0 && oreIndex < ores.size() ? ores.get(oreIndex) : null;
    }

    /**
     * What a deposit reverts to once its ore is fully mined out — the real local stone it was
     * generated from.
     */
    private static BlockState baseState(ServerLevel level, int baseIndex) {
        List<ResourceLocation> bases = OreDepositPaletteData.get(level).bases();
        if (baseIndex < 0 || baseIndex >= bases.size()) {
            return Blocks.STONE.defaultBlockState();
        }

        Block baseBlock = BuiltInRegistries.BLOCK.get(bases.get(baseIndex));
        return baseBlock == null || baseBlock == Blocks.AIR
                ? Blocks.STONE.defaultBlockState()
                : baseBlock.defaultBlockState();
    }

    private static ItemStack dropFor(ServerLevel level, BlockPos pos, ResourceLocation oreBlockId) {
        return dropFor(level, pos, oreBlockId, null, ItemStack.EMPTY);
    }

    private static ItemStack dropFor(
            ServerLevel level,
            BlockPos pos,
            ResourceLocation oreBlockId,
            @Nullable Player player,
            ItemStack tool) {
        BlockState oreState = oreState(oreBlockId);
        if (oreState.isAir()) {
            return ItemStack.EMPTY;
        }

        ItemStack result =
                tool.isEmpty()
                        ? OreDepositDrops.firstDrop(level, pos, oreState)
                        : OreDepositDrops.firstDrop(level, pos, oreState, player, tool);
        if (result.isEmpty()) {
            result = new ItemStack(oreState.getBlock().asItem());
        }
        return result;
    }

    private static Direction minedDropFace(BlockPos pos, Player player) {
        double centerX = pos.getX() + 0.5D;
        double centerY = pos.getY() + 0.5D;
        double centerZ = pos.getZ() + 0.5D;
        return Direction.getNearest(
                player.getX() - centerX, player.getEyeY() - centerY, player.getZ() - centerZ);
    }

    private static BlockState oreState(ResourceLocation oreBlockId) {
        Block oreBlock = BuiltInRegistries.BLOCK.get(oreBlockId);
        if (oreBlock == null || oreBlock == Blocks.AIR) {
            return Blocks.AIR.defaultBlockState();
        }
        return oreBlock.defaultBlockState();
    }

    private static ChunkAccess chunk(Level level, BlockPos pos) {
        return level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
    }

    public record DepositStats(int remainingOre, int initialOre) {
        public static final DepositStats EMPTY = new DepositStats(0, 0);

        public boolean isEmpty() {
            return remainingOre <= 0 || initialOre <= 0;
        }
    }

    public record GeneratedDeposit(
            BlockPos pos, int baseIndex, int oreIndex, int amount, int tier) {}

    /**
     * @param stage visual stage of the remaining ore, see {@link OreVisualStages}
     * @param initialStage visual stage the block was generated with; drawn as a faint trace once
     *     mined
     * @param depleted whether some ore has been taken, i.e. the trace of the initial stage is
     *     visible
     */
    public record Visual(
            int baseIndex, int oreIndex, int stage, int initialStage, boolean depleted, int tier) {}

    private record PhysicalTransfer(
            BlockPos sourcePos, BlockPos destinationPos, OreDepositChunkData.Entry entry) {}

    private record PhysicalTransferBatch(
            ServerLevel sourceLevel,
            ServerLevel destinationLevel,
            Set<Long> sourcePositions,
            List<PhysicalTransfer> transfers) {}

    private static int clampRichness(int richness) {
        return Math.max(MIN_RICHNESS, Math.min(MAX_RICHNESS, richness));
    }
}

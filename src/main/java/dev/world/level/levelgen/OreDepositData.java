package dev.world.level.levelgen;

import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import dev.network.OreDepositRemainingPayload;
import dev.world.block.OreDepositBlock;
import dev.world.block.entity.deposit.OreDepositDrops;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.stats.Stats;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.enchantment.EnchantmentHelper;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.List;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.function.Function;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public final class OreDepositData {
    public static final int MINIMUM_ORE_AMOUNT = 1;
    public static final int MAXIMUM_ORE_AMOUNT = 5_000;
    public static final int FILL_STAGE_COUNT = 4;
    private static final int MIN_RICHNESS = 0;
    private static final int MAX_RICHNESS = FILL_STAGE_COUNT - 1;
    private static final int[] LEGACY_RECOVERY_AMOUNTS = {250, 500, 1_000, 2_000, 4_000, 8_000, 12_000, 16_000};
    private static final ThreadLocal<Deque<PhysicalTransferBatch>> PHYSICAL_TRANSFERS = new ThreadLocal<>();
    /** Last complete client entries, retained across Sable plot-chunk replacement during block updates. */
    private static final Map<Level, ConcurrentMap<Long, OreDepositChunkData.Entry>> CLIENT_ENTRY_CACHE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private OreDepositData() {
    }

    public static void set(WorldGenLevel level, BlockPos pos, int baseIndex, int oreIndex, int remainingOre, int initialRichness, int richnessReferenceAmount) {
        set(level, pos, baseIndex, oreIndex, remainingOre, initialRichness, richnessReferenceAmount, -1);
    }

    public static void set(WorldGenLevel level, BlockPos pos, int baseIndex, int oreIndex, int remainingOre, int initialRichness, int richnessReferenceAmount, int tier) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        OreDepositChunkData data = chunk.getData(ModAttachments.ORE_DEPOSITS);
        int clampedOre = Math.max(0, remainingOre);
        if (data.putIfChanged(pos, baseIndex, oreIndex, clampedOre, clampedOre, initialRichness, richnessReferenceAmount, tier)) {
            chunk.setUnsaved(true);
            chunk.syncData(ModAttachments.ORE_DEPOSITS);
        }
    }

    /**
     * Worldgen creates many blocks in one operation. Mutate their attachments first and dirty each affected
     * chunk once. Initial attachment data is sent naturally when the completed chunk starts being watched;
     * forcing an early sync here only creates a redundant network/save backlog.
     */
    public static void setGeneratedBatch(WorldGenLevel level, List<GeneratedDeposit> deposits) {
        if (deposits.isEmpty()) {
            return;
        }

        // A lens frequently crosses chunk borders, but it still contains many positions from each
        // affected chunk. Resolve each chunk and take its attachment monitor once, rather than doing
        // both for every block in the lens.
        Long2ObjectOpenHashMap<List<GeneratedDeposit>> depositsByChunk = new Long2ObjectOpenHashMap<>();
        for (GeneratedDeposit deposit : deposits) {
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

    public static void remove(ServerLevel level, BlockPos pos) {
        if (isPhysicalTransferSource(level, pos)) {
            return;
        }

        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos, false);
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
     * Both blocks need the position-keyed attachment: active ore uses the ore/amount fields, while an
     * exhausted block still needs {@code baseIndex} for its texture, hardness and drops. Sable cannot
     * move that attachment as part of its normal block/block-entity copy pass.
     */
    public static void beginPhysicalTransfer(
            ServerLevel sourceLevel,
            ServerLevel destinationLevel,
            Function<BlockPos, BlockPos> positionTransform,
            Iterable<BlockPos> positions
    ) {
        List<PhysicalTransfer> transfers = new ArrayList<>();
        Set<Long> sourcePositions = new HashSet<>();

        for (BlockPos sourcePos : positions) {
            if (!isDepositState(sourceLevel.getBlockState(sourcePos))) {
                continue;
            }

            OreDepositChunkData.Entry entry = entry(sourceLevel, sourcePos, false);
            if (entry == null) {
                continue;
            }

            BlockPos immutableSource = sourcePos.immutable();
            transfers.add(new PhysicalTransfer(
                    immutableSource,
                    positionTransform.apply(immutableSource).immutable(),
                    entry
            ));
            sourcePositions.add(immutableSource.asLong());
        }

        Deque<PhysicalTransferBatch> batches = PHYSICAL_TRANSFERS.get();
        if (batches == null) {
            batches = new ArrayDeque<>();
            PHYSICAL_TRANSFERS.set(batches);
        }
        batches.push(new PhysicalTransferBatch(
                sourceLevel,
                destinationLevel,
                sourcePositions,
                transfers
        ));
    }

    /** Completes the most recent Sable assembly transfer and synchronizes every affected chunk once. */
    public static void finishPhysicalTransfer() {
        Deque<PhysicalTransferBatch> batches = PHYSICAL_TRANSFERS.get();
        if (batches == null || batches.isEmpty()) {
            return;
        }

        PhysicalTransferBatch batch = batches.pop();
        Set<ChunkAccess> changedChunks = new HashSet<>();
        for (PhysicalTransfer transfer : batch.transfers()) {
            if (isDepositState(batch.sourceLevel().getBlockState(transfer.sourcePos()))
                    || !isDepositState(batch.destinationLevel().getBlockState(transfer.destinationPos()))) {
                continue;
            }

            ChunkAccess sourceChunk = chunk(batch.sourceLevel(), transfer.sourcePos());
            OreDepositChunkData sourceData = sourceChunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
            if (sourceData != null && sourceData.remove(transfer.sourcePos())) {
                sourceChunk.setUnsaved(true);
                changedChunks.add(sourceChunk);
            }

            ChunkAccess destinationChunk = chunk(batch.destinationLevel(), transfer.destinationPos());
            OreDepositChunkData destinationData = destinationChunk.getData(ModAttachments.ORE_DEPOSITS);
            OreDepositChunkData.Entry entry = transfer.entry();
            destinationData.put(
                    transfer.destinationPos(),
                    entry.baseIndex(),
                    entry.oreIndex(),
                    entry.remainingOre(),
                    entry.initialOre(),
                    entry.initialRichness(),
                    entry.richnessReferenceAmount(),
                    entry.tier()
            );
            PhysicalOreDepositSavedData.get(batch.sourceLevel()).remove(transfer.sourcePos());
            PhysicalOreDepositSavedData.get(batch.destinationLevel()).put(transfer.destinationPos(), entry);
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

    private static boolean isPhysicalTransferSource(ServerLevel level, BlockPos pos) {
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
        return state.is(ModBlocks.ORE_DEPOSIT.get()) || state.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get());
    }

    public static ResourceLocation oreBlockIdAt(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        return entry != null ? oreId(level, entry.oreIndex()) : null;
    }

    /**
     * Resolves a deposit's real ore on either side. The client uses its synchronized palette because
     * the per-position attachment is intentionally not encoded into the shared block state.
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
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        if (entry == null) {
            return DepositStats.EMPTY;
        }
        return new DepositStats(entry.remainingOre(), entry.initialOre());
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
            int tier
    ) {
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
                tier
        );
        cacheClientEntry(level, pos, data.get(pos));
        // Sable observes this client-side notification and dirties the owning sub-level render section.
        // Do not change a real block-state property: its plot update can resolve against the backing world
        // and replace the client ore state while the authoritative server state remains intact.
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
                entry.remainingOre() < entry.initialOre()
        );
    }

    @Nullable
    private static OreDepositChunkData.Entry entryForRead(Level level, BlockPos pos) {
        if (level instanceof ServerLevel serverLevel) {
            return entry(serverLevel, pos, false);
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
        if (!state.is(ModBlocks.ORE_DEPOSIT.get()) && !state.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())) {
            cache.remove(key);
            return null;
        }
        return cache.get(key);
    }

    private static void cacheClientEntry(Level level, BlockPos pos, @Nullable OreDepositChunkData.Entry entry) {
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
        OreDepositChunkData.Entry entry = entry(level, pos, true);
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

    public static boolean mineByPlayer(ServerLevel level, BlockPos pos, BlockState state, Player player) {
        return mineByPlayer(level, pos, state, player, true);
    }

    /**
     * Player-breaking entry point used from {@code OreDepositBlock#playerWillDestroy}. Vanilla damages
     * the tool immediately after that callback, so this variant performs the virtual mining and player
     * bookkeeping but deliberately leaves tool durability to the normal breaking pipeline.
     */
    public static boolean mineByPlayerBeforeRemoval(ServerLevel level, BlockPos pos, BlockState state, Player player) {
        return mineByPlayer(level, pos, state, player, false);
    }

    private static boolean mineByPlayer(
            ServerLevel level,
            BlockPos pos,
            BlockState state,
            Player player,
            boolean damageTool
    ) {
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        if (entry == null || entry.remainingOre() <= 0) {
            return false;
        }

        ResourceLocation oreBlockId = oreId(level, entry.oreIndex());
        if (oreBlockId == null) {
            return false;
        }
        ItemStack tool = player.getMainHandItem();
        BlockState oreState = oreState(oreBlockId);
        if (!oreState.canHarvestBlock(level, pos, player)) {
            // Matches vanilla: mining ore with too weak a tool still wastes that unit of the vein, no drop, no XP.
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
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        if (entry == null || entry.remainingOre() <= 0) {
            return false;
        }

        ItemStack mined = mineOne(level, pos);
        if (!mined.isEmpty()) {
            Block.popResource(level, pos, mined);
        }
        return true;
    }

    public static ItemStack mineOne(ServerLevel level, BlockPos pos) {
        return mineOne(level, pos, null, ItemStack.EMPTY);
    }

    public static ItemStack mineOne(ServerLevel level, BlockPos pos, @Nullable Player player, ItemStack tool) {
        return mineOne(level, pos, player, tool, stack -> true);
    }

    public static ItemStack mineOneIfAccepted(ServerLevel level, BlockPos pos, Predicate<ItemStack> canAccept) {
        return mineOne(level, pos, null, ItemStack.EMPTY, canAccept);
    }

    private static ItemStack mineOne(
            ServerLevel level,
            BlockPos pos,
            @Nullable Player player,
            ItemStack tool,
            Predicate<ItemStack> canAccept
    ) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (entry == null || data == null || entry.remainingOre() <= 0) {
            return ItemStack.EMPTY;
        }

        ResourceLocation oreBlockId = oreId(level, entry.oreIndex());
        if (oreBlockId == null) {
            return ItemStack.EMPTY;
        }
        ItemStack result = dropFor(level, pos, oreBlockId, player, tool);
        if (result.isEmpty()) {
            return ItemStack.EMPTY;
        }
        if (!canAccept.test(result)) {
            return ItemStack.EMPTY;
        }

        deplete(level, chunk, data, pos, entry);

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

    /** Wastes one unit of the vein with no drop and no XP, matching vanilla's "wrong tool" harvest semantics. */
    private static void depleteWithoutDrop(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (entry == null || data == null || entry.remainingOre() <= 0) {
            return;
        }

        deplete(level, chunk, data, pos, entry);
    }

    private static void deplete(ServerLevel level, ChunkAccess chunk, OreDepositChunkData data, BlockPos pos, OreDepositChunkData.Entry entry) {
        playVirtualBreakEffect(level, pos);
        int remaining = entry.remainingOre() - 1;
        data.put(pos, entry.baseIndex(), entry.oreIndex(), Math.max(0, remaining), entry.initialOre(), entry.initialRichness(), entry.richnessReferenceAmount(), entry.tier());
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
                        updatedEntry.tier()
                )
        );
        if (remaining <= 0) {
            markIndexesDepleted(level, pos, entry);
            level.setBlock(pos, ModBlocks.EXHAUSTED_ORE_DEPOSIT.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        chunk.setUnsaved(true);
    }

    private static void markIndexesDepleted(ServerLevel level, BlockPos pos, OreDepositChunkData.Entry entry) {
        ResourceLocation oreId = oreId(level, entry.oreIndex());
        if (oreId != null) {
            ConfirmedDepositIndex.get(level).markBlockDepleted(pos, oreId);
        }
        if (entry.tier() >= OreDepositFeature.TIER_MEDIUM) {
            Block ore = oreId == null ? null : BuiltInRegistries.BLOCK.get(oreId);
            if (ore != null) {
                LargeDepositSpatialIndex.get(level).markBlockDepleted(
                        pos, entry.tier(), OreUnifier.materialKeyFor(ore)
                );
            }
        }
    }

    private static OreDepositChunkData.Entry entry(ServerLevel level, BlockPos pos, boolean recoverLegacy) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData.Entry persisted = PhysicalOreDepositSavedData.get(level).restoreEntry(level, chunk, pos);
        if (persisted != null) {
            return persisted;
        }
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        OreDepositChunkData.Entry entry = data != null ? data.get(pos) : null;
        return entry == null && recoverLegacy ? recoverLegacyEntry(level, chunk, pos) : entry;
    }

    private static OreDepositChunkData.Entry recoverLegacyEntry(ServerLevel level, ChunkAccess chunk, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.ORE_DEPOSIT.get())) {
            return null;
        }

        int oreIndex = 0;
        int richness = MAX_RICHNESS;
        int baseIndex = 0;
        int amount = LEGACY_RECOVERY_AMOUNTS[clampRichness(richness)];
        OreDepositChunkData data = chunk.getData(ModAttachments.ORE_DEPOSITS);
        data.put(pos, baseIndex, oreIndex, amount, amount, richness, Math.max(1, (int)Math.ceil(amount * 0.5D)));
        chunk.setUnsaved(true);
        chunk.syncData(ModAttachments.ORE_DEPOSITS);
        OreDepositChunkData.Entry recovered = data.get(pos);
        PhysicalOreDepositSavedData.get(level).put(pos, recovered);
        return recovered;
    }

    /** Restores Sable plot metadata before its custom chunk sender builds the initial sync packet. */
    public static void restorePhysicalChunkEntries(ServerLevel level, ChunkAccess chunk) {
        PhysicalOreDepositSavedData.get(level).restoreChunk(level, chunk);
    }

    /**
     * Maps an absolute amount in the global 1..5000 range onto four logarithmic fill stages.
     * Deposit tier, ore type and a block's original amount deliberately do not take part here.
     */
    public static int calculateFillStage(int remainingOre, int minimumOre, int maximumOre, int stageCount) {
        return DepositTierMath.calculateFillStage(remainingOre, minimumOre, maximumOre, stageCount);
    }

    private static ResourceLocation oreId(ServerLevel level, int oreIndex) {
        List<ResourceLocation> ores = OreDepositPaletteData.get(level).ores();
        return oreIndex >= 0 && oreIndex < ores.size() ? ores.get(oreIndex) : null;
    }

    /** What a deposit reverts to once its ore is fully mined out — the real local stone it was generated from. */
    private static BlockState baseState(ServerLevel level, int baseIndex) {
        List<ResourceLocation> bases = OreDepositPaletteData.get(level).bases();
        if (baseIndex < 0 || baseIndex >= bases.size()) {
            return Blocks.STONE.defaultBlockState();
        }

        Block baseBlock = BuiltInRegistries.BLOCK.get(bases.get(baseIndex));
        return baseBlock == null || baseBlock == Blocks.AIR ? Blocks.STONE.defaultBlockState() : baseBlock.defaultBlockState();
    }

    private static ItemStack dropFor(ServerLevel level, BlockPos pos, ResourceLocation oreBlockId) {
        return dropFor(level, pos, oreBlockId, null, ItemStack.EMPTY);
    }

    private static ItemStack dropFor(
            ServerLevel level,
            BlockPos pos,
            ResourceLocation oreBlockId,
            @Nullable Player player,
            ItemStack tool
    ) {
        BlockState oreState = oreState(oreBlockId);
        if (oreState.isAir()) {
            return ItemStack.EMPTY;
        }

        ItemStack result = tool.isEmpty()
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
                player.getX() - centerX,
                player.getEyeY() - centerY,
                player.getZ() - centerZ
        );
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

    public record GeneratedDeposit(BlockPos pos, int baseIndex, int oreIndex, int amount, int tier) {
    }

    /**
     * @param stage        visual stage of the remaining ore, see {@link OreVisualStages}
     * @param initialStage visual stage the block was generated with; drawn as a faint trace once mined
     * @param depleted     whether some ore has been taken, i.e. the trace of the initial stage is visible
     */
    public record Visual(int baseIndex, int oreIndex, int stage, int initialStage, boolean depleted) {
    }

    private record PhysicalTransfer(BlockPos sourcePos, BlockPos destinationPos, OreDepositChunkData.Entry entry) {
    }

    private record PhysicalTransferBatch(
            ServerLevel sourceLevel,
            ServerLevel destinationLevel,
            Set<Long> sourcePositions,
            List<PhysicalTransfer> transfers
    ) {
    }

    private static int clampRichness(int richness) {
        return Math.max(MIN_RICHNESS, Math.min(MAX_RICHNESS, richness));
    }
}

package dev.world.level.levelgen;

import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
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

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import javax.annotation.Nullable;

public final class OreDepositData {
    public static final int MINIMUM_ORE_AMOUNT = 1;
    public static final int MAXIMUM_ORE_AMOUNT = 5_000;
    public static final int FILL_STAGE_COUNT = 8;
    private static final int MIN_RICHNESS = 0;
    private static final int MAX_RICHNESS = FILL_STAGE_COUNT - 1;
    private static final int[] LEGACY_RECOVERY_AMOUNTS = {250, 500, 1_000, 2_000, 4_000, 8_000, 12_000, 16_000};

    private OreDepositData() {
    }

    public static void set(WorldGenLevel level, BlockPos pos, int baseIndex, int oreIndex, int remainingOre, int initialRichness, int richnessReferenceAmount) {
        set(level, pos, baseIndex, oreIndex, remainingOre, initialRichness, richnessReferenceAmount, -1);
    }

    public static void set(WorldGenLevel level, BlockPos pos, int baseIndex, int oreIndex, int remainingOre, int initialRichness, int richnessReferenceAmount, int tier) {
        ChunkAccess chunk = level.getChunk(pos.getX() >> 4, pos.getZ() >> 4);
        OreDepositChunkData data = chunk.getData(ModAttachments.ORE_DEPOSITS);
        int clampedOre = Math.max(0, remainingOre);
        data.put(pos, baseIndex, oreIndex, clampedOre, clampedOre, initialRichness, richnessReferenceAmount, tier);
        chunk.setUnsaved(true);
        chunk.syncData(ModAttachments.ORE_DEPOSITS);
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
            data.putGeneratedBatch(chunkDeposits);
            chunk.setUnsaved(true);
        }
    }

    public static void remove(ServerLevel level, BlockPos pos) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        OreDepositChunkData.Entry entry = data != null ? data.get(pos) : null;
        if (entry != null && data.remove(pos)) {
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
            chunk.setUnsaved(true);
            chunk.syncData(ModAttachments.ORE_DEPOSITS);
        }
    }

    public static ResourceLocation oreBlockIdAt(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        return entry != null ? oreId(level, entry.oreIndex()) : null;
    }

    public static DepositStats statsAt(ServerLevel level, BlockPos pos) {
        OreDepositChunkData.Entry entry = entry(level, pos, true);
        if (entry == null) {
            return DepositStats.EMPTY;
        }
        return new DepositStats(entry.remainingOre(), entry.initialOre());
    }

    @Nullable
    public static Visual visualAt(Level level, BlockPos pos) {
        ChunkAccess chunk = chunk(level, pos);
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        OreDepositChunkData.Entry entry = data != null ? data.get(pos) : null;
        if (entry == null) {
            return null;
        }

        return new Visual(
                entry.baseIndex(),
                entry.oreIndex(),
                Math.max(0, calculateFillStage(entry.remainingOre(), MINIMUM_ORE_AMOUNT, MAXIMUM_ORE_AMOUNT, FILL_STAGE_COUNT) - 1)
        );
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
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        OreDepositChunkData.Entry entry = data != null ? data.get(pos) : null;
        if (entry == null) {
            entry = recoverLegacyEntry(level, chunk, pos);
            data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        }
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
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        OreDepositChunkData.Entry entry = data != null ? data.get(pos) : null;
        if (entry == null) {
            entry = recoverLegacyEntry(level, chunk, pos);
            data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        }
        if (entry == null || data == null || entry.remainingOre() <= 0) {
            return;
        }

        deplete(level, chunk, data, pos, entry);
    }

    private static void deplete(ServerLevel level, ChunkAccess chunk, OreDepositChunkData data, BlockPos pos, OreDepositChunkData.Entry entry) {
        playVirtualBreakEffect(level, pos);
        int remaining = entry.remainingOre() - 1;
        if (remaining <= 0) {
            level.setBlock(pos, baseState(level, entry.baseIndex()), Block.UPDATE_ALL);
        } else {
            int oldStage = calculateFillStage(entry.remainingOre(), MINIMUM_ORE_AMOUNT, MAXIMUM_ORE_AMOUNT, FILL_STAGE_COUNT);
            int newStage = calculateFillStage(remaining, MINIMUM_ORE_AMOUNT, MAXIMUM_ORE_AMOUNT, FILL_STAGE_COUNT);
            data.put(pos, entry.baseIndex(), entry.oreIndex(), remaining, entry.initialOre(), entry.initialRichness(), entry.richnessReferenceAmount(), entry.tier());
            if (oldStage != newStage) {
                chunk.syncData(ModAttachments.ORE_DEPOSITS);
                updateVisual(level, pos);
            }
        }
        chunk.setUnsaved(true);
    }

    private static OreDepositChunkData.Entry entry(ServerLevel level, BlockPos pos, boolean recoverLegacy) {
        ChunkAccess chunk = chunk(level, pos);
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
        return data.get(pos);
    }

    /** Re-broadcasts a real state transition only when the attachment's logarithmic stage changed. */
    private static void updateVisual(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.is(ModBlocks.ORE_DEPOSIT.get())) {
            return;
        }

        level.setBlock(pos, state.cycle(OreDepositBlock.REFRESH), Block.UPDATE_ALL);
    }

    public static int visualRichness(int remainingOre, int initialOre) {
        return calculateFillStage(remainingOre, MINIMUM_ORE_AMOUNT, MAXIMUM_ORE_AMOUNT, FILL_STAGE_COUNT) - 1;
    }

    public static int visualRichness(int remainingOre, int initialOre, int initialRichness) {
        return visualRichness(remainingOre, initialOre);
    }

    public static int visualRichness(int remainingOre, int initialOre, int initialRichness, int richnessReferenceAmount) {
        return visualRichness(remainingOre, initialOre);
    }

    /**
     * Maps an absolute amount in the global 1..5000 range onto eight logarithmic fill stages.
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

    public record Visual(int baseIndex, int oreIndex, int richness) {
    }

    private static int clampRichness(int richness) {
        return Math.max(MIN_RICHNESS, Math.min(MAX_RICHNESS, richness));
    }
}

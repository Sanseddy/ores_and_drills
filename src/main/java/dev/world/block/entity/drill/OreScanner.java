package dev.world.block.entity.drill;

import dev.registry.ModBlocks;
import dev.recipe.DrillMiningRecipe;
import dev.recipe.DrillMiningFluid;
import dev.recipe.DrillMiningRecipes;
import dev.world.block.DrillStructure;
import dev.world.block.MiningDrillTier;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositFeature;
import dev.world.level.levelgen.OreTags;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

public final class OreScanner {
    private static final int DROP_RANGE_SAMPLES = 64;
    /** Vertical range scanned below the drill per column; comfortably covers the deepest possible vein (see {@link OreDepositFeature#MAX_UNDERGROUND_DEPTH}). */
    public static final int SCAN_DEPTH = OreDepositFeature.MAX_UNDERGROUND_DEPTH + 4;
    private static final Map<ResourceLocation, DropRange> DROP_RANGE_CACHE = new ConcurrentHashMap<>();

    private OreScanner() {
    }

    public static void clearDropRangeCache() { DROP_RANGE_CACHE.clear(); }

    /**
     * @param remainingInVein how much ore is still left at this exact deposit block, or 1 for a plain ore block
     * @param funnelStage     the step of the drill's growing funnel that reaches this block (see {@link ConeMiningOrder})
     */
    public record Target(BlockPos pos, ItemStack result, int minCount, int maxCount, int remainingInVein,
                         DrillMiningFluid requiredFluid, int funnelStage) {
        public Target(BlockPos pos, ItemStack result, int minCount, int maxCount, int remainingInVein) {
            this(pos, result, minCount, maxCount, remainingInVein, null, 0);
        }
    }

    private record DropRange(ItemStack result, int minCount, int maxCount) {
        DropRange include(ItemStack stack) {
            if (stack.isEmpty() || !sameResult(result, stack)) {
                return this;
            }

            return new DropRange(result, Math.min(minCount, stack.getCount()), Math.max(maxCount, stack.getCount()));
        }
    }

    /**
     * Scans the drill's mining area from directly below it down through {@link #SCAN_DEPTH} blocks, collecting
     * every ore position (plain stone/rock between ore is skipped, not mined) in the order the drill works them:
     * the stages of a funnel that starts as the top centre block under the bit and widens and deepens from there
     * (see {@link ConeMiningOrder}). The drill works the lowest stage that still holds ore, so the funnel only
     * grows once everything inside it is worked out.
     */
    public static List<Target> scan(ServerLevel level, BlockPos origin, Direction facing, DrillStructure structure, MiningDrillTier tier) {
        // The mining area extends past the body on every side (2x2 -> 2x2, 3x3 -> 5x5, 5x5 -> 13x13, 7x7 -> 19x19).
        List<ConeMiningOrder.Cell> cells = ConeMiningOrder.of(structure.size(), structure.miningAreaMargin(), SCAN_DEPTH);
        List<Target> targets = new ArrayList<>();
        for (ConeMiningOrder.Cell cell : cells) {
            BlockPos columnTop = structure.offset(origin, facing, cell.x(), 0, cell.z()).below();
            scanBlock(level, columnTop.below(cell.depth()), cell.stage(), tier, targets);
        }
        return targets;
    }

    private static void scanBlock(ServerLevel level, BlockPos targetPos, int funnelStage, MiningDrillTier tier, List<Target> targets) {
        BlockState targetState = level.getBlockState(targetPos);
        BlockState source = harvestCheckState(level, targetPos, targetState);
        if ((!OreTags.isOre(targetState) && DrillMiningRecipes.find(level, source) == null) || !tier.canHarvest(source)) {
            return;
        }

        ItemStack result = miningResultFor(level, targetPos, targetState);
        if (result.isEmpty()) {
            return;
        }

        DropRange range = dropRangeFor(level, targetPos, targetState, result);
        int remainingInVein = remainingInVein(level, targetPos, targetState);
        DrillMiningRecipe recipe = DrillMiningRecipes.find(level, source);
        targets.add(new Target(targetPos, result, range.minCount(), range.maxCount(), remainingInVein,
                recipe == null ? null : recipe.fluid().orElse(null), funnelStage));
    }

    private static int remainingInVein(ServerLevel level, BlockPos targetPos, BlockState targetState) {
        if (!targetState.is(ModBlocks.ORE_DEPOSIT.get())) {
            return 1;
        }

        return OreDepositData.statsAt(level, targetPos).remainingOre();
    }

    public static List<Target> mineable(List<Target> targets, Predicate<ItemStack> canAccept) {
        List<Target> result = new ArrayList<>(targets.size());
        for (Target target : targets) {
            if (!canAccept.test(target.result())) {
                continue;
            }

            result.add(target);
        }

        return result;
    }

    /** The ore_deposit block itself carries no tool-tier metadata; the harvest check must run against the ore it actually holds. */
    private static BlockState harvestCheckState(ServerLevel level, BlockPos targetPos, BlockState targetState) {
        if (!targetState.is(ModBlocks.ORE_DEPOSIT.get())) {
            return targetState;
        }

        ResourceLocation oreId = OreDepositData.oreBlockIdAt(level, targetPos);
        if (oreId == null) {
            return targetState;
        }

        Block oreBlock = BuiltInRegistries.BLOCK.get(oreId);
        return oreBlock == null || oreBlock == Blocks.AIR ? targetState : oreBlock.defaultBlockState();
    }

    private static ItemStack miningResultFor(ServerLevel level, BlockPos targetPos, BlockState targetState) {
        DrillMiningRecipe recipe = DrillMiningRecipes.find(level, harvestCheckState(level, targetPos, targetState));
        if (recipe == null) return ItemStack.EMPTY;
        if (recipe.result().isPresent()) return recipe.result().get().copy();
        if (targetState.is(ModBlocks.ORE_DEPOSIT.get())) {
            return OreDepositData.previewResult(level, targetPos);
        }

        for (ItemStack drop : Block.getDrops(targetState, level, targetPos, level.getBlockEntity(targetPos))) {
            if (!drop.isEmpty()) {
                return drop.copy();
            }
        }

        return new ItemStack(targetState.getBlock().asItem());
    }

    private static DropRange dropRangeFor(ServerLevel level, BlockPos targetPos, BlockState targetState, ItemStack expected) {
        DrillMiningRecipe recipe = DrillMiningRecipes.find(level, harvestCheckState(level, targetPos, targetState));
        if (recipe != null && recipe.result().isPresent()) return new DropRange(expected.copy(), expected.getCount(), expected.getCount());
        ResourceLocation cacheKey = dropRangeCacheKey(level, targetPos, targetState);
        DropRange cached = DROP_RANGE_CACHE.get(cacheKey);
        if (cached != null && sameResult(cached.result(), expected)) {
            DropRange expanded = cached.include(expected);
            if (expanded != cached) {
                DROP_RANGE_CACHE.put(cacheKey, expanded);
            }
            return expanded;
        }

        int min = expected.getCount();
        int max = expected.getCount();
        ItemStack preview = expected.copy();
        preview.setCount(1);

        for (int sample = 0; sample < DROP_RANGE_SAMPLES; sample++) {
            ItemStack sampled = miningResultFor(level, targetPos, targetState);
            if (sampled.isEmpty() || !sameResult(preview, sampled)) {
                continue;
            }

            min = Math.min(min, sampled.getCount());
            max = Math.max(max, sampled.getCount());
        }

        DropRange range = new DropRange(preview, min, max);
        DROP_RANGE_CACHE.put(cacheKey, range);
        return range;
    }

    private static ResourceLocation dropRangeCacheKey(ServerLevel level, BlockPos targetPos, BlockState targetState) {
        if (targetState.is(ModBlocks.ORE_DEPOSIT.get())) {
            ResourceLocation oreId = OreDepositData.oreBlockIdAt(level, targetPos);
            if (oreId != null) {
                return oreId;
            }
        }

        return BuiltInRegistries.BLOCK.getKey(targetState.getBlock());
    }

    private static boolean sameResult(ItemStack first, ItemStack second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }

        ItemStack normalizedFirst = first.copy();
        ItemStack normalizedSecond = second.copy();
        normalizedFirst.setCount(1);
        normalizedSecond.setCount(1);
        return ItemStack.isSameItemSameComponents(normalizedFirst, normalizedSecond);
    }
}

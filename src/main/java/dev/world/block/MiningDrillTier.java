package dev.world.block;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * @param miningDuration progress needed for one mining cycle
 * @param miningAreaMargin how many blocks the mining area reaches past the drill body on every side
 * @param representativeTool the pickaxe this drill tier is equivalent to for harvest-tier checks
 *     (e.g. an ore tagged {@code minecraft:needs_diamond_tool} is skipped by a drill whose
 *     representative tool is an iron pickaxe). Pass {@link ItemStack#EMPTY} for a tier that ignores
 *     tool-tier restrictions entirely.
 * @param progressPerTick progress gained per working tick, so a cycle takes {@code miningDuration /
 *     progressPerTick} ticks on average. Lets a speed like 0.75 ore/s (3 cycles per 80 ticks) be
 *     exact although a single cycle would need a fractional number of ticks.
 */
public record MiningDrillTier(
        int size,
        int miningDuration,
        int oreUnitsPerCycle,
        ItemStack representativeTool,
        int miningAreaMargin,
        int recipeLevel,
        int progressPerTick) {
    private static final double TICKS_PER_SECOND = 20.0D;

    /**
     * Derives the recipe level from the drill size (2 → 1, 3 → 2, up to 5 → 3, larger → 4). Use the
     * canonical constructor to give a new drill an independent recipe level.
     */
    public MiningDrillTier(
            int size,
            int miningDuration,
            int oreUnitsPerCycle,
            ItemStack tool,
            int miningAreaMargin) {
        this(
                size,
                miningDuration,
                oreUnitsPerCycle,
                tool,
                miningAreaMargin,
                size <= 2 ? 1 : size <= 3 ? 2 : size <= 5 ? 3 : 4);
    }

    public MiningDrillTier(
            int size,
            int miningDuration,
            int oreUnitsPerCycle,
            ItemStack tool,
            int miningAreaMargin,
            int recipeLevel) {
        this(size, miningDuration, oreUnitsPerCycle, tool, miningAreaMargin, recipeLevel, 1);
    }

    public MiningDrillTier {
        if (recipeLevel < 1)
            throw new IllegalArgumentException("Drill recipe level must be positive");
        if (miningDuration < 1 || progressPerTick < 1)
            throw new IllegalArgumentException("Drill mining speed must be positive");
    }

    public MiningDrillTier withProgressPerTick(int progressPerTick) {
        return new MiningDrillTier(
                size,
                miningDuration,
                oreUnitsPerCycle,
                representativeTool,
                miningAreaMargin,
                recipeLevel,
                progressPerTick);
    }

    public double oresPerSecond() {
        return oreUnitsPerCycle * progressPerTick * TICKS_PER_SECOND / miningDuration;
    }

    public boolean canHarvest(BlockState state) {
        return representativeTool.isEmpty()
                || !state.requiresCorrectToolForDrops()
                || representativeTool.isCorrectToolForDrops(state);
    }
}

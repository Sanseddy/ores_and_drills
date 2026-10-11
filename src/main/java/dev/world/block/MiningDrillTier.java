package dev.world.block;

import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;

/**
 * @param miningAreaMargin   how many blocks the mining area reaches past the drill body on every side
 * @param representativeTool the pickaxe this drill tier is equivalent to for harvest-tier checks
 *                            (e.g. an ore tagged {@code minecraft:needs_diamond_tool} is skipped by
 *                            a drill whose representative tool is an iron pickaxe). Pass
 *                            {@link ItemStack#EMPTY} for a tier that ignores tool-tier restrictions entirely.
 */
public record MiningDrillTier(
        int size, int miningDuration, int oreUnitsPerCycle, ItemStack representativeTool, int miningAreaMargin, int recipeLevel
) {
    /** Existing drills keep stable levels; future drills can supply an independent recipe level. */
    public MiningDrillTier(int size, int miningDuration, int oreUnitsPerCycle, ItemStack tool, int miningAreaMargin) {
        this(size, miningDuration, oreUnitsPerCycle, tool, miningAreaMargin, size <= 2 ? 1 : size <= 3 ? 2 : size <= 5 ? 3 : 4);
    }

    public MiningDrillTier {
        if (recipeLevel < 1) throw new IllegalArgumentException("Drill recipe level must be positive");
    }

    public boolean canHarvest(BlockState state) {
        return representativeTool.isEmpty() || !state.requiresCorrectToolForDrops() || representativeTool.isCorrectToolForDrops(state);
    }
}

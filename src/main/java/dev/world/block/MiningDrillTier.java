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
        int size, int miningDuration, int oreUnitsPerCycle, ItemStack representativeTool, int miningAreaMargin
) {
    public boolean canHarvest(BlockState state) {
        return representativeTool.isEmpty() || !state.requiresCorrectToolForDrops() || representativeTool.isCorrectToolForDrops(state);
    }
}

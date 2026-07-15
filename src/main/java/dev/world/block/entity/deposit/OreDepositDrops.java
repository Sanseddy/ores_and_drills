package dev.world.block.entity.deposit;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import javax.annotation.Nullable;

public final class OreDepositDrops {
    private OreDepositDrops() {
    }

    public static ItemStack firstDrop(ServerLevel level, BlockPos pos, BlockState oreState) {
        for (ItemStack drop : Block.getDrops(oreState, level, pos, null)) {
            if (!drop.isEmpty()) {
                return drop.copy();
            }
        }

        return ItemStack.EMPTY;
    }

    public static ItemStack firstDrop(ServerLevel level, BlockPos pos, BlockState oreState, @Nullable Entity entity, ItemStack tool) {
        for (ItemStack drop : Block.getDrops(oreState, level, pos, null, entity, tool)) {
            if (!drop.isEmpty()) {
                return drop.copy();
            }
        }

        return ItemStack.EMPTY;
    }
}

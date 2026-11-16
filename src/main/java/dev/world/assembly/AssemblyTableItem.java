package dev.world.assembly;

import net.minecraft.core.BlockPos;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/** Reserves both cells before consuming one table item, rolling back a failed second placement. */
public final class AssemblyTableItem extends BlockItem {
    public AssemblyTableItem(Block block, Properties properties) {
        super(block, properties);
    }

    @Override
    protected boolean placeBlock(BlockPlaceContext context, BlockState state) {
        var level = context.getLevel();
        BlockPos main = context.getClickedPos();
        BlockPos extension = AssemblyBlock.otherPos(main, state);
        if (!AssemblyBlock.canExtend(context, state)) return false;
        BlockState previousMain = level.getBlockState(main);
        BlockState previousExtension = level.getBlockState(extension);
        // Suppress shape propagation until the pair exists. Client updates are still sent.
        int flags = Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE;
        if (!level.setBlock(main, state, flags)) return false;
        if (!level.setBlock(extension, state.setValue(AssemblyBlock.EXTENSION, true), flags)) {
            AssemblyBlock.restorePair(level, main, previousMain, extension, previousExtension);
            return false;
        }
        if (!AssemblyBlock.isComplete(level, main, state)) {
            AssemblyBlock.restorePair(level, main, previousMain, extension, previousExtension);
            return false;
        }
        level.updateNeighborsAt(main, getBlock());
        level.updateNeighborsAt(extension, getBlock());
        state.updateNeighbourShapes(level, main, Block.UPDATE_ALL);
        state.setValue(AssemblyBlock.EXTENSION, true)
                .updateNeighbourShapes(level, extension, Block.UPDATE_ALL);
        return true;
    }
}

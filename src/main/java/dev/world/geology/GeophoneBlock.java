package dev.world.geology;

import com.mojang.serialization.MapCodec;
import java.util.List;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.HorizontalDirectionalBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A surface sensor that records the station's waves at its own position. It needs firm ground to
 * couple to and registers itself in {@link GeologySavedData}, so stations find it without scanning
 * or loading terrain.
 */
public final class GeophoneBlock extends HorizontalDirectionalBlock {
    public static final MapCodec<GeophoneBlock> CODEC = simpleCodec(GeophoneBlock::new);

    private static final VoxelShape SHAPE =
            Shapes.or(
                    Block.box(5.0D, 0.0D, 5.0D, 11.0D, 2.0D, 11.0D),
                    Block.box(6.0D, 2.0D, 6.0D, 10.0D, 9.0D, 10.0D));

    public GeophoneBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends HorizontalDirectionalBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state =
                defaultBlockState()
                        .setValue(FACING, context.getHorizontalDirection().getOpposite());
        return state.canSurvive(context.getLevel(), context.getClickedPos()) ? state : null;
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    protected boolean canSurvive(BlockState state, LevelReader level, BlockPos pos) {
        BlockPos below = pos.below();
        return level.getBlockState(below).isFaceSturdy(level, below, Direction.UP);
    }

    @Override
    protected BlockState updateShape(
            BlockState state,
            Direction direction,
            BlockState neighbour,
            LevelAccessor level,
            BlockPos pos,
            BlockPos neighbourPos) {
        return direction == Direction.DOWN && !state.canSurvive(level, pos)
                ? Blocks.AIR.defaultBlockState()
                : super.updateShape(state, direction, neighbour, level, pos, neighbourPos);
    }

    @Override
    protected void onPlace(
            BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        if (level instanceof ServerLevel serverLevel && !oldState.is(this)) {
            GeologySavedData.get(serverLevel).addGeophone(pos);
        }
    }

    @Override
    protected void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (level instanceof ServerLevel serverLevel && !newState.is(this)) {
            GeologySavedData.get(serverLevel).removeGeophone(pos);
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Override
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(
                Component.translatable("tooltip.ores_and_drills.geophone.desc")
                        .withStyle(ChatFormatting.GRAY));
    }
}

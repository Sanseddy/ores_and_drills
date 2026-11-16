package dev.world.assembly;

import com.mojang.serialization.MapCodec;
import dev.registry.ModAssembly;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityTicker;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;

public final class AssemblyBlock extends BaseEntityBlock {
    public static final MapCodec<AssemblyBlock> CODEC = simpleCodec(AssemblyBlock::new);

    /** The long axis points from the inventory half to the assembly half. */
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public static final BooleanProperty EXTENSION = BooleanProperty.create("extension");
    private static final ThreadLocal<Boolean> RESTORING = ThreadLocal.withInitial(() -> false);

    public AssemblyBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition.any().setValue(FACING, Direction.EAST).setValue(EXTENSION, false));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, EXTENSION);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        BlockState state =
                defaultBlockState()
                        .setValue(FACING, context.getHorizontalDirection().getCounterClockWise());
        return canExtend(context, state) ? state : null;
    }

    public static BlockPos otherPos(BlockPos pos, BlockState state) {
        Direction right = state.getValue(FACING);
        return pos.relative(state.getValue(EXTENSION) ? right.getOpposite() : right);
    }

    public static BlockPos mainPos(BlockPos pos, BlockState state) {
        return state.getValue(EXTENSION) ? otherPos(pos, state) : pos;
    }

    public static boolean isComplete(LevelReader level, BlockPos pos, BlockState state) {
        BlockPos other = otherPos(pos, state);
        if (!level.hasChunkAt(other)) return false;
        BlockState partner = level.getBlockState(other);
        return partner.is(state.getBlock())
                && partner.getValue(EXTENSION) != state.getValue(EXTENSION)
                && partner.getValue(FACING) == state.getValue(FACING);
    }

    public static boolean canExtend(BlockPlaceContext context, BlockState state) {
        Level level = context.getLevel();
        BlockPos other = otherPos(context.getClickedPos(), state);
        Player player = context.getPlayer();
        BlockPlaceContext secondary =
                BlockPlaceContext.at(context, other, context.getClickedFace());
        CollisionContext collision =
                player == null ? CollisionContext.empty() : CollisionContext.of(player);
        return level.getWorldBorder().isWithinBounds(context.getClickedPos())
                && level.hasChunkAt(other)
                && level.getWorldBorder().isWithinBounds(other)
                && !level.isOutsideBuildHeight(other)
                && level.getBlockEntity(other) == null
                && level.getBlockState(other).canBeReplaced(secondary)
                && level.isUnobstructed(state.setValue(EXTENSION, true), other, collision)
                && (player == null
                        || level.mayInteract(player, other)
                                && player.mayUseItemAt(
                                        other, context.getClickedFace(), context.getItemInHand()));
    }

    static void restorePair(
            Level level,
            BlockPos main,
            BlockState mainState,
            BlockPos extension,
            BlockState extensionState) {
        boolean wasRestoring = RESTORING.get();
        RESTORING.set(true);
        try {
            level.setBlock(
                    extension, extensionState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
            level.setBlock(main, mainState, Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE);
        } finally {
            RESTORING.set(wasRestoring);
        }
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        BlockPos main = mainPos(pos, state);
        if (!level.isClientSide
                && level.getBlockEntity(main) instanceof AssemblyBlockEntity table) {
            table.ensureStructure();
            if (isComplete(level, main, table.getBlockState())) player.openMenu(table);
            else
                player.displayClientMessage(
                        net.minecraft.network.chat.Component.translatable(
                                "gui.ores_and_drills.assembly.needs_space"),
                        true);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Override
    public void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState replacement, boolean moved) {
        boolean removing = !state.is(replacement.getBlock());
        if (removing
                && !level.restoringBlockSnapshots
                && !level.isClientSide
                && level.getBlockEntity(pos) instanceof AssemblyBlockEntity table) {
            Containers.dropContents(level, pos, table.inventory());
        }
        super.onRemove(state, level, pos, replacement, moved);
        if (removing && !level.isClientSide && !level.restoringBlockSnapshots && !RESTORING.get()) {
            BlockPos other = otherPos(pos, state);
            BlockState partner = level.getBlockState(other);
            if (partner.is(this)
                    && partner.getValue(EXTENSION) != state.getValue(EXTENSION)
                    && partner.getValue(FACING) == state.getValue(FACING)) {
                if (state.getValue(EXTENSION)) level.destroyBlock(other, true);
                else level.removeBlock(other, false);
            }
        }
    }

    @Override
    public BlockState playerWillDestroy(
            Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide && player.isCreative() && state.getValue(EXTENSION)) {
            BlockPos main = otherPos(pos, state);
            BlockState partner = level.getBlockState(main);
            if (partner.is(this)
                    && !partner.getValue(EXTENSION)
                    && partner.getValue(FACING) == state.getValue(FACING))
                level.destroyBlock(main, false, player);
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return state.getValue(EXTENSION) ? null : new AssemblyBlockEntity(pos, state);
    }

    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(
                        type,
                        ModAssembly.BLOCK_ENTITY.get(),
                        (world, pos, blockState, table) -> table.tick());
    }
}

package dev.world.geology.station;

import com.mojang.serialization.MapCodec;
import dev.compat.sable.SableAssemblyTransfer;
import dev.registry.ModBlockEntities;
import dev.registry.ModBlocks;
import dev.world.geology.UpgradeModuleItem;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
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
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.phys.BlockHitResult;

/** Main block of the 3×3×3 seismic station; the model is drawn by its block entity. */
public final class SeismicStationBlock extends BaseEntityBlock {
    public static final MapCodec<SeismicStationBlock> CODEC = simpleCodec(SeismicStationBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;

    public SeismicStationBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING);
    }

    @Nullable
    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        if (!ModBlocks.SEISMIC_STATION_STRUCTURE.canPlace(
                context.getLevel(), context.getClickedPos())) {
            return null;
        }
        return defaultBlockState().setValue(FACING, context.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(
            Level level,
            BlockPos pos,
            BlockState state,
            @Nullable LivingEntity placer,
            ItemStack stack) {
        if (!level.isClientSide) {
            ModBlocks.SEISMIC_STATION_STRUCTURE.placeParts(level, pos);
        }
    }

    @Override
    public void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston) {
        if (!state.is(newState.getBlock())
                && !level.isClientSide
                && !SableAssemblyTransfer.isMoving(level, pos)) {
            if (level.getBlockEntity(pos) instanceof SeismicStationBlockEntity station
                    && level instanceof net.minecraft.server.level.ServerLevel serverLevel) {
                dev.world.geology.survey.SeismicSurveyService.release(
                        serverLevel, station.stationId());
            }
            ModBlocks.SEISMIC_STATION_STRUCTURE.removeParts(level, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return openMenu(level, pos, player);
    }

    @Override
    protected ItemInteractionResult useItemOn(
            ItemStack stack,
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            InteractionHand hand,
            BlockHitResult hitResult) {
        return installModule(stack, level, pos, player);
    }

    /** Uses an upgrade module on the station whose main block is at {@code origin}. */
    static ItemInteractionResult installModule(
            ItemStack stack, Level level, BlockPos origin, Player player) {
        return UpgradeModuleItem.install(
                stack,
                level,
                origin,
                player,
                level.getBlockEntity(origin) instanceof SeismicStationBlockEntity station
                        ? station
                        : null);
    }

    static InteractionResult openMenu(Level level, BlockPos origin, Player player) {
        if (!level.isClientSide
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(origin) instanceof SeismicStationBlockEntity station) {
            SeismicStationMenu.open(serverPlayer, station);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new SeismicStationBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(
                        type,
                        ModBlockEntities.SEISMIC_STATION.get(),
                        SeismicStationBlockEntity::serverTick);
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        return state.setValue(FACING, rotation.rotate(state.getValue(FACING)));
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        return rotate(state, mirror.getRotation(state.getValue(FACING)));
    }
}

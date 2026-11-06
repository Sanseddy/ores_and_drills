package dev.world.geology.station;

import dev.compat.sable.SableAssemblyTransfer;
import dev.registry.ModBlocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Mirror;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/**
 * One of the 26 secondary blocks of a seismic station. It holds no data: it renders nothing of its
 * own, forwards interaction and energy to the main block and takes the whole station down with it.
 */
public final class SeismicStationPartBlock extends Block {
    public SeismicStationPartBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition
                        .any()
                        .setValue(SeismicStationStructure.OFFSET_X, 0)
                        .setValue(SeismicStationStructure.OFFSET_Y, 0)
                        .setValue(SeismicStationStructure.OFFSET_Z, 0));
    }

    /** A part shows as the station itself. */
    @Override
    public String getDescriptionId() {
        String id = super.getDescriptionId();
        return id.endsWith("_part") ? id.substring(0, id.length() - "_part".length()) : id;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(
                SeismicStationStructure.OFFSET_X,
                SeismicStationStructure.OFFSET_Y,
                SeismicStationStructure.OFFSET_Z);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        // Kept out of chunk meshes, but unlike INVISIBLE it still gets hit particles.
        return RenderShape.ENTITYBLOCK_ANIMATED;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    // Offsets are in world axes, so a body turned by Sable or a structure template must turn them
    // with it; the cube maps onto itself and the part keeps pointing at the main block.
    @Override
    protected BlockState rotate(BlockState state, Rotation rotation) {
        BlockPos turned = relative(state).rotate(rotation);
        return withRelative(state, turned.getX(), turned.getZ());
    }

    @Override
    protected BlockState mirror(BlockState state, Mirror mirror) {
        BlockPos relative = relative(state);
        return switch (mirror) {
            case LEFT_RIGHT -> withRelative(state, relative.getX(), -relative.getZ());
            case FRONT_BACK -> withRelative(state, -relative.getX(), relative.getZ());
            default -> state;
        };
    }

    private static BlockPos relative(BlockState state) {
        return new BlockPos(
                state.getValue(SeismicStationStructure.OFFSET_X) - SeismicStationStructure.MAIN_X,
                0,
                state.getValue(SeismicStationStructure.OFFSET_Z) - SeismicStationStructure.MAIN_Z);
    }

    private static BlockState withRelative(BlockState state, int x, int z) {
        return state.setValue(SeismicStationStructure.OFFSET_X, x + SeismicStationStructure.MAIN_X)
                .setValue(SeismicStationStructure.OFFSET_Z, z + SeismicStationStructure.MAIN_Z);
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return SeismicStationBlock.openMenu(
                level, ModBlocks.SEISMIC_STATION_STRUCTURE.originFromPart(pos, state), player);
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
        return SeismicStationBlock.installModule(
                stack,
                level,
                ModBlocks.SEISMIC_STATION_STRUCTURE.originFromPart(pos, state),
                player);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return new ItemStack(ModBlocks.SEISMIC_STATION_ITEM.get());
    }

    @Override
    public ItemStack getCloneItemStack(
            BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return new ItemStack(ModBlocks.SEISMIC_STATION_ITEM.get());
    }

    /**
     * Breaking any part breaks the main block, which drops the single station item; parts have no
     * loot of their own.
     */
    @Override
    public BlockState playerWillDestroy(
            Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos origin = ModBlocks.SEISMIC_STATION_STRUCTURE.originFromPart(pos, state);
            if (ModBlocks.SEISMIC_STATION_STRUCTURE.isMainBlock(level.getBlockState(origin))) {
                level.destroyBlock(origin, !player.isCreative(), player);
            }
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(
            BlockState state,
            Level level,
            BlockPos pos,
            BlockState newState,
            boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            level.invalidateCapabilities(pos);
            if (!SableAssemblyTransfer.isMoving(level, pos)) {
                BlockPos origin = ModBlocks.SEISMIC_STATION_STRUCTURE.originFromPart(pos, state);
                if (ModBlocks.SEISMIC_STATION_STRUCTURE.isMainBlock(level.getBlockState(origin))) {
                    level.destroyBlock(origin, true);
                }
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    protected void onPlace(
            BlockState state, Level level, BlockPos pos, BlockState oldState, boolean moved) {
        super.onPlace(state, level, pos, oldState, moved);
        level.invalidateCapabilities(pos);
    }
}

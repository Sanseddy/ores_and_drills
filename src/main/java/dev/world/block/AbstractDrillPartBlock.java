package dev.world.block;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
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
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.VoxelShape;

public abstract class AbstractDrillPartBlock extends Block {
    protected final DrillStructure structure;
    protected final DrillMenuOpener menuOpener;

    protected AbstractDrillPartBlock(Properties properties, DrillStructure structure, DrillMenuOpener menuOpener) {
        super(properties);
        this.structure = structure;
        this.menuOpener = menuOpener;
        registerDefaultState(stateDefinition.any()
                .setValue(AbstractDrillBlock.FACING, Direction.NORTH)
                .setValue(structure.offsetXProperty(), 0)
                .setValue(structure.offsetYProperty(), 0)
                .setValue(structure.offsetZProperty(), 1));
    }

    public DrillStructure structure() {
        return structure;
    }

    // Block's constructor calls this from within super(properties), before `structure` is assigned — subclasses must use their own static offset fields here, not `structure`.
    @Override
    protected abstract void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder);

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        return null;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.INVISIBLE;
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return true;
    }

    @Override
    protected VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return collisionShape(state);
    }

    @Override
    protected VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return collisionShape(state);
    }

    @Override
    protected VoxelShape getInteractionShape(BlockState state, BlockGetter level, BlockPos pos) {
        return collisionShape(state);
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        return menuOpener.open(level, structure.originFromPart(pos, state), player);
    }

    @SuppressWarnings("deprecation")
    @Override
    protected ItemInteractionResult useItemOn(ItemStack stack, BlockState state, Level level, BlockPos pos, Player player,
                                              InteractionHand hand, BlockHitResult hitResult) {
        if (!isFluidPort(state)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        net.minecraft.world.level.block.entity.BlockEntity origin = level.getBlockEntity(structure.originFromPart(pos, state));
        if (!(origin instanceof dev.world.block.entity.AbstractEnergyDrillBlockEntity energyDrill)
                || !energyDrill.canInteractWithFluidContainer(stack)) {
            return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
        }

        if (level.isClientSide) {
            return ItemInteractionResult.SUCCESS;
        }

        return energyDrill.interactWithFluidContainer(player, hand)
                ? ItemInteractionResult.CONSUME
                : ItemInteractionResult.CONSUME_PARTIAL;
    }

    @Override
    protected void onPlace(BlockState state, Level level, BlockPos pos, BlockState oldState, boolean movedByPiston) {
        super.onPlace(state, level, pos, oldState, movedByPiston);
        level.invalidateCapabilities(pos);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return structure.cloneItemStack();
    }

    @Override
    public ItemStack getCloneItemStack(BlockState state, HitResult target, LevelReader level, BlockPos pos, Player player) {
        return structure.cloneItemStack();
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!level.isClientSide) {
            BlockPos origin = structure.originFromPart(pos, state);
            BlockState originState = level.getBlockState(origin);
            if (structure.isMainBlock(originState)) {
                level.destroyBlock(origin, !player.isCreative(), player);
            }
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && !level.isClientSide) {
            level.invalidateCapabilities(pos);
            BlockPos origin = structure.originFromPart(pos, state);
            BlockState originState = level.getBlockState(origin);
            if (structure.isMainBlock(originState)) {
                level.destroyBlock(origin, true);
            }
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private VoxelShape collisionShape(BlockState state) {
        return structure.collisionShapeForOffset(
                state.getValue(AbstractDrillBlock.FACING),
                state.getValue(structure.offsetXProperty()),
                state.getValue(structure.offsetYProperty()),
                state.getValue(structure.offsetZProperty())
        );
    }

    private boolean isFluidPort(BlockState state) {
        int size = structure.size();
        int center = size / 2;
        int offsetX = state.getValue(structure.offsetXProperty());
        int offsetY = state.getValue(structure.offsetYProperty());
        int offsetZ = state.getValue(structure.offsetZProperty());
        if (offsetY != 0 || offsetZ != center) {
            return false;
        }

        return offsetX == 0 || offsetX == size - 1;
    }
}

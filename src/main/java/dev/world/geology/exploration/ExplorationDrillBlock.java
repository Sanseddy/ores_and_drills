package dev.world.geology.exploration;

import com.mojang.serialization.MapCodec;
import dev.registry.ModBlockEntities;
import dev.registry.ModDataComponents;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.UpgradeModuleItem;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Containers;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
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
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.block.state.properties.DirectionProperty;
import net.minecraft.world.level.block.state.properties.IntegerProperty;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A one-block core drill on a tripod; {@link #ACTIVE} switches the model's work light and {@link
 * #LEVEL} — the installed upgrade modules — adds the analyser parts to the model.
 */
public final class ExplorationDrillBlock extends BaseEntityBlock {
    public static final MapCodec<ExplorationDrillBlock> CODEC =
            simpleCodec(ExplorationDrillBlock::new);
    public static final DirectionProperty FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final BooleanProperty ACTIVE = BooleanProperty.create("active");
    public static final IntegerProperty LEVEL =
            IntegerProperty.create("level", 0, ScannerUpgrade.MAX_LEVEL);

    /** Installed cave analyser level; adds the analyser's sensor parts to the model. */
    public static final IntegerProperty ANALYZER =
            IntegerProperty.create("analyzer", 0, CaveAnalyzer.MAX_LEVEL);

    private static final VoxelShape SHAPE =
            Shapes.or(
                    Block.box(1.0D, 0.0D, 1.0D, 15.0D, 3.0D, 15.0D),
                    Block.box(4.0D, 3.0D, 4.0D, 12.0D, 16.0D, 12.0D));

    public ExplorationDrillBlock(Properties properties) {
        super(properties);
        registerDefaultState(
                stateDefinition
                        .any()
                        .setValue(FACING, Direction.NORTH)
                        .setValue(ACTIVE, false)
                        .setValue(LEVEL, 0)
                        .setValue(ANALYZER, 0));
    }

    @Override
    protected MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        builder.add(FACING, ACTIVE, LEVEL, ANALYZER);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext context) {
        Integer level = context.getItemInHand().get(ModDataComponents.SCANNER_LEVEL);
        Integer analyzer = context.getItemInHand().get(ModDataComponents.ANALYZER_LEVEL);
        return defaultBlockState()
                .setValue(FACING, context.getHorizontalDirection().getOpposite())
                .setValue(LEVEL, level == null ? 0 : ScannerUpgrade.clamp(level))
                .setValue(ANALYZER, analyzer == null ? 0 : CaveAnalyzer.clamp(analyzer));
    }

    @Override
    protected VoxelShape getShape(
            BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return SHAPE;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    protected InteractionResult useWithoutItem(
            BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hitResult) {
        if (!level.isClientSide
                && player instanceof ServerPlayer serverPlayer
                && level.getBlockEntity(pos) instanceof ExplorationDrillBlockEntity drill) {
            ExplorationDrillMenu.open(serverPlayer, drill);
        }
        return InteractionResult.sidedSuccess(level.isClientSide);
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
        return UpgradeModuleItem.install(
                stack,
                level,
                pos,
                player,
                level.getBlockEntity(pos) instanceof ExplorationDrillBlockEntity drill
                        ? drill
                        : null);
    }

    @Override
    public void onRemove(
            BlockState state, Level level, BlockPos pos, BlockState newState, boolean moved) {
        if (!state.is(newState.getBlock())
                && level.getBlockEntity(pos) instanceof ExplorationDrillBlockEntity drill) {
            Containers.dropContents(level, pos, drill.output());
        }
        super.onRemove(state, level, pos, newState, moved);
    }

    @Nullable
    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ExplorationDrillBlockEntity(pos, state);
    }

    @Nullable
    @Override
    public <T extends BlockEntity> BlockEntityTicker<T> getTicker(
            Level level, BlockState state, BlockEntityType<T> type) {
        return level.isClientSide
                ? null
                : createTickerHelper(
                        type,
                        ModBlockEntities.EXPLORATION_DRILL.get(),
                        ExplorationDrillBlockEntity::serverTick);
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
    public void appendHoverText(
            ItemStack stack,
            Item.TooltipContext context,
            List<Component> tooltip,
            TooltipFlag flag) {
        tooltip.add(
                Component.translatable("tooltip.ores_and_drills.exploration_drill.desc")
                        .withStyle(ChatFormatting.GRAY));
        Integer level = stack.get(ModDataComponents.SCANNER_LEVEL);
        tooltip.add(
                Component.translatable(
                                "tooltip.ores_and_drills.exploration_drill.level",
                                level == null ? 0 : level,
                                ScannerUpgrade.MAX_LEVEL)
                        .withStyle(ChatFormatting.DARK_AQUA));
        Integer analyzer = stack.get(ModDataComponents.ANALYZER_LEVEL);
        tooltip.add(
                Component.translatable(
                                "tooltip.ores_and_drills.equipment.analyzer",
                                analyzer == null ? 0 : analyzer,
                                CaveAnalyzer.MAX_LEVEL)
                        .withStyle(ChatFormatting.DARK_AQUA));
    }
}

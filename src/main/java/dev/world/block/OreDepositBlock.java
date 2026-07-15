package dev.world.block;

import dev.world.level.levelgen.OreDepositData;
import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.Explosion;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BooleanProperty;
import net.minecraft.world.level.material.FluidState;

import java.util.function.BiConsumer;

public class OreDepositBlock extends Block {
    public static final MapCodec<OreDepositBlock> CODEC = simpleCodec(OreDepositBlock::new);

    /**
     * Carries no meaning of its own — it exists purely so re-broadcasting this block's state after a
     * richness change is a REAL state transition. {@code LevelChunk#setBlockState} skips all client
     * render-invalidation ({@code if (blockstate == state) return null;}) when the "new" state is
     * reference-equal to what's already there, which it always is for a property-less block (its
     * {@code defaultBlockState()} is a single cached singleton) — so resending the identical state to
     * force a re-render silently does nothing, on vanilla and Sodium alike. Toggling this property
     * defeats that fast path without giving the block any real per-position blockstate data again.
     */
    public static final BooleanProperty REFRESH = BooleanProperty.create("refresh");

    public OreDepositBlock(Properties properties) {
        super(properties);
        registerDefaultState(stateDefinition.any().setValue(REFRESH, false));
    }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> builder) {
        super.createBlockStateDefinition(builder);
        builder.add(REFRESH);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public RenderShape getRenderShape(BlockState state) {
        return RenderShape.MODEL;
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, Entity entity) {
        return OreDepositData.baseBlockStateAt(level, pos).getSoundType(level, pos, entity);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        float baseProgress = super.getDestroyProgress(state, player, level, pos);
        if (baseProgress <= 0.0F || !(level instanceof Level concreteLevel)) {
            return baseProgress;
        }

        OreDepositData.Visual visual = OreDepositData.visualAt(concreteLevel, pos);
        if (visual == null) {
            return baseProgress;
        }

        return baseProgress * OreDepositMiningSpeed.progressMultiplier(
                visual.richness(),
                OreDepositData.FILL_STAGE_COUNT
        );
    }

    /**
     * Mines one virtual ore unit before vanilla damages the tool. The following
     * {@link #onDestroyedByPlayer} call keeps the physical deposit in place until its stored ore is
     * exhausted, without cancelling NeoForge's {@code BlockEvent.BreakEvent}.
     */
    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!player.isCreative() && level instanceof ServerLevel serverLevel) {
            OreDepositData.mineByPlayerBeforeRemoval(serverLevel, pos, state, player);
            return state;
        }

        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public boolean onDestroyedByPlayer(
            BlockState state,
            Level level,
            BlockPos pos,
            Player player,
            boolean willHarvest,
            FluidState fluid
    ) {
        if (!player.isCreative()) {
            return false;
        }

        return super.onDestroyedByPlayer(state, level, pos, player, willHarvest, fluid);
    }

    @Override
    protected void onExplosionHit(BlockState state, Level level, BlockPos pos, Explosion explosion, BiConsumer<ItemStack, BlockPos> dropConsumer) {
        if (state.isAir()
                || explosion.getBlockInteraction() == Explosion.BlockInteraction.TRIGGER_BLOCK
                || !(level instanceof ServerLevel serverLevel)) {
            return;
        }

        ItemStack mined = OreDepositData.mineOne(serverLevel, pos);
        if (!mined.isEmpty() && state.canDropFromExplosion(level, pos, explosion)) {
            dropConsumer.accept(mined, pos);
        }
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            OreDepositData.remove(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }
}

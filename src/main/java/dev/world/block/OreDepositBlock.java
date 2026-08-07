package dev.world.block;

import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositOrePalette;
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
import net.minecraft.world.phys.HitResult;

import java.util.function.BiConsumer;

public class OreDepositBlock extends Block {
    public static final MapCodec<OreDepositBlock> CODEC = simpleCodec(OreDepositBlock::new);

    /**
     * Kept for serialized-state compatibility. Visual refreshes now use a client render notification;
     * toggling a real state here can make Sable replace a plot block with the backing-world block.
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

    /**
     * A deposit is a visual container for one concrete ore stored in its chunk attachment. Returning
     * that ore here lets Jade/recipe-viewer integrations use the real recipe subject rather than the
     * technical ore_deposit item.
     */
    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return pickedOre(level, pos, super.getCloneItemStack(level, pos, state));
    }

    @Override
    public ItemStack getCloneItemStack(
            BlockState state,
            HitResult target,
            LevelReader level,
            BlockPos pos,
            Player player
    ) {
        return pickedOre(level, pos, super.getCloneItemStack(state, target, level, pos, player));
    }

    private static ItemStack pickedOre(LevelReader level, BlockPos pos, ItemStack fallback) {
        if (level instanceof Level concreteLevel) {
            Block ore = OreDepositData.oreBlockAt(concreteLevel, pos);
            if (ore != null) {
                ItemStack stack = new ItemStack(ore.asItem());
                if (!stack.isEmpty()) {
                    return stack;
                }
            }
        }
        return fallback;
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!(level instanceof Level concreteLevel)) {
            return super.getDestroyProgress(state, player, level, pos);
        }

        Block oreBlock = OreDepositData.oreBlockAt(concreteLevel, pos);
        if (oreBlock == null || oreBlock == this) {
            return super.getDestroyProgress(state, player, level, pos);
        }

        float singleOreProgress = oreBlock.defaultBlockState().getDestroyProgress(player, level, pos);
        // The remaining-ore multiplier is applied through PlayerEvent.BreakSpeed so it
        // composes with tool/enchantment and other-mod speed modifiers on both sides.
        return singleOreProgress;
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
        if (!state.is(newState.getBlock())
                && !newState.is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())
                && level instanceof ServerLevel serverLevel) {
            OreDepositData.remove(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    @Override
    public boolean propagatesSkylightDown(BlockState state, BlockGetter level, BlockPos pos) {
        return false;
    }
}

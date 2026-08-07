package dev.world.block;

import com.mojang.serialization.MapCodec;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelReader;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.List;

/**
 * A spent deposit keeps only the original host-rock identity in the chunk attachment. It behaves as
 * an ordinary stone block and cannot yield any more virtual ore.
 */
public final class ExhaustedOreDepositBlock extends Block {
    public static final MapCodec<ExhaustedOreDepositBlock> CODEC = simpleCodec(ExhaustedOreDepositBlock::new);
    private static final ThreadLocal<HarvestContext> HARVEST_CONTEXT = new ThreadLocal<>();

    public ExhaustedOreDepositBlock(Properties properties) {
        super(properties);
    }

    @Override
    protected MapCodec<? extends Block> codec() {
        return CODEC;
    }

    @Override
    public SoundType getSoundType(BlockState state, LevelReader level, BlockPos pos, Entity entity) {
        return OreDepositData.baseBlockStateAt(level, pos).getSoundType(level, pos, entity);
    }

    @Override
    protected float getDestroyProgress(BlockState state, Player player, BlockGetter level, BlockPos pos) {
        if (!(level instanceof Level concreteLevel)) {
            return super.getDestroyProgress(state, player, level, pos);
        }

        BlockState baseState = OreDepositData.baseBlockStateAt(concreteLevel, pos);
        return baseState.is(this)
                ? super.getDestroyProgress(state, player, level, pos)
                : baseState.getDestroyProgress(player, level, pos);
    }

    @Override
    public ItemStack getCloneItemStack(LevelReader level, BlockPos pos, BlockState state) {
        return pickedBase(level, pos, super.getCloneItemStack(level, pos, state));
    }

    @Override
    public ItemStack getCloneItemStack(
            BlockState state,
            HitResult target,
            LevelReader level,
            BlockPos pos,
            Player player
    ) {
        return pickedBase(level, pos, super.getCloneItemStack(state, target, level, pos, player));
    }

    private static ItemStack pickedBase(LevelReader level, BlockPos pos, ItemStack fallback) {
        Block base = OreDepositData.baseBlockStateAt(level, pos).getBlock();
        ItemStack stack = new ItemStack(base.asItem());
        return stack.isEmpty() ? fallback : stack;
    }

    @Override
    public BlockState playerWillDestroy(Level level, BlockPos pos, BlockState state, Player player) {
        if (!player.isCreative()
                && level instanceof ServerLevel serverLevel
                && state.canHarvestBlock(serverLevel, pos, player)) {
            HARVEST_CONTEXT.set(new HarvestContext(
                    serverLevel,
                    pos.immutable(),
                    OreDepositData.baseBlockStateAt(serverLevel, pos)
            ));
        }
        return super.playerWillDestroy(level, pos, state, player);
    }

    @Override
    public void playerDestroy(
            Level level,
            Player player,
            BlockPos pos,
            BlockState state,
            net.minecraft.world.level.block.entity.BlockEntity blockEntity,
            ItemStack tool
    ) {
        try {
            super.playerDestroy(level, player, pos, state, blockEntity, tool);
        } finally {
            HARVEST_CONTEXT.remove();
        }
    }

    /** Delegates Silk Touch, Fortune and normal stone-to-cobblestone behavior to the saved Base block. */
    @Override
    protected List<ItemStack> getDrops(BlockState state, LootParams.Builder params) {
        ServerLevel level = params.getLevel();
        Vec3 origin = params.getParameter(LootContextParams.ORIGIN);
        BlockPos pos = BlockPos.containing(origin);
        HarvestContext harvest = HARVEST_CONTEXT.get();
        BlockState baseState = harvest != null && harvest.matches(level, pos)
                ? harvest.baseState()
                : OreDepositData.baseBlockStateAt(level, pos);
        return baseState.is(this) ? super.getDrops(state, params) : baseState.getDrops(params);
    }

    @Override
    protected void onRemove(BlockState state, Level level, BlockPos pos, BlockState newState, boolean movedByPiston) {
        if (!state.is(newState.getBlock()) && level instanceof ServerLevel serverLevel) {
            OreDepositData.remove(serverLevel, pos);
        }
        super.onRemove(state, level, pos, newState, movedByPiston);
    }

    private record HarvestContext(ServerLevel level, BlockPos pos, BlockState baseState) {
        private boolean matches(ServerLevel candidateLevel, BlockPos candidatePos) {
            return level == candidateLevel && pos.equals(candidatePos);
        }
    }
}

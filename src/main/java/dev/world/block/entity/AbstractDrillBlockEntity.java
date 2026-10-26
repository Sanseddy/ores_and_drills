package dev.world.block.entity;

import dev.registry.ModBlocks;
import dev.recipe.DrillMiningInput;
import dev.recipe.DrillMiningRecipe;
import dev.recipe.DrillMiningRecipes;
import dev.world.block.AbstractDrillBlock;
import dev.world.block.DrillStructure;
import dev.world.block.MiningDrillTier;
import dev.world.block.entity.drill.DrillBit;
import dev.world.block.entity.drill.DrillClientEffects;
import dev.world.block.entity.drill.MiningFront;
import dev.world.block.entity.drill.OreScanner;
import dev.world.block.entity.drill.PreviewGrid;
import dev.world.block.entity.drill.ResultSlot;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.ItemHandlerHelper;
import net.neoforged.neoforge.items.wrapper.InvWrapper;
import software.bernie.geckolib.animatable.GeoBlockEntity;
import software.bernie.geckolib.animatable.instance.AnimatableInstanceCache;
import software.bernie.geckolib.animation.AnimatableManager;
import software.bernie.geckolib.animation.AnimationController;
import software.bernie.geckolib.animation.PlayState;
import software.bernie.geckolib.animation.RawAnimation;
import software.bernie.geckolib.util.GeckoLibUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import javax.annotation.Nullable;

public abstract class AbstractDrillBlockEntity extends BlockEntity implements GeoBlockEntity, DrillStatusProvider {
    protected static final RawAnimation DRILL_WORK = RawAnimation.begin().thenLoop("drill_work");

    public static final int GUI_ANIMATION_FRAME_TIME = 2;
    public static final int GUI_ANIMATION_FRAME_COUNT = 5;

    protected final MiningDrillTier tier;
    protected final DrillStructure structure;
    protected final AnimatableInstanceCache cache = GeckoLibUtil.createInstanceCache(this);
    protected final SimpleContainer inventory;
    protected final IItemHandler itemHandler;
    protected final PreviewGrid previewGrid;
    protected final ResultSlot outputSlot;
    protected final int outputSlotIndex;
    protected int miningProgress;
    protected int guiAnimationTick;
    protected boolean active;
    /** Turn within the working face, advanced by every mined unit; see {@link MiningFront}. */
    protected int miningFaceTurn;
    /** Ore whose debris the bit throws up; chosen on the server and synced so clients can draw particles. */
    @Nullable
    protected Block miningParticleBlock;

    protected AbstractDrillBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                        MiningDrillTier tier, DrillStructure structure,
                                        int inventorySize, int outputSlotIndex) {
        super(type, pos, state);
        this.tier = tier;
        this.structure = structure;
        this.inventory = new SimpleContainer(inventorySize) {
            @Override
            public boolean canPlaceItem(int slot, ItemStack stack) {
                return AbstractDrillBlockEntity.this.canPlaceItem(slot, stack);
            }

            @Override
            public void setChanged() {
                super.setChanged();
                AbstractDrillBlockEntity.this.setChanged();
            }
        };
        this.itemHandler = new DrillItemHandler(inventory);
        this.previewGrid = new PreviewGrid(defaultPreviewSlots(tier));
        this.outputSlotIndex = outputSlotIndex;
        this.outputSlot = new ResultSlot(inventory, outputSlotIndex);
    }

    protected boolean canPlaceItem(int slot, ItemStack stack) {
        return false;
    }

    private static int defaultPreviewSlots(MiningDrillTier tier) {
        return tier.size() <= 3 ? 6 : 8;
    }

    protected boolean canAutomationInsertItem(int slot, ItemStack stack) {
        return slot != outputSlotIndex;
    }

    protected boolean canAutomationExtractItem(int slot) {
        return slot == outputSlotIndex;
    }

    public SimpleContainer getInventory() {
        return inventory;
    }

    public SimpleContainer getPreviewContainer() {
        return previewGrid.container();
    }

    public IItemHandler getItemHandler() {
        return itemHandler;
    }

    @Override
    public int getMiningProgress() {
        return miningProgress;
    }

    public void setMiningProgress(int miningProgress) {
        this.miningProgress = miningProgress;
    }

    @Override
    public int getMiningDuration() {
        return tier.miningDuration();
    }

    public int getGuiAnimationFrame() {
        return (guiAnimationTick / GUI_ANIMATION_FRAME_TIME) % GUI_ANIMATION_FRAME_COUNT;
    }

    @Override
    public boolean isActive() {
        return active;
    }

    @Override
    public void registerControllers(AnimatableManager.ControllerRegistrar controllers) {
        controllers.add(new AnimationController<>(this, "controller", 0, animationState -> {
            animationState.getController().setAnimationSpeed(active ? 2.0D : 0.0D);
            animationState.setAndContinue(DRILL_WORK);
            return PlayState.CONTINUE;
        }));
    }

    @Override
    public AnimatableInstanceCache getAnimatableInstanceCache() {
        return cache;
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Inventory", Tag.TAG_COMPOUND)) {
            ContainerHelper.loadAllItems(tag.getCompound("Inventory"), inventory.getItems(), registries);
        }
        miningProgress = tag.getInt("MiningProgress");
        guiAnimationTick = tag.getInt("GuiAnimationTick");
        miningFaceTurn = tag.getInt("MiningFaceTurn");
        active = tag.getBoolean("Active");
        miningParticleBlock = tag.contains("MiningParticleBlock", Tag.TAG_STRING)
                ? BuiltInRegistries.BLOCK.getOptional(ResourceLocation.tryParse(tag.getString("MiningParticleBlock"))).orElse(null)
                : null;
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag inventoryTag = new CompoundTag();
        ContainerHelper.saveAllItems(inventoryTag, inventory.getItems(), registries);
        tag.put("Inventory", inventoryTag);
        tag.putInt("MiningProgress", miningProgress);
        tag.putInt("GuiAnimationTick", guiAnimationTick);
        tag.putInt("MiningFaceTurn", miningFaceTurn);
        tag.putBoolean("Active", active);
        if (miningParticleBlock != null) {
            tag.putString("MiningParticleBlock", BuiltInRegistries.BLOCK.getKey(miningParticleBlock).toString());
        }
    }

    /** Where this drill's model bit meets the ground. */
    protected abstract DrillBit drillBit();

    /** Sound looped on clients while the drill works, or {@code null} for a silent drill. */
    @Nullable
    public SoundEvent workSound() {
        return null;
    }

    /** World point where the bit enters the ground; the drill's debris and sounds come from here. */
    public Vec3 bitGroundCenter() {
        BlockState state = getBlockState();
        Direction facing = state.hasProperty(AbstractDrillBlock.FACING) ? state.getValue(AbstractDrillBlock.FACING) : Direction.NORTH;
        return drillBit().groundCenter(worldPosition, facing);
    }

    /** Horizontal radius of the bit at ground level. */
    public double bitRadius() {
        return drillBit().radius();
    }

    /** Debris particles the working bit throws up per tick at full particle settings. */
    public double debrisPerTick() {
        return drillBit().debrisPerTick();
    }

    /** Ore whose debris the bit throws up, or {@code null} before the drill has started on any. */
    @Nullable
    public Block miningParticleBlock() {
        return miningParticleBlock;
    }

    protected static void tickClientEffects(AbstractDrillBlockEntity be) {
        DrillClientEffects.tick(be);
    }

    /** Picks the ore the coming cycle mines first, so clients show its debris; syncs only when it changes. */
    private void refreshMiningParticleBlock(ServerLevel level, List<OreScanner.Target> mineable) {
        for (OreScanner.Target target : cycleOrder(mineable)) {
            if (canMineTarget(target)) {
                Block ore = DrillMiningRecipes.sourceState(level, target.pos()).getBlock();
                if (ore != miningParticleBlock) {
                    miningParticleBlock = ore;
                    syncToClient();
                }
                return;
            }
        }
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveCustomOnly(registries);
    }

    protected void syncToClient() {
        if (level instanceof ServerLevel serverLevel) {
            serverLevel.getChunkSource().blockChanged(worldPosition);
        }
    }

    protected record ScanResult(List<OreScanner.Target> mineable, boolean changed) {
    }

    protected static ScanResult scanAndPreview(ServerLevel level, BlockPos pos, Direction facing, AbstractDrillBlockEntity be) {
        List<OreScanner.Target> targets = OreScanner.scan(level, pos, facing, be.structure, be.tier);
        boolean changed = be.previewGrid.update(targets);
        List<OreScanner.Target> mineable = OreScanner.mineable(
                targets,
                stack -> true
        );
        return new ScanResult(mineable, changed);
    }

    /**
     * Pushes mined items from the player-placed main block, matching Factorio's dedicated output tile. Inventories
     * receive items directly; otherwise one item is placed into the output tile for a conveyor to collect. An item
     * already waiting there blocks further ejection instead of allowing an unlimited pile to accumulate.
     */
    protected static boolean exportOutput(ServerLevel level, BlockPos origin, Direction facing, AbstractDrillBlockEntity be) {
        ItemStack output = be.inventory.getItem(be.outputSlotIndex);
        if (output.isEmpty()) {
            return false;
        }

        Direction outputDirection = facing.getOpposite();
        BlockPos outputPort = origin.relative(outputDirection);
        IItemHandler destination = level.getCapability(
                Capabilities.ItemHandler.BLOCK,
                outputPort,
                outputDirection.getOpposite()
        );
        if (destination != null) {
            ItemStack remainder = ItemHandlerHelper.insertItemStacked(destination, output.copy(), false);
            if (remainder.getCount() == output.getCount()) {
                return false;
            }

            be.inventory.setItem(be.outputSlotIndex, remainder);
            return true;
        }

        AABB waitingArea = new AABB(
                outputPort.getX() + 0.1D, outputPort.getY() - 0.75D, outputPort.getZ() + 0.1D,
                outputPort.getX() + 0.9D, outputPort.getY() + 1.25D, outputPort.getZ() + 0.9D
        );
        if (!level.getEntitiesOfClass(ItemEntity.class, waitingArea, ItemEntity::isAlive).isEmpty()) {
            return false;
        }

        ItemStack ejected = output.copyWithCount(1);
        ItemEntity item = new ItemEntity(
                level,
                outputPort.getX() + 0.5D,
                outputPort.getY() + 0.35D,
                outputPort.getZ() + 0.5D,
                ejected
        );
        item.setDeltaMovement(0.0D, 0.0D, 0.0D);
        item.setDefaultPickUpDelay();
        if (!level.addFreshEntity(item)) {
            return false;
        }

        output.shrink(1);
        be.inventory.setItem(be.outputSlotIndex, output);
        return true;
    }

    protected static boolean canOutputTargets(AbstractDrillBlockEntity be, List<OreScanner.Target> mineable) {
        for (OreScanner.Target target : mineable) {
            if (be.canMineTarget(target)
                    && be.outputSlot.canAccept(be.cycleOutput(maxExpectedDrop(target)))) {
                return true;
            }
        }
        return false;
    }

    protected static boolean advanceMining(AbstractDrillBlockEntity be, Level level, boolean poweredAndMining, List<OreScanner.Target> mineable) {
        if (poweredAndMining) {
            if (be.miningProgress == 0 && level instanceof ServerLevel serverLevel) {
                be.refreshMiningParticleBlock(serverLevel, mineable);
            }
            be.miningProgress += be.tier.progressPerTick();
            be.guiAnimationTick++;
            if (be.miningProgress >= be.tier.miningDuration()) {
                if (!canOutputTargets(be, mineable)) {
                    be.miningProgress = Math.max(0, be.tier.miningDuration() - 1);
                    return true;
                }

                int minedUnits = mineCycle(be, level, mineable);
                if (minedUnits == 0) {
                    be.miningProgress = Math.max(0, be.tier.miningDuration() - 1);
                    return true;
                }
                be.onMiningCycleCompleted();
                // Overshoot carries into the next cycle so fractional cycle lengths average out exactly.
                be.miningProgress -= be.tier.miningDuration();
            }
            return true;
        }

        if (!mineable.isEmpty() && be.miningProgress >= be.tier.miningDuration()) {
            be.miningProgress = Math.max(0, be.tier.miningDuration() - 1);
            return true;
        }

        if (mineable.isEmpty() && be.miningProgress != 0) {
            be.miningProgress = 0;
            return true;
        }

        return false;
    }

    /**
     * Targets in the order this cycle tries them, taking turns across the working face: every block of the
     * lowest funnel stage that still holds ore. The scanner orders targets by stage, so the funnel starts as the
     * single top centre block and only widens once everything inside it is worked out.
     */
    private List<OreScanner.Target> cycleOrder(List<OreScanner.Target> mineable) {
        int faceSize = MiningFront.currentStageSize(mineable, OreScanner.Target::funnelStage);
        return MiningFront.order(mineable, faceSize, miningFaceTurn);
    }

    private static int mineCycle(AbstractDrillBlockEntity be, Level level, List<OreScanner.Target> mineable) {
        int minedUnits = mineCycleInOrder(be, level, be.cycleOrder(mineable));
        be.miningFaceTurn += minedUnits;
        return minedUnits;
    }

    private static int mineCycleInOrder(AbstractDrillBlockEntity be, Level level, List<OreScanner.Target> mineable) {
        int minedUnits = 0;
        Set<DrillMiningRecipe> paidRecipes = new HashSet<>();
        List<TargetCursor> cursors = targetCursors(mineable);
        while (minedUnits < be.tier.oreUnitsPerCycle() && hasRemainingTarget(cursors)) {
            int minedThisPass = 0;
            for (TargetCursor cursor : cursors) {
                if (minedUnits >= be.tier.oreUnitsPerCycle()) {
                    break;
                }
                if (cursor.remaining() <= 0) {
                    continue;
                }

                OreScanner.Target target = cursor.target();
                if (!(level instanceof ServerLevel serverLevel)) return minedUnits;
                BlockState source = DrillMiningRecipes.sourceState(serverLevel, target.pos());
                DrillMiningRecipe recipe = DrillMiningRecipes.find(serverLevel, source);
                if (!be.meetsRecipeConditions(recipe, source, target.pos())
                        || !paidRecipes.contains(recipe) && !be.hasMiningFluid(recipe.fluid().orElse(null))) {
                    continue;
                }
                ItemStack expectedOutput = be.cycleOutput(maxExpectedDrop(target));
                if (!be.outputSlot.canAccept(expectedOutput)) {
                    continue;
                }

                ItemStack mined = mineTarget(be, level, target, recipe);
                if (mined.isEmpty()) {
                    cursor.exhaust();
                    continue;
                }

                ItemStack output = be.cycleOutput(mined);
                if (!be.outputSlot.canAccept(output)) {
                    cursor.exhaust();
                    continue;
                }

                be.outputSlot.insert(output);
                if (paidRecipes.add(recipe)) be.consumeMiningFluid(recipe.fluid().orElse(null));
                cursor.consumeOne();
                minedUnits++;
                minedThisPass++;
            }

            if (minedThisPass == 0) {
                break;
            }
        }
        return minedUnits;
    }

    private static List<TargetCursor> targetCursors(List<OreScanner.Target> mineable) {
        List<TargetCursor> cursors = new ArrayList<>(mineable.size());
        for (OreScanner.Target target : mineable) {
            int remaining = Math.max(0, target.remainingInVein());
            if (remaining > 0) {
                cursors.add(new TargetCursor(target, remaining));
            }
        }
        return cursors;
    }

    private static boolean hasRemainingTarget(List<TargetCursor> cursors) {
        for (TargetCursor cursor : cursors) {
            if (cursor.remaining() > 0) {
                return true;
            }
        }
        return false;
    }

    private static ItemStack maxExpectedDrop(OreScanner.Target target) {
        ItemStack stack = target.result().copy();
        stack.setCount(Math.max(1, target.maxCount()));
        return stack;
    }

    private static ItemStack mineTarget(AbstractDrillBlockEntity be, Level level, OreScanner.Target target, DrillMiningRecipe recipe) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockState(target.pos()).is(ModBlocks.ORE_DEPOSIT.get())) {
            return OreDepositData.mineOneIfAccepted(
                    serverLevel,
                    target.pos(),
                    recipe.result().orElse(null),
                    stack -> be.outputSlot.canAccept(be.cycleOutput(stack))
            );
        }

        if (!be.outputSlot.canAccept(be.cycleOutput(target.result()))) {
            return ItemStack.EMPTY;
        }
        if (!level.setBlock(target.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL)) return ItemStack.EMPTY;
        return target.result();
    }

    private boolean canMineTarget(OreScanner.Target target) {
        if (!(level instanceof ServerLevel serverLevel)) return false;
        BlockState source = DrillMiningRecipes.sourceState(serverLevel, target.pos());
        DrillMiningRecipe recipe = DrillMiningRecipes.find(serverLevel, source);
        return meetsRecipeConditions(recipe, source, target.pos()) && hasMiningFluid(recipe.fluid().orElse(null));
    }

    private boolean meetsRecipeConditions(DrillMiningRecipe recipe, BlockState source, BlockPos orePos) {
        return recipe != null && level instanceof ServerLevel serverLevel && tier.canHarvest(source)
                && recipe.matches(new DrillMiningInput(source, worldPosition, orePos, tier.recipeLevel()), serverLevel);
    }

    protected boolean hasMiningFluid(dev.recipe.DrillMiningFluid required) { return required == null; }
    protected boolean consumeMiningFluid(dev.recipe.DrillMiningFluid required) { return required == null; }

    /**
     * How many copies of each mined unit the cycle in progress outputs. Drills with productivity add the bonus
     * copies this cycle earns, so the whole cycle result is repeated for free without depleting the deposit.
     */
    protected int cycleOutputMultiplier() {
        return 1;
    }

    /** Called once after a cycle has mined at least one unit and placed its result in the output. */
    protected void onMiningCycleCompleted() {
    }

    private ItemStack cycleOutput(ItemStack mined) {
        return mined.copyWithCount(mined.getCount() * cycleOutputMultiplier());
    }

    private static final class TargetCursor {
        private final OreScanner.Target target;
        private int remaining;

        private TargetCursor(OreScanner.Target target, int remaining) {
            this.target = target;
            this.remaining = remaining;
        }

        private OreScanner.Target target() {
            return target;
        }

        private int remaining() {
            return remaining;
        }

        private void consumeOne() {
            remaining = Math.max(0, remaining - 1);
        }

        private void exhaust() {
            remaining = 0;
        }
    }

    protected static void updateActiveAndCheckStructure(AbstractDrillBlockEntity be, Level level, BlockPos pos, Direction facing,
                                                          boolean active) {
        if (be.active != active) {
            be.active = active;
            be.syncToClient();
        }

        if (level.getGameTime() % 5L == 0L && !be.structure.isComplete(level, pos, facing)) {
            level.destroyBlock(pos, true);
        }
    }

    private final class DrillItemHandler extends InvWrapper {
        private DrillItemHandler(SimpleContainer inventory) {
            super(inventory);
        }

        @Override
        public ItemStack insertItem(int slot, ItemStack stack, boolean simulate) {
            if (!canAutomationInsertItem(slot, stack)) {
                return stack;
            }

            return super.insertItem(slot, stack, simulate);
        }

        @Override
        public ItemStack extractItem(int slot, int amount, boolean simulate) {
            if (!canAutomationExtractItem(slot)) {
                return ItemStack.EMPTY;
            }

            return super.extractItem(slot, amount, simulate);
        }
    }
}

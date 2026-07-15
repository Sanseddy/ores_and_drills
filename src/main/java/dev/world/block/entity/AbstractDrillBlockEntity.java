package dev.world.block.entity;

import dev.registry.ModBlocks;
import dev.world.block.DrillStructure;
import dev.world.block.MiningDrillTier;
import dev.world.block.entity.drill.OreScanner;
import dev.world.block.entity.drill.PreviewGrid;
import dev.world.block.entity.drill.ResultSlot;
import dev.world.level.levelgen.OreDepositData;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.items.IItemHandler;
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
        active = tag.getBoolean("Active");
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        CompoundTag inventoryTag = new CompoundTag();
        ContainerHelper.saveAllItems(inventoryTag, inventory.getItems(), registries);
        tag.put("Inventory", inventoryTag);
        tag.putInt("MiningProgress", miningProgress);
        tag.putInt("GuiAnimationTick", guiAnimationTick);
        tag.putBoolean("Active", active);
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

    protected static boolean canOutputTargets(AbstractDrillBlockEntity be, List<OreScanner.Target> mineable) {
        for (OreScanner.Target target : mineable) {
            if (be.outputSlot.canAccept(be.outputStackForInsertion(maxExpectedDrop(target)))) {
                return true;
            }
        }
        return false;
    }

    protected static boolean advanceMining(AbstractDrillBlockEntity be, Level level, boolean poweredAndMining, List<OreScanner.Target> mineable) {
        if (poweredAndMining) {
            be.miningProgress++;
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
                be.miningProgress = 0;
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

    private static int mineCycle(AbstractDrillBlockEntity be, Level level, List<OreScanner.Target> mineable) {
        int minedUnits = 0;
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
                ItemStack expectedOutput = be.outputStackForInsertion(maxExpectedDrop(target));
                if (!be.outputSlot.canAccept(expectedOutput)) {
                    continue;
                }

                ItemStack mined = mineTarget(be, level, target);
                if (mined.isEmpty()) {
                    cursor.exhaust();
                    continue;
                }

                ItemStack output = be.outputStackForInsertion(mined);
                if (!be.outputSlot.canAccept(output)) {
                    cursor.exhaust();
                    continue;
                }

                be.outputSlot.insert(output);
                be.onOutputInserted(mined);
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

    private static ItemStack mineTarget(AbstractDrillBlockEntity be, Level level, OreScanner.Target target) {
        if (level instanceof ServerLevel serverLevel
                && level.getBlockState(target.pos()).is(ModBlocks.ORE_DEPOSIT.get())) {
            return OreDepositData.mineOneIfAccepted(
                    serverLevel,
                    target.pos(),
                    stack -> be.outputSlot.canAccept(be.outputStackForInsertion(stack))
            );
        }

        if (!be.outputSlot.canAccept(be.outputStackForInsertion(target.result()))) {
            return ItemStack.EMPTY;
        }
        level.setBlock(target.pos(), Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        return target.result();
    }

    protected ItemStack outputStackForInsertion(ItemStack stack) {
        return stack.copy();
    }

    protected void onOutputInserted(ItemStack stack) {
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

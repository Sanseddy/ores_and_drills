package dev.world.block.entity;

import dev.world.block.AbstractDrillBlock;
import dev.world.block.DrillStructure;
import dev.world.block.EnergyProfile;
import dev.world.block.MiningDrillTier;
import dev.world.block.entity.drill.EnergyBuffer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BucketItem;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.common.SoundActions;
import net.neoforged.neoforge.energy.IEnergyStorage;
import net.neoforged.neoforge.fluids.FluidActionResult;
import net.neoforged.neoforge.fluids.FluidStack;
import net.neoforged.neoforge.fluids.FluidType;
import net.neoforged.neoforge.fluids.FluidUtil;
import net.neoforged.neoforge.fluids.capability.IFluidHandler;
import net.neoforged.neoforge.fluids.capability.templates.FluidTank;

public abstract class AbstractEnergyDrillBlockEntity extends AbstractDrillBlockEntity {
    private static final int ENERGY_ITEM_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;

    protected final EnergyProfile energyProfile;
    protected final EnergyBuffer energyBuffer;
    protected final int productivityPercent;
    protected final FluidTank[] fluidTanks;
    protected final IFluidHandler fluidHandler;
    protected int productivityProgress;

    protected AbstractEnergyDrillBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                              MiningDrillTier tier, EnergyProfile energyProfile, DrillStructure structure) {
        this(type, pos, state, tier, energyProfile, structure, 0, 0);
    }

    protected AbstractEnergyDrillBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state,
                                              MiningDrillTier tier, EnergyProfile energyProfile, DrillStructure structure,
                                              int productivityPercent, int fluidCapacity) {
        super(type, pos, state, tier, structure, 2, OUTPUT_SLOT);
        this.energyProfile = energyProfile;
        this.energyBuffer = new EnergyBuffer(energyProfile.capacity(), energyProfile.capacity(), this::setChanged);
        this.productivityPercent = productivityPercent;
        this.fluidTanks = fluidCapacity > 0 ? new FluidTank[]{new InputFluidTank(fluidCapacity), new InputFluidTank(fluidCapacity)} : new FluidTank[0];
        this.fluidHandler = fluidCapacity > 0 ? new RoutedFluidHandler(false) : null;
    }

    @Override
    protected boolean canPlaceItem(int slot, ItemStack stack) {
        return slot == ENERGY_ITEM_SLOT && energyStorage(stack) != null;
    }

    @Override
    protected boolean canAutomationInsertItem(int slot, ItemStack stack) {
        return slot != ENERGY_ITEM_SLOT && super.canAutomationInsertItem(slot, stack);
    }

    public IEnergyStorage getEnergyStorage() {
        return energyBuffer;
    }

    public IFluidHandler getFluidHandler() {
        return fluidHandler;
    }

    public boolean hasFluidHandler() {
        return fluidHandler != null;
    }

    public boolean canInteractWithFluidContainer(ItemStack stack) {
        return fluidHandler != null && !stack.isEmpty()
                && (stack.is(Items.BUCKET) || stack.getCapability(Capabilities.FluidHandler.ITEM) != null);
    }

    @SuppressWarnings("deprecation")
    public boolean interactWithFluidContainer(Player player, InteractionHand hand) {
        if (!canInteractWithFluidContainer(player.getItemInHand(hand))) {
            return false;
        }

        if (FluidUtil.interactWithFluidHandler(player, hand, new RoutedFluidHandler(true))) {
            return true;
        }

        return interactWithVanillaBucket(player, hand);
    }

    public ItemStack interactWithFluidContainerItem(ItemStack carried, Player player) {
        if (carried.getCount() != 1 || !canInteractWithFluidContainer(carried)) {
            return carried;
        }

        RoutedFluidHandler routed = new RoutedFluidHandler(true);

        FluidActionResult fillResult = FluidUtil.tryFillContainer(carried, routed, FluidType.BUCKET_VOLUME, player, true);
        if (fillResult.isSuccess()) {
            return fillResult.getResult();
        }

        FluidActionResult drainResult = FluidUtil.tryEmptyContainer(carried, routed, FluidType.BUCKET_VOLUME, player, true);
        if (drainResult.isSuccess()) {
            return drainResult.getResult();
        }

        return carried;
    }

    public int getEnergyStored() {
        return energyBuffer.getEnergyStored();
    }

    public int getEnergyCapacity() {
        return energyBuffer.getMaxEnergyStored();
    }

    public int getFluidAmount(int tank) {
        return tank >= 0 && tank < fluidTanks.length ? fluidTanks[tank].getFluidAmount() : 0;
    }

    public int getFluidCapacity(int tank) {
        return tank >= 0 && tank < fluidTanks.length ? fluidTanks[tank].getCapacity() : 0;
    }

    public FluidStack getFluidForRender(int tank) {
        return tank >= 0 && tank < fluidTanks.length ? fluidTanks[tank].getFluid() : FluidStack.EMPTY;
    }

    public int getProductivityProgress() {
        return productivityProgress;
    }

    public int getProductivityPercent() {
        return productivityPercent;
    }

    public int getVisibleProductivityProgress() {
        if (productivityPercent <= 0) {
            return 0;
        }

        if (miningProgress <= 0 || getMiningDuration() <= 0) {
            return productivityProgress;
        }

        int cycleTarget = Math.min(100, productivityProgress + productivityPercent);
        int currentCycleProgress = (cycleTarget - productivityProgress) * miningProgress / getMiningDuration();
        return Math.min(99, productivityProgress + currentCycleProgress);
    }

    protected boolean hasFluid(FluidStack required) {
        if (required.isEmpty()) {
            return true;
        }

        int tank = findTankContaining(required);
        return tank >= 0 && fluidTanks[tank].getFluidAmount() >= required.getAmount();
    }

    protected boolean consumeFluid(FluidStack required) {
        if (required.isEmpty()) {
            return true;
        }

        int tank = findTankContaining(required);
        if (tank < 0 || fluidTanks[tank].getFluidAmount() < required.getAmount()) {
            return false;
        }

        int remaining = fluidTanks[tank].getFluidAmount() - required.getAmount();
        fluidTanks[tank].setFluid(remaining > 0 ? fluidTanks[tank].getFluid().copyWithAmount(remaining) : FluidStack.EMPTY);
        return true;
    }

    protected static void tickEnergy(Level level, BlockPos pos, BlockState state, AbstractEnergyDrillBlockEntity be, Block expectedBlock) {
        if (level.isClientSide || !state.is(expectedBlock)) {
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        Direction facing = state.getValue(AbstractDrillBlock.FACING);
        ScanResult scan = scanAndPreview(serverLevel, pos, facing, be);
        boolean canMine = !scan.mineable().isEmpty();
        boolean outputReady = canOutputTargets(be, scan.mineable());
        int cost = be.energyProfile.energyPerTick();
        boolean changed = scan.changed();
        changed |= be.chargeFromEnergyItem();
        changed |= be.pullFluidsFromInputs(serverLevel, pos, facing);
        boolean poweredAndMining = canMine && outputReady && be.energyBuffer.hasEnergy(cost);

        if (poweredAndMining) {
            be.energyBuffer.consume(cost);
        }

        changed |= advanceMining(be, level, poweredAndMining, scan.mineable());
        updateActiveAndCheckStructure(be, level, pos, facing, poweredAndMining);

        if (changed) {
            be.setChanged();
        }
    }

    private boolean chargeFromEnergyItem() {
        ItemStack stack = inventory.getItem(ENERGY_ITEM_SLOT);
        IEnergyStorage itemEnergy = energyStorage(stack);
        if (itemEnergy == null || !itemEnergy.canExtract() || !energyBuffer.canReceive()) {
            return false;
        }

        int space = energyBuffer.getMaxEnergyStored() - energyBuffer.getEnergyStored();
        if (space <= 0) {
            return false;
        }

        int extracted = itemEnergy.extractEnergy(space, true);
        if (extracted <= 0) {
            return false;
        }

        int received = energyBuffer.receiveEnergy(extracted, false);
        if (received <= 0) {
            return false;
        }

        itemEnergy.extractEnergy(received, false);
        inventory.setChanged();
        return true;
    }

    private boolean pullFluidsFromInputs(ServerLevel level, BlockPos origin, Direction facing) {
        if (fluidHandler == null) {
            return false;
        }

        int size = structure.size();
        int center = size / 2;
        Direction right = facing.getClockWise();
        boolean changed = false;

        changed |= pullFluidFromInput(level, origin, facing, 0, 0, center, right.getOpposite());
        changed |= pullFluidFromInput(level, origin, facing, size - 1, 0, center, right);

        return changed;
    }

    private boolean pullFluidFromInput(ServerLevel level, BlockPos origin, Direction facing,
                                       int offsetX, int offsetY, int offsetZ, Direction externalDirection) {
        BlockPos portPos = structure.offset(origin, facing, offsetX, offsetY, offsetZ);
        BlockPos sourcePos = portPos.relative(externalDirection);
        IFluidHandler source = level.getCapability(
                Capabilities.FluidHandler.BLOCK,
                sourcePos,
                externalDirection.getOpposite()
        );
        if (source == null) {
            return false;
        }

        return !FluidUtil.tryFluidTransfer(fluidHandler, source, FluidType.BUCKET_VOLUME, true).isEmpty();
    }

    private static IEnergyStorage energyStorage(ItemStack stack) {
        return stack.isEmpty() ? null : stack.getCapability(Capabilities.EnergyStorage.ITEM);
    }

    private int findTankForFill(FluidStack resource) {
        int matchingTank = findTankContaining(resource);
        if (matchingTank >= 0) {
            return matchingTank;
        }

        for (int tank = 0; tank < fluidTanks.length; tank++) {
            if (fluidTanks[tank].isEmpty()) {
                return tank;
            }
        }

        return -1;
    }

    private int findTankContaining(FluidStack resource) {
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            FluidStack stored = fluidTanks[tank].getFluid();
            if (!stored.isEmpty() && FluidStack.isSameFluidSameComponents(stored, resource)) {
                return tank;
            }
        }

        return -1;
    }

    private FluidStack drainFromTank(int tank, int amount, IFluidHandler.FluidAction action) {
        if (tank < 0 || tank >= fluidTanks.length || amount <= 0 || fluidTanks[tank].isEmpty()) {
            return FluidStack.EMPTY;
        }

        int drained = Math.min(amount, fluidTanks[tank].getFluidAmount());
        FluidStack result = fluidTanks[tank].getFluid().copyWithAmount(drained);
        if (action.execute()) {
            int remaining = fluidTanks[tank].getFluidAmount() - drained;
            fluidTanks[tank].setFluid(remaining > 0 ? fluidTanks[tank].getFluid().copyWithAmount(remaining) : FluidStack.EMPTY);
        }

        return result;
    }

    private boolean interactWithVanillaBucket(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(Items.BUCKET)) {
            return fillBucketFromTank(player, hand, held);
        }

        if (held.getItem() instanceof BucketItem bucket && bucket.content != net.minecraft.world.level.material.Fluids.EMPTY) {
            return emptyBucketIntoTank(player, hand, held, bucket);
        }

        return false;
    }

    private boolean fillBucketFromTank(Player player, InteractionHand hand, ItemStack held) {
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            FluidStack simulated = drainFromTank(tank, FluidType.BUCKET_VOLUME, IFluidHandler.FluidAction.SIMULATE);
            if (simulated.getAmount() < FluidType.BUCKET_VOLUME) {
                continue;
            }

            ItemStack filledBucket = FluidUtil.getFilledBucket(simulated);
            if (filledBucket.isEmpty()) {
                continue;
            }

            drainFromTank(tank, FluidType.BUCKET_VOLUME, IFluidHandler.FluidAction.EXECUTE);
            if (!player.getAbilities().instabuild) {
                player.setItemInHand(hand, ItemUtils.createFilledResult(held, player, filledBucket));
            }
            playBucketSound(player, simulated, SoundActions.BUCKET_FILL);
            return true;
        }

        return false;
    }

    private boolean emptyBucketIntoTank(Player player, InteractionHand hand, ItemStack held, BucketItem bucket) {
        FluidStack fluid = new FluidStack(bucket.content, FluidType.BUCKET_VOLUME);
        if (fluidHandler.fill(fluid, IFluidHandler.FluidAction.SIMULATE) < FluidType.BUCKET_VOLUME) {
            return false;
        }

        fluidHandler.fill(fluid, IFluidHandler.FluidAction.EXECUTE);
        if (!player.getAbilities().instabuild) {
            player.setItemInHand(hand, new ItemStack(Items.BUCKET));
        }
        playBucketSound(player, fluid, SoundActions.BUCKET_EMPTY);
        return true;
    }

    private void playBucketSound(Player player, FluidStack fluid, net.neoforged.neoforge.common.SoundAction action) {
        SoundEvent sound = fluid.getFluidType().getSound(fluid, action);
        if (sound != null && level != null) {
            level.playSound(null, player.blockPosition(), sound, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        if (tag.contains("Energy")) {
            energyBuffer.deserializeNBT(registries, tag.get("Energy"));
        }
        productivityProgress = tag.getInt("ProductivityProgress");
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            String key = "FluidTank" + tank;
            if (tag.contains(key)) {
                fluidTanks[tank].readFromNBT(registries, tag.getCompound(key));
            }
        }
        if (fluidTanks.length > 0 && tag.contains("FluidTank") && !tag.contains("FluidTank0")) {
            fluidTanks[0].readFromNBT(registries, tag.getCompound("FluidTank"));
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyBuffer.serializeNBT(registries));
        tag.putInt("ProductivityProgress", productivityProgress);
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            tag.put("FluidTank" + tank, fluidTanks[tank].writeToNBT(registries, new CompoundTag()));
        }
    }

    @Override
    protected ItemStack outputStackForInsertion(ItemStack stack) {
        if (productivityPercent <= 0 || stack.isEmpty()) {
            return stack.copy();
        }

        int accumulated = productivityProgress + stack.getCount() * productivityPercent;
        ItemStack result = stack.copy();
        result.grow(accumulated / 100);
        return result;
    }

    @Override
    protected void onOutputInserted(ItemStack stack) {
        if (productivityPercent <= 0 || stack.isEmpty()) {
            return;
        }

        productivityProgress = (productivityProgress + stack.getCount() * productivityPercent) % 100;
        setChanged();
    }

    private final class InputFluidTank extends FluidTank {
        private InputFluidTank(int capacity) {
            super(capacity);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            return FluidStack.EMPTY;
        }

        @Override
        protected void onContentsChanged() {
            AbstractEnergyDrillBlockEntity.this.setChanged();
        }
    }

    private final class RoutedFluidHandler implements IFluidHandler {
        private final boolean allowDrain;

        private RoutedFluidHandler(boolean allowDrain) {
            this.allowDrain = allowDrain;
        }

        @Override
        public int getTanks() {
            return fluidTanks.length;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return tank >= 0 && tank < fluidTanks.length ? fluidTanks[tank].getFluid() : FluidStack.EMPTY;
        }

        @Override
        public int getTankCapacity(int tank) {
            return tank >= 0 && tank < fluidTanks.length ? fluidTanks[tank].getCapacity() : 0;
        }

        @Override
        public boolean isFluidValid(int tank, FluidStack stack) {
            if (stack.isEmpty() || tank < 0 || tank >= fluidTanks.length) {
                return false;
            }

            FluidStack stored = fluidTanks[tank].getFluid();
            if (!stored.isEmpty()) {
                return FluidStack.isSameFluidSameComponents(stored, stack);
            }

            return findTankContaining(stack) < 0;
        }

        @Override
        public int fill(FluidStack resource, FluidAction action) {
            if (resource.isEmpty()) {
                return 0;
            }

            int tank = findTankForFill(resource);
            if (tank < 0) {
                return 0;
            }

            return fluidTanks[tank].fill(resource, action);
        }

        @Override
        public FluidStack drain(FluidStack resource, FluidAction action) {
            if (!allowDrain || resource.isEmpty()) {
                return FluidStack.EMPTY;
            }

            int tank = findTankContaining(resource);
            return tank < 0 ? FluidStack.EMPTY : drainFromTank(tank, resource.getAmount(), action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (!allowDrain || maxDrain <= 0) {
                return FluidStack.EMPTY;
            }

            for (int tank = 0; tank < fluidTanks.length; tank++) {
                if (!fluidTanks[tank].isEmpty()) {
                    return drainFromTank(tank, maxDrain, action);
                }
            }

            return FluidStack.EMPTY;
        }
    }
}

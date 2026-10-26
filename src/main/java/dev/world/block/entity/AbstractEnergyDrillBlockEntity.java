package dev.world.block.entity;

import dev.world.block.AbstractDrillBlock;
import dev.world.block.AbstractDrillPartBlock;
import dev.world.block.DrillStructure;
import dev.world.block.EnergyProfile;
import dev.world.block.MiningDrillTier;
import dev.world.block.entity.drill.EnergyBuffer;
import dev.world.block.entity.drill.ProductivityBonus;
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
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.ItemUtils;
import net.minecraft.world.item.Items;
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
    protected final ProductivityBonus productivity;
    protected final FluidTank[] fluidTanks;
    protected final IFluidHandler fluidHandler;

    protected AbstractEnergyDrillBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            MiningDrillTier tier,
            EnergyProfile energyProfile,
            DrillStructure structure) {
        this(type, pos, state, tier, energyProfile, structure, 0, 0);
    }

    protected AbstractEnergyDrillBlockEntity(
            BlockEntityType<?> type,
            BlockPos pos,
            BlockState state,
            MiningDrillTier tier,
            EnergyProfile energyProfile,
            DrillStructure structure,
            int productivityPercent,
            int fluidCapacity) {
        super(type, pos, state, tier, structure, 2, OUTPUT_SLOT);
        this.energyProfile = energyProfile;
        this.energyBuffer =
                new EnergyBuffer(
                        energyProfile.capacity(), energyProfile.capacity(), this::setChanged);
        this.productivity = new ProductivityBonus(productivityPercent);
        this.fluidTanks =
                fluidCapacity > 0
                        ? new FluidTank[] {
                            new DrillFluidTank(fluidCapacity), new DrillFluidTank(fluidCapacity)
                        }
                        : new FluidTank[0];
        this.fluidHandler = fluidCapacity > 0 ? new RoutedFluidHandler() : null;
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
        return fluidHandler != null
                && !stack.isEmpty()
                && (stack.is(Items.BUCKET)
                        || stack.getCapability(Capabilities.FluidHandler.ITEM) != null);
    }

    @SuppressWarnings("deprecation")
    public boolean interactWithFluidContainer(Player player, InteractionHand hand) {
        if (!canInteractWithFluidContainer(player.getItemInHand(hand))) {
            return false;
        }

        if (FluidUtil.interactWithFluidHandler(player, hand, fluidHandler)) {
            return true;
        }

        return interactWithVanillaBucket(player, hand);
    }

    public ItemStack interactWithFluidContainerItem(ItemStack carried, Player player) {
        if (carried.getCount() != 1 || !canInteractWithFluidContainer(carried)) {
            return carried;
        }

        IFluidHandler routed = fluidHandler;

        FluidActionResult fillResult =
                FluidUtil.tryFillContainer(carried, routed, FluidType.BUCKET_VOLUME, player, true);
        if (fillResult.isSuccess()) {
            return fillResult.getResult();
        }

        FluidActionResult drainResult =
                FluidUtil.tryEmptyContainer(carried, routed, FluidType.BUCKET_VOLUME, player, true);
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
        return tank >= 0 && tank < fluidTanks.length
                ? fluidTanks[tank].getFluid()
                : FluidStack.EMPTY;
    }

    /**
     * Productivity bar carried over from finished cycles, in {@link ProductivityBonus#FULL_BAR}
     * units.
     */
    public int getProductivityProgress() {
        return productivity.progress();
    }

    public int getProductivityPercent() {
        return productivity.percent();
    }

    /** Productivity bar fill including the current cycle, as a whole percentage. */
    public int getVisibleProductivityPercent() {
        return ProductivityBonus.visiblePercent(
                productivity.progress(),
                productivity.percent(),
                miningProgress,
                getMiningDuration());
    }

    @Override
    protected boolean hasMiningFluid(dev.recipe.DrillMiningFluid required) {
        return required == null || findMiningFluidTank(required) >= 0;
    }

    private int findMiningFluidTank(dev.recipe.DrillMiningFluid required) {
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            if (required.test(fluidTanks[tank].getFluid())) return tank;
        }
        return -1;
    }

    @Override
    protected boolean consumeMiningFluid(dev.recipe.DrillMiningFluid required) {
        if (required == null) {
            return true;
        }

        int tank = findMiningFluidTank(required);
        if (tank < 0) {
            return false;
        }

        int remaining = fluidTanks[tank].getFluidAmount() - required.amount();
        fluidTanks[tank].setFluid(
                remaining > 0
                        ? fluidTanks[tank].getFluid().copyWithAmount(remaining)
                        : FluidStack.EMPTY);
        setChanged();
        return true;
    }

    protected static void tickEnergy(
            Level level,
            BlockPos pos,
            BlockState state,
            AbstractEnergyDrillBlockEntity be,
            Block expectedBlock) {
        if (!state.is(expectedBlock)) {
            return;
        }
        if (level.isClientSide) {
            tickClientEffects(be);
            return;
        }

        ServerLevel serverLevel = (ServerLevel) level;
        Direction facing = state.getValue(AbstractDrillBlock.FACING);
        boolean changed = exportOutput(serverLevel, pos, facing, be);
        ScanResult scan = scanAndPreview(serverLevel, pos, facing, be);
        changed |= scan.changed();
        changed |= be.chargeFromEnergyItem();
        changed |= be.pullFluidsFromInputs(serverLevel, pos, facing);
        boolean canMine = !scan.mineable().isEmpty();
        boolean outputReady = canOutputTargets(be, scan.mineable());
        int cost = be.energyProfile.energyPerTick();
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

    private boolean pullFluidFromInput(
            ServerLevel level,
            BlockPos origin,
            Direction facing,
            int offsetX,
            int offsetY,
            int offsetZ,
            Direction externalDirection) {
        BlockPos portPos = structure.offset(origin, facing, offsetX, offsetY, offsetZ);
        BlockPos sourcePos = portPos.relative(externalDirection);
        IFluidHandler source =
                level.getCapability(
                        Capabilities.FluidHandler.BLOCK,
                        sourcePos,
                        externalDirection.getOpposite());
        if (source == null) {
            return false;
        }

        AbstractEnergyDrillBlockEntity connectedDrill = connectedDrillAt(level, sourcePos);
        if (connectedDrill != null
                && connectedDrill != this
                && connectedDrill.fluidHandler == source) {
            return balanceFluids(fluidHandler, source);
        }

        return !FluidUtil.tryFluidTransfer(fluidHandler, source, FluidType.BUCKET_VOLUME, true)
                .isEmpty();
    }

    private static AbstractEnergyDrillBlockEntity connectedDrillAt(
            ServerLevel level, BlockPos partPos) {
        BlockState partState = level.getBlockState(partPos);
        if (!(partState.getBlock() instanceof AbstractDrillPartBlock part)) {
            return null;
        }

        BlockPos origin = part.structure().originFromPart(partPos, partState);
        return level.getBlockEntity(origin) instanceof AbstractEnergyDrillBlockEntity drill
                ? drill
                : null;
    }

    /**
     * Equalizes every fluid present in either drill. Pairwise equalization makes a row of drills
     * behave like a Factorio fluid network: liquid spreads through the row over subsequent ticks
     * without being pumped back and forth by both block entities.
     */
    private static boolean balanceFluids(IFluidHandler first, IFluidHandler second) {
        boolean changed = false;
        for (int tank = 0; tank < first.getTanks(); tank++) {
            FluidStack fluid = first.getFluidInTank(tank);
            if (!fluid.isEmpty() && isFirstOccurrence(first, tank, fluid)) {
                changed |= balanceFluid(first, second, fluid);
            }
        }
        for (int tank = 0; tank < second.getTanks(); tank++) {
            FluidStack fluid = second.getFluidInTank(tank);
            if (!fluid.isEmpty() && isFirstOccurrence(second, tank, fluid)) {
                changed |= balanceFluid(first, second, fluid);
            }
        }
        return changed;
    }

    private static boolean isFirstOccurrence(IFluidHandler handler, int tank, FluidStack fluid) {
        for (int previous = 0; previous < tank; previous++) {
            if (FluidStack.isSameFluidSameComponents(handler.getFluidInTank(previous), fluid)) {
                return false;
            }
        }
        return true;
    }

    private static boolean balanceFluid(
            IFluidHandler first, IFluidHandler second, FluidStack fluid) {
        int firstAmount = amountOf(first, fluid);
        int secondAmount = amountOf(second, fluid);
        int transferAmount = Math.abs(firstAmount - secondAmount) / 2;
        if (transferAmount <= 0) {
            return false;
        }

        IFluidHandler source = firstAmount > secondAmount ? first : second;
        IFluidHandler destination = firstAmount > secondAmount ? second : first;
        return !FluidUtil.tryFluidTransfer(
                        destination, source, fluid.copyWithAmount(transferAmount), true)
                .isEmpty();
    }

    private static int amountOf(IFluidHandler handler, FluidStack fluid) {
        int amount = 0;
        for (int tank = 0; tank < handler.getTanks(); tank++) {
            FluidStack stored = handler.getFluidInTank(tank);
            if (FluidStack.isSameFluidSameComponents(stored, fluid)) {
                amount += stored.getAmount();
            }
        }
        return amount;
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
            fluidTanks[tank].setFluid(
                    remaining > 0
                            ? fluidTanks[tank].getFluid().copyWithAmount(remaining)
                            : FluidStack.EMPTY);
            setChanged();
        }

        return result;
    }

    private boolean interactWithVanillaBucket(Player player, InteractionHand hand) {
        ItemStack held = player.getItemInHand(hand);
        if (held.is(Items.BUCKET)) {
            return fillBucketFromTank(player, hand, held);
        }

        if (held.getItem() instanceof BucketItem bucket
                && bucket.content != net.minecraft.world.level.material.Fluids.EMPTY) {
            return emptyBucketIntoTank(player, hand, held, bucket);
        }

        return false;
    }

    private boolean fillBucketFromTank(Player player, InteractionHand hand, ItemStack held) {
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            FluidStack simulated =
                    drainFromTank(
                            tank, FluidType.BUCKET_VOLUME, IFluidHandler.FluidAction.SIMULATE);
            if (simulated.getAmount() < FluidType.BUCKET_VOLUME) {
                continue;
            }

            ItemStack filledBucket = FluidUtil.getFilledBucket(simulated);
            if (filledBucket.isEmpty()) {
                continue;
            }

            drainFromTank(tank, FluidType.BUCKET_VOLUME, IFluidHandler.FluidAction.EXECUTE);
            if (!player.getAbilities().instabuild) {
                player.setItemInHand(
                        hand, ItemUtils.createFilledResult(held, player, filledBucket));
            }
            playBucketSound(player, simulated, SoundActions.BUCKET_FILL);
            return true;
        }

        return false;
    }

    private boolean emptyBucketIntoTank(
            Player player, InteractionHand hand, ItemStack held, BucketItem bucket) {
        FluidStack fluid = new FluidStack(bucket.content, FluidType.BUCKET_VOLUME);
        if (fluidHandler.fill(fluid, IFluidHandler.FluidAction.SIMULATE)
                < FluidType.BUCKET_VOLUME) {
            return false;
        }

        fluidHandler.fill(fluid, IFluidHandler.FluidAction.EXECUTE);
        if (!player.getAbilities().instabuild) {
            player.setItemInHand(hand, new ItemStack(Items.BUCKET));
        }
        playBucketSound(player, fluid, SoundActions.BUCKET_EMPTY);
        return true;
    }

    private void playBucketSound(
            Player player, FluidStack fluid, net.neoforged.neoforge.common.SoundAction action) {
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
        if (tag.contains("ProductivityBonusProgress")) {
            productivity.setProgress(tag.getInt("ProductivityBonusProgress"));
        } else {
            // Worlds saved before the Factorio model stored whole percents.
            productivity.setProgress(
                    tag.getInt("ProductivityProgress") * (ProductivityBonus.FULL_BAR / 100));
        }
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            String key = "FluidTank" + tank;
            if (tag.contains(key)) {
                fluidTanks[tank].readFromNBT(registries, tag.getCompound(key));
            }
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        tag.put("Energy", energyBuffer.serializeNBT(registries));
        tag.putInt("ProductivityBonusProgress", productivity.progress());
        for (int tank = 0; tank < fluidTanks.length; tank++) {
            tag.put("FluidTank" + tank, fluidTanks[tank].writeToNBT(registries, new CompoundTag()));
        }
    }

    @Override
    protected int cycleOutputMultiplier() {
        return productivity.cycleOutputMultiplier();
    }

    @Override
    protected void onMiningCycleCompleted() {
        if (productivity.isActive()) {
            productivity.completeCycle();
            setChanged();
        }
    }

    private final class DrillFluidTank extends FluidTank {
        private DrillFluidTank(int capacity) {
            super(capacity);
        }

        @Override
        protected void onContentsChanged() {
            AbstractEnergyDrillBlockEntity.this.setChanged();
        }
    }

    private final class RoutedFluidHandler implements IFluidHandler {
        @Override
        public int getTanks() {
            return fluidTanks.length;
        }

        @Override
        public FluidStack getFluidInTank(int tank) {
            return tank >= 0 && tank < fluidTanks.length
                    ? fluidTanks[tank].getFluid()
                    : FluidStack.EMPTY;
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
            if (resource.isEmpty()) {
                return FluidStack.EMPTY;
            }

            int tank = findTankContaining(resource);
            return tank < 0 ? FluidStack.EMPTY : drainFromTank(tank, resource.getAmount(), action);
        }

        @Override
        public FluidStack drain(int maxDrain, FluidAction action) {
            if (maxDrain <= 0) {
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

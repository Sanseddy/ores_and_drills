package dev.world.inventory;

import dev.world.block.entity.AbstractEnergyDrillBlockEntity;
import dev.world.block.DrillStructure;
import dev.util.ProgressMath;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.DataSlot;
import net.minecraft.world.inventory.MenuType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.material.Fluid;
import net.minecraft.world.level.material.Fluids;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.fluids.FluidStack;

public abstract class AbstractEnergyDrillMenu extends AbstractContainerMenu {
    private static final int ENERGY_ITEM_SLOT = 0;
    private static final int OUTPUT_SLOT = 1;

    private final ContainerLevelAccess access;
    private final int energyMenuSlot;
    private final int outputMenuSlot;
    private final int previewSlotCount;
    private final int playerInventoryStart;
    private final int playerInventoryEnd;
    private final int hotbarStart;
    private final int hotbarEnd;
    private final int[] clientData = new int[13];

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, int gridSize,
                                       int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int playerInventoryY) {
        this(type, containerId, playerInventory, gridSize, gridSize, gridSize,
                outputSlotX, outputSlotY, previewOriginX, previewOriginY, playerInventoryY, playerInventoryY + 58);
    }

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, int scanGridSize,
                                       int previewColumns, int previewRows,
                                       int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int playerInventoryY, int hotbarY) {
        this(type, containerId, playerInventory, scanGridSize, previewColumns, previewRows,
                outputSlotX, outputSlotY, previewOriginX, previewOriginY, 18, 18, false, playerInventoryY, hotbarY);
    }

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, int scanGridSize,
                                       int previewColumns, int previewRows,
                                       int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int previewStepX, int previewStepY, boolean previewColumnMajor,
                                       int playerInventoryY, int hotbarY) {
        this(type, containerId, playerInventory, scanGridSize, previewColumns, previewRows,
                outputSlotX, outputSlotY, previewOriginX, previewOriginY, previewStepX, previewStepY,
                previewColumnMajor, -1, -1, playerInventoryY, hotbarY);
    }

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, int scanGridSize,
                                       int previewColumns, int previewRows,
                                       int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int previewStepX, int previewStepY, boolean previewColumnMajor,
                                       int energySlotX, int energySlotY, int playerInventoryY, int hotbarY) {
        this(type, containerId, playerInventory, ContainerLevelAccess.NULL, new SimpleContainer(2),
                new SimpleContainer(scanGridSize * scanGridSize), null, previewColumns, previewRows,
                outputSlotX, outputSlotY, previewOriginX, previewOriginY, previewStepX, previewStepY,
                previewColumnMajor, energySlotX, energySlotY, playerInventoryY, hotbarY);
    }

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, ContainerLevelAccess access,
                                       Container outputContainer, Container previewContainer, AbstractEnergyDrillBlockEntity blockEntity,
                                       int gridSize, int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int playerInventoryY) {
        this(type, containerId, playerInventory, access, outputContainer, previewContainer, blockEntity,
                gridSize, gridSize, outputSlotX, outputSlotY, previewOriginX, previewOriginY,
                playerInventoryY, playerInventoryY + 58);
    }

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, ContainerLevelAccess access,
                                       Container outputContainer, Container previewContainer, AbstractEnergyDrillBlockEntity blockEntity,
                                       int previewColumns, int previewRows,
                                       int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int playerInventoryY, int hotbarY) {
        this(type, containerId, playerInventory, access, outputContainer, previewContainer, blockEntity,
                previewColumns, previewRows, outputSlotX, outputSlotY, previewOriginX, previewOriginY,
                18, 18, false, playerInventoryY, hotbarY);
    }

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, ContainerLevelAccess access,
                                       Container outputContainer, Container previewContainer, AbstractEnergyDrillBlockEntity blockEntity,
                                       int previewColumns, int previewRows,
                                       int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int previewStepX, int previewStepY, boolean previewColumnMajor,
                                       int playerInventoryY, int hotbarY) {
        this(type, containerId, playerInventory, access, outputContainer, previewContainer, blockEntity,
                previewColumns, previewRows, outputSlotX, outputSlotY, previewOriginX, previewOriginY,
                previewStepX, previewStepY, previewColumnMajor, -1, -1, playerInventoryY, hotbarY);
    }

    protected AbstractEnergyDrillMenu(MenuType<?> type, int containerId, Inventory playerInventory, ContainerLevelAccess access,
                                       Container outputContainer, Container previewContainer, AbstractEnergyDrillBlockEntity blockEntity,
                                       int previewColumns, int previewRows,
                                       int outputSlotX, int outputSlotY, int previewOriginX, int previewOriginY,
                                       int previewStepX, int previewStepY, boolean previewColumnMajor,
                                       int energySlotX, int energySlotY, int playerInventoryY, int hotbarY) {
        super(type, containerId);
        this.access = access;
        this.previewSlotCount = previewColumns * previewRows;

        this.energyMenuSlot = energySlotX >= 0 && energySlotY >= 0 ? slots.size() : -1;
        if (energyMenuSlot >= 0) {
            addSlot(new EnergyItemSlot(outputContainer, ENERGY_ITEM_SLOT, energySlotX, energySlotY));
        }

        this.outputMenuSlot = slots.size();
        addSlot(new OutputSlot(outputContainer, OUTPUT_SLOT, outputSlotX, outputSlotY));

        for (int row = 0; row < previewRows; row++) {
            for (int column = 0; column < previewColumns; column++) {
                int slot = previewColumnMajor ? column * previewRows + row : row * previewColumns + column;
                addSlot(new PreviewSlot(previewContainer, slot,
                        previewOriginX + column * previewStepX, previewOriginY + row * previewStepY));
            }
        }

        addDrillDataSlots(blockEntity);

        this.playerInventoryStart = slots.size();
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(new Slot(playerInventory, column + row * 9 + 9, 8 + column * 18, playerInventoryY + row * 18));
            }
        }

        this.playerInventoryEnd = slots.size();
        this.hotbarStart = slots.size();
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(playerInventory, column, 8 + column * 18, hotbarY));
        }
        this.hotbarEnd = slots.size();
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        ItemStack result = ItemStack.EMPTY;
        int previewStart = outputMenuSlot + 1;
        int previewEnd = previewStart + previewSlotCount;
        if (index >= previewStart && index < previewEnd) {
            return result;
        }

        Slot slot = slots.get(index);

        if (slot.hasItem()) {
            ItemStack stack = slot.getItem();
            result = stack.copy();

            if (index == outputMenuSlot) {
                if (!moveItemStackTo(stack, playerInventoryStart, hotbarEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (index == energyMenuSlot) {
                if (!moveItemStackTo(stack, playerInventoryStart, hotbarEnd, true)) {
                    return ItemStack.EMPTY;
                }
            } else if (energyMenuSlot >= 0 && isEnergyItem(stack)) {
                if (!moveItemStackTo(stack, energyMenuSlot, energyMenuSlot + 1, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (index < hotbarStart) {
                if (!moveItemStackTo(stack, hotbarStart, hotbarEnd, false)) {
                    return ItemStack.EMPTY;
                }
            } else if (!moveItemStackTo(stack, playerInventoryStart, playerInventoryEnd, false)) {
                return ItemStack.EMPTY;
            }

            if (stack.isEmpty()) {
                slot.setByPlayer(ItemStack.EMPTY);
            } else {
                slot.setChanged();
            }
        }

        return result;
    }

    @Override
    public boolean stillValid(Player player) {
        return DrillMenuValidity.stillValid(access, player, blockForValidation(), structureForValidation());
    }

    public void handleFluidTankClick(ServerPlayer player) {
        ItemStack carried = getCarried();
        if (carried.isEmpty()) {
            return;
        }

        access.execute((level, pos) -> {
            if (level.getBlockEntity(pos) instanceof AbstractEnergyDrillBlockEntity energyDrill) {
                ItemStack result = energyDrill.interactWithFluidContainerItem(carried, player);
                if (!ItemStack.matches(result, carried)) {
                    setCarried(result);
                }
            }
        });
    }

    protected abstract net.minecraft.world.level.block.Block blockForValidation();

    protected abstract DrillStructure structureForValidation();

    public int getEnergyProgressHeight(int barSize) {
        return ProgressMath.scaled(clientData[0], clientData[1], barSize);
    }

    public int getEnergyStored() {
        return clientData[0];
    }

    public int getEnergyCapacity() {
        return clientData[1];
    }

    public int getMiningProgressHeight(int barSize) {
        return ProgressMath.scaled(clientData[2], clientData[3], barSize);
    }

    public int getMiningProgressWidth(int barSize) {
        return ProgressMath.scaled(clientData[2], clientData[3], barSize);
    }

    public int getMiningProgressPercent() {
        return ProgressMath.percent(clientData[2], clientData[3]);
    }

    public int getFluidProgressHeight(int tank, int barSize) {
        int dataIndex = tank == 0 ? 4 : 6;
        return ProgressMath.scaled(clientData[dataIndex], clientData[dataIndex + 1], barSize);
    }

    public int getFluidAmount(int tank) {
        return clientData[tank == 0 ? 4 : 6];
    }

    public int getFluidCapacity(int tank) {
        return clientData[tank == 0 ? 5 : 7];
    }

    public int getProductivityProgressWidth(int barSize) {
        int visibleProgress = getVisibleProductivityProgress();
        return visibleProgress <= 0 ? 0 : Math.max(1, visibleProgress * barSize / 100);
    }

    public int getProductivityProgressPercent() {
        return getVisibleProductivityProgress();
    }

    private int getVisibleProductivityProgress() {
        int productivityProgress = clientData[8];
        int productivityPercent = clientData[9];
        int miningProgress = clientData[2];
        int miningDuration = clientData[3];
        if (productivityPercent <= 0) {
            return 0;
        }

        if (miningProgress <= 0 || miningDuration <= 0) {
            return productivityProgress;
        }

        int cycleTarget = Math.min(100, productivityProgress + productivityPercent);
        int currentCycleProgress = (cycleTarget - productivityProgress) * miningProgress / miningDuration;

        return Math.min(99, productivityProgress + currentCycleProgress);
    }

    public int getAnimationFrame() {
        return clientData[12];
    }

    public FluidStack getFluidForRender(int tank) {
        int dataIndex = tank == 0 ? 4 : 6;
        int amount = clientData[dataIndex];
        int fluidId = clientData[tank == 0 ? 10 : 11];
        if (amount <= 0 || fluidId < 0) {
            return FluidStack.EMPTY;
        }

        Fluid fluid = BuiltInRegistries.FLUID.byId(fluidId);
        return fluid == Fluids.EMPTY ? FluidStack.EMPTY : new FluidStack(fluid, amount);
    }

    private void addDrillDataSlots(AbstractEnergyDrillBlockEntity blockEntity) {
        if (blockEntity == null) {
            addDataSlot(DataSlot.shared(clientData, 0));
            addDataSlot(DataSlot.shared(clientData, 1));
            addDataSlot(DataSlot.shared(clientData, 2));
            addDataSlot(DataSlot.shared(clientData, 3));
            addDataSlot(DataSlot.shared(clientData, 4));
            addDataSlot(DataSlot.shared(clientData, 5));
            addDataSlot(DataSlot.shared(clientData, 6));
            addDataSlot(DataSlot.shared(clientData, 7));
            addDataSlot(DataSlot.shared(clientData, 8));
            addDataSlot(DataSlot.shared(clientData, 9));
            addDataSlot(DataSlot.shared(clientData, 10));
            addDataSlot(DataSlot.shared(clientData, 11));
            addDataSlot(DataSlot.shared(clientData, 12));
            return;
        }

        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getEnergyStored();
            }

            @Override
            public void set(int value) {
                clientData[0] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getEnergyCapacity();
            }

            @Override
            public void set(int value) {
                clientData[1] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getMiningProgress();
            }

            @Override
            public void set(int value) {
                blockEntity.setMiningProgress(value);
                clientData[2] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getMiningDuration();
            }

            @Override
            public void set(int value) {
                clientData[3] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getFluidAmount(0);
            }

            @Override
            public void set(int value) {
                clientData[4] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getFluidCapacity(0);
            }

            @Override
            public void set(int value) {
                clientData[5] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getFluidAmount(1);
            }

            @Override
            public void set(int value) {
                clientData[6] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getFluidCapacity(1);
            }

            @Override
            public void set(int value) {
                clientData[7] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getProductivityProgress();
            }

            @Override
            public void set(int value) {
                clientData[8] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getProductivityPercent();
            }

            @Override
            public void set(int value) {
                clientData[9] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return fluidId(blockEntity, 0);
            }

            @Override
            public void set(int value) {
                clientData[10] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return fluidId(blockEntity, 1);
            }

            @Override
            public void set(int value) {
                clientData[11] = value;
            }
        });
        addDataSlot(new DataSlot() {
            @Override
            public int get() {
                return blockEntity.getGuiAnimationFrame();
            }

            @Override
            public void set(int value) {
                clientData[12] = value;
            }
        });
    }

    private static int fluidId(AbstractEnergyDrillBlockEntity blockEntity, int tank) {
        FluidStack fluid = blockEntity.getFluidForRender(tank);
        return fluid.isEmpty() ? -1 : BuiltInRegistries.FLUID.getId(fluid.getFluid());
    }

    private static boolean isEnergyItem(ItemStack stack) {
        return !stack.isEmpty() && stack.getCapability(Capabilities.EnergyStorage.ITEM) != null;
    }

    private static class EnergyItemSlot extends Slot {
        EnergyItemSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return isEnergyItem(stack);
        }
    }

    private static class OutputSlot extends Slot {
        OutputSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }
    }

    private static class PreviewSlot extends Slot {
        PreviewSlot(Container container, int slot, int x, int y) {
            super(container, slot, x, y);
        }

        @Override
        public boolean mayPlace(ItemStack stack) {
            return false;
        }

        @Override
        public boolean mayPickup(Player player) {
            return false;
        }

        @Override
        public boolean isHighlightable() {
            return false;
        }
    }
}

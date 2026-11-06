package dev.world.geology.exploration;

import dev.registry.ModBlocks;
import dev.registry.ModMenuTypes;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarSync;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/** Exploration drill menu: one take-only core slot, the player inventory and live figures. */
public final class ExplorationDrillMenu extends AbstractContainerMenu implements LidarSync.Source {
    public static final int BUTTON_START = 0;
    public static final int BUTTON_STOP = 1;

    public static final int DATA_ENERGY = 0;
    public static final int DATA_CAPACITY = 1;
    public static final int DATA_STATE = 2;
    public static final int DATA_TOP = 3;
    public static final int DATA_CURSOR = 4;
    public static final int DATA_BOTTOM = 5;
    public static final int DATA_LEVEL = 6;
    public static final int DATA_ANALYZER = 7;
    public static final int DATA_COUNT = 8;

    public static final int SAMPLE_SLOT_X = 152;
    public static final int SAMPLE_SLOT_Y = 62;
    public static final int INVENTORY_Y = 98;

    private static final Component TITLE =
            Component.translatable("block.ores_and_drills.exploration_drill");

    private final ContainerLevelAccess access;
    private final ContainerData data;
    private final Container output;
    private final BlockPos pos;
    private final BlockPos hole;

    public ExplorationDrillMenu(
            int containerId, Inventory inventory, RegistryFriendlyByteBuf buffer) {
        this(
                containerId,
                inventory,
                ContainerLevelAccess.NULL,
                new SimpleContainer(1),
                new SimpleContainerData(DATA_COUNT),
                buffer.readBlockPos(),
                buffer.readBlockPos());
    }

    private ExplorationDrillMenu(
            int containerId,
            Inventory inventory,
            ContainerLevelAccess access,
            Container output,
            ContainerData data,
            BlockPos pos,
            BlockPos hole) {
        super(ModMenuTypes.EXPLORATION_DRILL.get(), containerId);
        this.access = access;
        this.output = output;
        this.data = data;
        this.pos = pos.immutable();
        this.hole = hole.immutable();
        addSlot(
                new Slot(output, 0, SAMPLE_SLOT_X, SAMPLE_SLOT_Y) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }
                });
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 9; column++) {
                addSlot(
                        new Slot(
                                inventory,
                                column + row * 9 + 9,
                                8 + column * 18,
                                INVENTORY_Y + row * 18));
            }
        }
        for (int column = 0; column < 9; column++) {
            addSlot(new Slot(inventory, column, 8 + column * 18, INVENTORY_Y + 58));
        }
        addDataSlots(data);
    }

    public static void open(ServerPlayer player, ExplorationDrillBlockEntity drill) {
        BlockPos pos = drill.getBlockPos();
        BlockPos hole = drill.currentHole();
        player.openMenu(
                new SimpleMenuProvider(
                        (id, inventory, menuPlayer) ->
                                new ExplorationDrillMenu(
                                        id,
                                        inventory,
                                        ContainerLevelAccess.create(drill.getLevel(), pos),
                                        drill.output(),
                                        new DrillData(drill),
                                        pos,
                                        hole),
                        TITLE),
                buffer -> {
                    buffer.writeBlockPos(pos);
                    buffer.writeBlockPos(hole);
                });
    }

    public int value(int index) {
        return data.get(index);
    }

    /**
     * Top of the borehole when the screen opened; differs from the drill's block for a drill on a
     * Sable body, whose block lies in a plot far from the ground it bores.
     */
    public BlockPos hole() {
        return hole;
    }

    @Override
    public BlockPos lidarOwner() {
        return pos;
    }

    @Override
    public LidarMap lidarMap(ServerPlayer player) {
        if (!stillValid(player)) {
            return null;
        }
        return access.evaluate(
                        (level, origin) ->
                                level.getBlockEntity(origin)
                                                instanceof ExplorationDrillBlockEntity drill
                                        ? drill.lidarMap()
                                        : null)
                .orElse(null);
    }

    public ItemStack sample() {
        return output.getItem(0);
    }

    @Override
    public boolean clickMenuButton(Player player, int id) {
        if (player.isSpectator() || !player.mayBuild()) {
            return false;
        }
        return Boolean.TRUE.equals(
                access.evaluate(
                        (level, pos) -> {
                            if (!(level.getBlockEntity(pos)
                                    instanceof ExplorationDrillBlockEntity drill)) {
                                return false;
                            }
                            if (id == BUTTON_START) {
                                drill.start();
                                return true;
                            }
                            if (id == BUTTON_STOP) {
                                drill.stop();
                                return true;
                            }
                            return false;
                        },
                        false));
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        Slot slot = slots.get(index);
        if (!slot.hasItem()) {
            return ItemStack.EMPTY;
        }
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index == 0) {
            if (!moveItemStackTo(stack, 1, slots.size(), true)) {
                return ItemStack.EMPTY;
            }
        } else {
            return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) {
            slot.setByPlayer(ItemStack.EMPTY);
        } else {
            slot.setChanged();
        }
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModBlocks.EXPLORATION_DRILL.get());
    }

    private record DrillData(ExplorationDrillBlockEntity drill) implements ContainerData {
        @Override
        public int get(int index) {
            return switch (index) {
                case DATA_ENERGY -> drill.energyStored();
                case DATA_CAPACITY -> drill.energyCapacity();
                case DATA_STATE -> drill.state();
                case DATA_TOP -> drill.topY();
                case DATA_CURSOR -> drill.cursorY();
                case DATA_BOTTOM -> drill.bottomLimit();
                case DATA_LEVEL -> drill.scannerLevel();
                case DATA_ANALYZER -> drill.analyzerLevel();
                default -> 0;
            };
        }

        @Override
        public void set(int index, int value) {}

        @Override
        public int getCount() {
            return DATA_COUNT;
        }
    }
}

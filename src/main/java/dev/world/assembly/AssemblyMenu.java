package dev.world.assembly;

import dev.registry.ModAssembly;
import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.inventory.SimpleContainerData;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;

public final class AssemblyMenu extends AbstractContainerMenu {
    public static final int BUTTON_SELECT_RECIPE = 4;
    private final Container inventory;
    private final ContainerData data;
    private final ContainerLevelAccess access;
    private final AssemblyBlockEntity table;
    private final Level level;

    public AssemblyMenu(int id, Inventory player) {
        this(
                id,
                player,
                new SimpleContainer(7),
                new SimpleContainerData(4),
                ContainerLevelAccess.NULL,
                null);
    }

    public AssemblyMenu(
            int id,
            Inventory player,
            Container inventory,
            ContainerData data,
            ContainerLevelAccess access,
            AssemblyBlockEntity table) {
        super(ModAssembly.MENU.get(), id);
        this.inventory = inventory;
        this.data = data;
        this.access = access;
        this.table = table;
        this.level = player.player.level();
        for (int slot = 0; slot < 6; slot++) {
            addSlot(new Slot(inventory, slot, 48 + slot % 3 * 24, 44 + slot / 3 * 28));
        }
        addSlot(
                new Slot(inventory, 6, 151, 58) {
                    @Override
                    public boolean mayPlace(ItemStack stack) {
                        return false;
                    }
                });
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(player, 9 + row * 9 + col, 17 + col * 18, 134 + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) addSlot(new Slot(player, col, 17 + col * 18, 192));
        addDataSlots(data);
    }

    public RecipeHolder<AssemblyRecipe> recipe() {
        List<RecipeHolder<AssemblyRecipe>> recipes = AssemblyBlockEntity.recipes(level);
        return recipes.isEmpty()
                ? null
                : recipes.get(Math.clamp(data.get(0), 0, recipes.size() - 1));
    }

    public List<RecipeHolder<AssemblyRecipe>> recipes() {
        return AssemblyBlockEntity.recipes(level);
    }

    public int selectedRecipeIndex() {
        return Math.clamp(data.get(0), 0, Math.max(0, recipes().size() - 1));
    }

    public int progress() {
        return data.get(1);
    }

    public int duration() {
        return Math.max(1, data.get(2));
    }

    public boolean working() {
        return data.get(3) != 0;
    }

    public boolean ready() {
        RecipeHolder<AssemblyRecipe> recipe = recipe();
        if (recipe == null || !recipe.value().matches(new AssemblyInput(inventory), level))
            return false;
        ItemStack result = recipe.value().result();
        ItemStack output = inventory.getItem(6);
        return output.isEmpty()
                || ItemStack.isSameItemSameComponents(output, result)
                        && output.getCount() + result.getCount() <= output.getMaxStackSize();
    }

    @Override
    public boolean clickMenuButton(Player player, int button) {
        if (table == null || player.isSpectator() || !player.mayBuild() || !stillValid(player))
            return false;
        switch (button) {
            case 0 -> table.select(-1);
            case 1 -> table.select(1);
            case 2 -> {
                return table.start();
            }
            case 3 -> table.cancel();
            default -> {
                if (button < BUTTON_SELECT_RECIPE
                        || !table.selectRecipe(button - BUTTON_SELECT_RECIPE)) return false;
            }
        }
        broadcastChanges();
        return true;
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        if (index < 0 || index >= slots.size()) return ItemStack.EMPTY;
        Slot slot = slots.get(index);
        if (!slot.hasItem()) return ItemStack.EMPTY;
        ItemStack stack = slot.getItem();
        ItemStack original = stack.copy();
        if (index < 7) {
            if (!moveItemStackTo(stack, 7, slots.size(), true)) return ItemStack.EMPTY;
        } else {
            RecipeHolder<AssemblyRecipe> recipe = recipe();
            boolean moved = false;
            if (recipe != null) {
                for (int i = 0; i < recipe.value().parts().size() && !stack.isEmpty(); i++) {
                    if (recipe.value().parts().get(i).ingredient().test(stack)) {
                        moved |= moveItemStackTo(stack, i, i + 1, false);
                    }
                }
            }
            if (!moved) {
                if (index < 34) moved = moveItemStackTo(stack, 34, 43, false);
                else moved = moveItemStackTo(stack, 7, 34, false);
            }
            if (!moved) return ItemStack.EMPTY;
        }
        if (stack.isEmpty()) slot.setByPlayer(ItemStack.EMPTY);
        else slot.setChanged();
        if (stack.getCount() == original.getCount()) return ItemStack.EMPTY;
        slot.onTake(player, stack);
        return original;
    }

    @Override
    public boolean stillValid(Player player) {
        return stillValid(access, player, ModAssembly.TABLE.get())
                && (table == null
                        || access.evaluate(
                                (world, pos) ->
                                        world.getBlockEntity(pos) == table
                                                && AssemblyBlock.isComplete(
                                                        world, pos, world.getBlockState(pos)),
                                false));
    }
}

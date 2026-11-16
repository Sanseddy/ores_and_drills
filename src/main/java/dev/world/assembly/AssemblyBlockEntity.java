package dev.world.assembly;

import dev.registry.ModAssembly;
import dev.registry.ModRecipes;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.Containers;
import net.minecraft.world.MenuProvider;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerData;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A manual, one-batch assembly fixture. Inputs stay recoverable until the atomic completion step;
 * changing any slot stops the batch. No energy network or previously built drill is required.
 */
public final class AssemblyBlockEntity extends BlockEntity implements MenuProvider {
    private static final ResourceLocation DEFAULT_RECIPE =
            ResourceLocation.fromNamespaceAndPath("ores_and_drills", "assembly/electric_motor");
    private final SimpleContainer inventory =
            new SimpleContainer(7) {
                @Override
                public void setChanged() {
                    super.setChanged();
                    if (!completing) {
                        progress = 0;
                        working = false;
                    }
                    sync();
                }
            };
    private ResourceLocation selected = DEFAULT_RECIPE;
    private AssemblyRecipe runningRecipe;
    private int progress;
    private boolean working;
    private boolean completing;
    private boolean needsExpansion;

    public AssemblyBlockEntity(BlockPos pos, BlockState state) {
        super(ModAssembly.BLOCK_ENTITY.get(), pos, state);
    }

    public SimpleContainer inventory() {
        return inventory;
    }

    public static List<RecipeHolder<AssemblyRecipe>> recipes(Level level) {
        return level.getRecipeManager().getAllRecipesFor(ModRecipes.DRILL_ASSEMBLY.get()).stream()
                .sorted(
                        Comparator.<RecipeHolder<AssemblyRecipe>, Boolean>comparing(
                                        recipe ->
                                                !(recipe.value().result().getItem()
                                                        instanceof BlockItem))
                                .thenComparing(recipe -> recipe.id().toString()))
                .toList();
    }

    public RecipeHolder<AssemblyRecipe> selectedRecipe() {
        if (level == null) return null;
        List<RecipeHolder<AssemblyRecipe>> recipes = recipes(level);
        return recipes.stream()
                .filter(recipe -> recipe.id().equals(selected))
                .findFirst()
                .orElse(recipes.isEmpty() ? null : recipes.getFirst());
    }

    public int selectedIndex() {
        RecipeHolder<AssemblyRecipe> recipe = selectedRecipe();
        return recipe == null ? 0 : recipes(level).indexOf(recipe);
    }

    public void select(int direction) {
        List<RecipeHolder<AssemblyRecipe>> recipes = recipes(level);
        if (recipes.isEmpty()) return;
        selectRecipe(Math.floorMod(selectedIndex() + direction, recipes.size()));
    }

    public boolean selectRecipe(int index) {
        List<RecipeHolder<AssemblyRecipe>> recipes = recipes(level);
        if (index < 0 || index >= recipes.size()) return false;
        if (recipes.get(index).id().equals(selected)) return true;
        selected = recipes.get(index).id();
        working = false;
        progress = 0;
        runningRecipe = null;
        sync();
        return true;
    }

    public boolean canStart() {
        RecipeHolder<AssemblyRecipe> holder = selectedRecipe();
        return holder != null
                && AssemblyBlock.isComplete(level, worldPosition, getBlockState())
                && holder.value().matches(new AssemblyInput(inventory), level)
                && fitsOutput(holder.value());
    }

    public boolean start() {
        if (working || !canStart()) return false;
        RecipeHolder<AssemblyRecipe> holder = selectedRecipe();
        selected = holder.id();
        runningRecipe = holder.value();
        progress = 0;
        working = true;
        sync();
        return true;
    }

    public void cancel() {
        working = false;
        progress = 0;
        sync();
    }

    private boolean fitsOutput(AssemblyRecipe recipe) {
        ItemStack output = inventory.getItem(6);
        ItemStack result = recipe.result();
        return output.isEmpty()
                || ItemStack.isSameItemSameComponents(output, result)
                        && output.getCount() + result.getCount() <= output.getMaxStackSize();
    }

    public void tick() {
        if (level == null || level.isClientSide) return;
        ensureStructure();
        if (needsExpansion) return;
        if (!working) return;
        if (!level.hasChunkAt(AssemblyBlock.otherPos(worldPosition, getBlockState()))) return;
        RecipeHolder<AssemblyRecipe> holder = selectedRecipe();
        if (holder == null
                || !holder.id().equals(selected)
                || runningRecipe != null && runningRecipe != holder.value()
                || !canStart()) {
            cancel();
            return;
        }
        runningRecipe = holder.value();
        progress++;
        if (progress >= runningRecipe.duration()) {
            finish(runningRecipe);
        } else {
            setChanged();
            if (progress % 10 == 0) sync();
        }
    }

    /**
     * Expands a table saved by the old one-block implementation without overwriting its neighbor.
     */
    public void ensureStructure() {
        if (!needsExpansion || level == null || level.isClientSide) return;
        // Old saves store the front direction; the new state stores the table's long axis.
        BlockState migrated =
                getBlockState()
                        .setValue(
                                AssemblyBlock.FACING,
                                getBlockState().getValue(AssemblyBlock.FACING).getClockWise());
        BlockPos other = AssemblyBlock.otherPos(worldPosition, migrated);
        if (!level.hasChunkAt(other)) return;
        if (!level.getWorldBorder().isWithinBounds(worldPosition)
                || !level.getWorldBorder().isWithinBounds(other)
                || !level.getBlockState(other).canBeReplaced()
                || level.getBlockEntity(other) != null
                || !level.isUnobstructed(
                        migrated.setValue(AssemblyBlock.EXTENSION, true),
                        other,
                        net.minecraft.world.phys.shapes.CollisionContext.empty())) return;
        BlockState previous = level.getBlockState(other);
        if (!level.setBlock(other, migrated.setValue(AssemblyBlock.EXTENSION, true), 18)) return;
        if (!level.setBlock(worldPosition, migrated, 18)) {
            AssemblyBlock.restorePair(level, worldPosition, getBlockState(), other, previous);
            return;
        }
        needsExpansion = false;
        sync();
        level.updateNeighborsAt(worldPosition, getBlockState().getBlock());
        level.updateNeighborsAt(other, getBlockState().getBlock());
        migrated.updateNeighbourShapes(level, worldPosition, 3);
        migrated.setValue(AssemblyBlock.EXTENSION, true).updateNeighbourShapes(level, other, 3);
    }

    private void finish(AssemblyRecipe recipe) {
        // All validation happens on the server immediately before this indivisible mutation.
        completing = true;
        try {
            for (int slot = 0; slot < recipe.parts().size(); slot++) {
                int count = recipe.parts().get(slot).count();
                ItemStack consumed = inventory.getItem(slot).copyWithCount(count);
                inventory.removeItem(slot, count);
                for (int item = 0; item < count; item++) {
                    ItemStack remainder = consumed.copyWithCount(1).getCraftingRemainingItem();
                    if (!remainder.isEmpty()) {
                        ItemStack remainingInput = inventory.getItem(slot);
                        if (remainingInput.isEmpty()) {
                            inventory.setItem(slot, remainder);
                        } else if (ItemStack.isSameItemSameComponents(remainingInput, remainder)
                                && remainingInput.getCount() + remainder.getCount()
                                        <= remainingInput.getMaxStackSize()) {
                            remainingInput.grow(remainder.getCount());
                        } else
                            Containers.dropItemStack(
                                    level,
                                    worldPosition.getX() + 0.5,
                                    worldPosition.getY() + 1,
                                    worldPosition.getZ() + 0.5,
                                    remainder);
                    }
                }
            }
            ItemStack output = inventory.getItem(6);
            if (output.isEmpty()) inventory.setItem(6, recipe.result().copy());
            else output.grow(recipe.result().getCount());
            working = false;
            progress = 0;
        } finally {
            completing = false;
        }
        sync();
    }

    public int progress() {
        return progress;
    }

    public boolean working() {
        return working;
    }

    public int duration() {
        RecipeHolder<AssemblyRecipe> recipe = selectedRecipe();
        return recipe == null ? 1 : recipe.value().duration();
    }

    private void sync() {
        setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), 2);
        }
    }

    @Override
    public Component getDisplayName() {
        return Component.translatable("block.ores_and_drills.drill_assembly_table");
    }

    @Override
    public AbstractContainerMenu createMenu(int id, Inventory playerInventory, Player player) {
        ContainerData data =
                new ContainerData() {
                    @Override
                    public int get(int index) {
                        return switch (index) {
                            case 0 -> selectedIndex();
                            case 1 -> progress;
                            case 2 -> duration();
                            case 3 -> working ? 1 : 0;
                            default -> 0;
                        };
                    }

                    @Override
                    public void set(int index, int value) {}

                    @Override
                    public int getCount() {
                        return 4;
                    }
                };
        return new AssemblyMenu(
                id,
                playerInventory,
                inventory,
                data,
                ContainerLevelAccess.create(level, worldPosition),
                this);
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.saveAdditional(tag, registries);
        ContainerHelper.saveAllItems(tag, inventory.getItems(), registries);
        tag.putInt("AssemblyWidth", needsExpansion ? 1 : 2);
        if (selected != null) tag.putString("Recipe", selected.toString());
        tag.putInt("Progress", progress);
        tag.putBoolean("Working", working);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider registries) {
        super.loadAdditional(tag, registries);
        needsExpansion = tag.getInt("AssemblyWidth") < 2;
        inventory.clearContent();
        ContainerHelper.loadAllItems(tag, inventory.getItems(), registries);
        selected = ResourceLocation.tryParse(tag.getString("Recipe"));
        if (selected == null) selected = DEFAULT_RECIPE;
        progress = Math.clamp(tag.getInt("Progress"), 0, 12000);
        working = tag.getBoolean("Working");
        runningRecipe = null;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public ClientboundBlockEntityDataPacket getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}

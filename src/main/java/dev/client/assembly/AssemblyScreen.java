package dev.client.assembly;

import dev.OresAndDrillsMod;
import dev.world.assembly.AssemblyMenu;
import dev.world.assembly.AssemblyRecipe;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;

/** Recipe selection, rotating ghost ingredients and assembly progress. */
public final class AssemblyScreen extends AbstractContainerScreen<AssemblyMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    OresAndDrillsMod.MOD_ID, "textures/gui/drill_assembly.png");
    private static final int LABEL_COLOR = 0xff404040;
    private static final int GHOST_OVERLAY_COLOR = 0x998b8b8b;
    private Button assemble;
    private Button scrollUp;
    private Button scrollDown;
    private final RecipeButton[] recipeButtons = new RecipeButton[3];
    private int firstVisible;
    private int lastSelected = -1;
    private int lastRecipeCount = -1;

    public AssemblyScreen(AssemblyMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 196;
        imageHeight = 215;
        inventoryLabelX = 17;
        inventoryLabelY = 123;
    }

    private void send(int button) {
        if (minecraft.gameMode != null)
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, button);
    }

    @Override
    protected void init() {
        super.init();
        scrollUp =
                addRenderableWidget(
                        Button.builder(Component.literal("↑"), button -> scrollRecipes(-1))
                                .tooltip(
                                        Tooltip.create(
                                                Component.translatable(
                                                        "gui.ores_and_drills.assembly.scroll_up")))
                                .bounds(leftPos + 8, topPos + 18, 24, 10)
                                .build());
        scrollDown =
                addRenderableWidget(
                        Button.builder(Component.literal("↓"), button -> scrollRecipes(1))
                                .tooltip(
                                        Tooltip.create(
                                                Component.translatable(
                                                        "gui.ores_and_drills.assembly.scroll_down")))
                                .bounds(leftPos + 8, topPos + 108, 24, 10)
                                .build());
        for (int i = 0; i < recipeButtons.length; i++) {
            recipeButtons[i] =
                    addRenderableWidget(new RecipeButton(leftPos + 8, topPos + 30 + i * 26));
        }
        lastSelected = -1;
        refreshRecipes();
        assemble =
                addRenderableWidget(
                        Button.builder(Component.empty(), button -> send(menu.working() ? 3 : 2))
                                .bounds(leftPos + 131, topPos + 89, 56, 20)
                                .build());
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refreshRecipes();
        assemble.setMessage(
                Component.translatable(
                        menu.working()
                                ? "gui.ores_and_drills.assembly.cancel"
                                : "gui.ores_and_drills.assembly.start"));
        assemble.active = menu.working() || menu.ready();
    }

    @Override
    protected void renderBg(GuiGraphics gui, float partialTick, int mouseX, int mouseY) {
        int x = leftPos, y = topPos;
        gui.blit(TEXTURE, x, y, 0, 0, imageWidth, imageHeight, 256, 256);
        var holder = menu.recipe();
        if (holder != null) {
            AssemblyRecipe recipe = holder.value();
            Component name = recipe.result().getHoverName();
            String caption = font.plainSubstrByWidth(name.getString(), 144);
            gui.drawString(
                    font,
                    caption,
                    x + 40 + (148 - font.width(caption)) / 2,
                    y + 25,
                    LABEL_COLOR,
                    false);
            for (int slot = 0; slot < recipe.parts().size(); slot++) {
                var fixture = menu.slots.get(slot);
                AssemblyRecipe.Part part = recipe.parts().get(slot);
                ItemStack[] variants = part.ingredient().getItems();
                if (!fixture.hasItem() && variants.length > 0) {
                    ItemStack ghost =
                            variants[
                                    (int)
                                            (net.minecraft.Util.getMillis()
                                                    / 1000L
                                                    % variants.length)]
                                    .copyWithCount(part.count());
                    renderGhostItem(gui, ghost, x + fixture.x, y + fixture.y);
                }
                gui.drawString(
                        font,
                        "×" + part.count(),
                        x + fixture.x,
                        y + fixture.y + 18,
                        LABEL_COLOR,
                        false);
            }
            if (!menu.slots.get(6).hasItem()) {
                renderGhostItem(gui, recipe.result(), x + 151, y + 58);
            }
            gui.drawString(
                    font,
                    Component.translatable(
                            "gui.ores_and_drills.assembly.seconds", recipe.duration() / 20),
                    x + 142,
                    y + 41,
                    LABEL_COLOR,
                    false);
        }
        int arrow = Math.clamp(menu.progress() * 23 / menu.duration(), 0, 23);
        if (arrow > 0) gui.blit(TEXTURE, x + 117, y + 57, 204, 0, arrow, 17, 256, 256);
        int bar = Math.clamp(menu.progress() * 64 / menu.duration(), 0, 64);
        if (bar > 0) gui.blit(TEXTURE, x + 48, y + 103, 0, 236, bar, 5, 256, 256);
        for (int i = 1; i < 3; i++)
            gui.fill(x + 47 + i * 22, y + 103, x + 48 + i * 22, y + 108, 0xffc6c6c6);
    }

    private void scrollRecipes(int direction) {
        firstVisible =
                Math.clamp(firstVisible + direction, 0, Math.max(0, menu.recipes().size() - 3));
        refreshRecipes();
    }

    private void refreshRecipes() {
        var recipes = menu.recipes();
        int selected = menu.selectedRecipeIndex();
        if (selected != lastSelected || recipes.size() != lastRecipeCount) {
            if (selected < firstVisible) firstVisible = selected;
            else if (selected >= firstVisible + 3) firstVisible = selected - 2;
            lastSelected = selected;
            lastRecipeCount = recipes.size();
        }
        firstVisible = Math.clamp(firstVisible, 0, Math.max(0, recipes.size() - 3));
        scrollUp.active = firstVisible > 0;
        scrollDown.active = firstVisible + 3 < recipes.size();
        for (int i = 0; i < recipeButtons.length; i++) {
            RecipeButton button = recipeButtons[i];
            button.recipeIndex = firstVisible + i;
            button.active = button.recipeIndex < recipes.size();
            button.result =
                    button.active
                            ? recipes.get(button.recipeIndex).value().result()
                            : ItemStack.EMPTY;
            button.setMessage(button.result.getHoverName());
            button.setTooltip(button.active ? Tooltip.create(button.getMessage()) : null);
        }
    }

    private boolean insideRecipePanel(double x, double y) {
        return x >= leftPos + 8 && x < leftPos + 32 && y >= topPos + 18 && y < topPos + 118;
    }

    @Override
    public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        if (insideRecipePanel(x, y) && vertical != 0) {
            scrollRecipes(vertical > 0 ? -1 : 1);
            return true;
        }
        return super.mouseScrolled(x, y, horizontal, vertical);
    }

    private final class RecipeButton extends AbstractButton {
        private int recipeIndex;
        private ItemStack result = ItemStack.EMPTY;

        private RecipeButton(int x, int y) {
            super(x, y, 24, 24, Component.empty());
        }

        @Override
        public void onPress() {
            send(AssemblyMenu.BUTTON_SELECT_RECIPE + recipeIndex);
        }

        @Override
        protected void renderWidget(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
            int v =
                    active && recipeIndex == menu.selectedRecipeIndex()
                            ? 48
                            : active && isHoveredOrFocused() ? 72 : 24;
            gui.blit(TEXTURE, getX(), getY(), 232, v, 24, 24, 256, 256);
            if (!result.isEmpty()) {
                gui.renderFakeItem(result, getX() + 4, getY() + 4);
                gui.renderItemDecorations(font, result, getX() + 4, getY() + 4);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput output) {
            defaultButtonNarrationText(output);
        }
    }

    private static void renderGhostItem(GuiGraphics gui, ItemStack stack, int x, int y) {
        gui.renderFakeItem(stack, x, y);
        // The recipe-book pass reaches item pixels at their GUI depth; a normal fill sits behind
        // them.
        gui.fill(RenderType.guiGhostRecipeOverlay(), x, y, x + 16, y + 16, GHOST_OVERLAY_COLOR);
    }

    @Override
    public void render(GuiGraphics gui, int mouseX, int mouseY, float partialTick) {
        super.render(gui, mouseX, mouseY, partialTick);
        renderTooltip(gui, mouseX, mouseY);
        var holder = menu.recipe();
        if (holder == null || hoveredSlot == null || hoveredSlot.hasItem()) return;
        int slot = hoveredSlot.index;
        if (slot >= 0 && slot < holder.value().parts().size()) {
            var part = holder.value().parts().get(slot);
            ItemStack[] variants = part.ingredient().getItems();
            if (variants.length > 0) {
                ItemStack ghost =
                        variants[(int) (net.minecraft.Util.getMillis() / 1000L % variants.length)];
                gui.renderTooltip(
                        font,
                        java.util.List.of(
                                ghost.getHoverName(),
                                Component.translatable(
                                        "gui.ores_and_drills.assembly.required", part.count())),
                        java.util.Optional.empty(),
                        mouseX,
                        mouseY);
            }
        } else if (slot == 6) gui.renderTooltip(font, holder.value().result(), mouseX, mouseY);
    }
}

package dev.client.worldcreation;

import dev.OresAndDrillsMod;
import dev.config.ConfigReloader;
import dev.config.OreDepositConfig;
import dev.world.level.levelgen.OreTags;
import dev.world.level.levelgen.OreUnifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Edits {@code mod_priorities}: when several mods add the same material, the ore of the mod highest in
 * this list becomes the canonical deposit ore. Rows are reordered by dragging the grip on the right and
 * can be added, renamed or removed; mods missing from the list rank below every listed mod.
 */
public final class ModPrioritiesScreen extends Screen {
    private static final int MARGIN = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int ROW_WIDTH = 340;
    private static final String KEY = "gui.ores_and_drills.mod_priorities.";

    private final Screen parent;
    private final Consumer<List<String>> onSave;
    private final List<String> order = new ArrayList<>();
    private PriorityList list;
    private int pendingRemoval = -1;

    /** Saves straight to the config on "Done". */
    public ModPrioritiesScreen(Screen parent) {
        this(parent, configured(), ModPrioritiesScreen::saveToConfig);
    }

    /** Hands the edited order to {@code onSave} instead, for screens that save several settings at once. */
    public ModPrioritiesScreen(Screen parent, List<? extends String> priorities, Consumer<List<String>> onSave) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        this.onSave = onSave;
        load(priorities);
    }

    public static List<? extends String> configured() {
        try {
            return OreDepositConfig.MOD_PRIORITIES.get();
        } catch (IllegalStateException exception) {
            return OreDepositConfig.MOD_PRIORITIES.getDefault();
        }
    }

    private void load(List<? extends String> priorities) {
        order.clear();
        priorities.stream().filter(mod -> !mod.isBlank()).distinct().forEach(order::add);
    }

    /** Installed mods that add ores and aren't listed yet; offered as quick picks when adding. */
    private List<String> unlistedOreMods() {
        Set<String> mods = new LinkedHashSet<>();
        for (Block block : OreTags.oreBlocks()) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id != null && !id.getNamespace().equals(OresAndDrillsMod.MOD_ID)) mods.add(id.getNamespace());
        }
        mods.removeAll(order);
        return List.copyOf(mods);
    }

    @Override
    protected void init() {
        int listY = MARGIN + 32;
        int footer = OreUnifier.hasAlmostUnifiedPriorities() ? 48 : 36;
        double scroll = list == null ? 0.0D : list.getScrollAmount();
        list = addRenderableWidget(new PriorityList(this.minecraft, this.width, Math.max(48, this.height - listY - footer), listY));
        list.setScrollAmount(scroll);

        int buttonY = this.height - 28;
        int left = this.width / 2 - 158;
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.settings.list.add"), button -> openEditor(-1))
                .bounds(left, buttonY, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable(KEY + "reset"), button -> {
            load(OreDepositConfig.MOD_PRIORITIES.getDefault());
            list.refresh();
        }).bounds(left + 80, buttonY, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.done"), button -> onDone())
                .bounds(left + 160, buttonY, 76, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.cancel"), button -> onClose())
                .bounds(left + 240, buttonY, 76, 20).build());
    }

    /** Opens the mod id editor for a row, or for a new row appended at the end when {@code index < 0}. */
    private void openEditor(int index) {
        String current = index >= 0 ? order.get(index) : "";
        Set<String> taken = new LinkedHashSet<>(order);
        taken.remove(current);
        this.minecraft.setScreen(new ModIdInputScreen(this,
                Component.translatable(index >= 0 ? KEY + "edit_title" : KEY + "add_title"),
                current, taken, unlistedOreMods(), mod -> {
                    if (index >= 0) order.set(index, mod);
                    else order.add(mod);
                }));
    }

    @Override
    public void tick() {
        // Removed on the next tick: the click is still being dispatched to the row being removed.
        if (pendingRemoval >= 0 && pendingRemoval < order.size()) {
            order.remove(pendingRemoval);
            list.refresh();
        }
        pendingRemoval = -1;
    }

    private void onDone() {
        onSave.accept(List.copyOf(order));
        this.minecraft.setScreen(parent);
    }

    public static void saveToConfig(List<String> priorities) {
        OreDepositConfig.MOD_PRIORITIES.set(priorities);
        OreDepositConfig.SPEC.save();
        // Canonical ores are picked from these priorities; the settings screen rebuilds its rows from them.
        ConfigReloader.reload();
        OresAndDrillsMod.LOGGER.debug("Ore deposits: mod priorities set to {}", priorities);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(guiGraphics, mouseX, mouseY, partialTick);
        super.render(guiGraphics, mouseX, mouseY, partialTick);

        // The world-creation blur and widget backgrounds render below this final foreground pass.
        guiGraphics.pose().pushPose();
        guiGraphics.pose().translate(0.0F, 0.0F, 300.0F);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, MARGIN - 6, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, Component.translatable(KEY + "hint"), this.width / 2, MARGIN + 10, 0xA0A0A0);
        if (order.isEmpty()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable(KEY + "empty"),
                    this.width / 2, list.getY() + 12, 0x808080);
        }
        if (OreUnifier.hasAlmostUnifiedPriorities()) {
            guiGraphics.drawCenteredString(this.font, Component.translatable(KEY + "almost_unified"),
                    this.width / 2, this.height - 42, 0xFFD080);
        }
        guiGraphics.pose().popPose();
    }

    static Component modName(String mod) {
        return ModList.get().getModContainerById(mod)
                .map(container -> (Component) Component.literal(container.getModInfo().getDisplayName()))
                .orElse(Component.literal(mod));
    }

    /** Rows are reordered by dragging the grip; edit and remove buttons sit to its left. */
    private final class PriorityList extends ContainerObjectSelectionList<PriorityList.Row> {
        private static final int ROW_BACKGROUND = 0x30FFFFFF;
        private static final int ROW_BACKGROUND_HOVERED = 0x50FFFFFF;
        private static final int PLACEHOLDER = 0x40000000;
        private static final int DRAGGED_BACKGROUND = 0xE0303030;
        private static final int DRAGGED_BORDER = 0xFFFFFFFF;
        private static final int INSERT_LINE = 0xFFFFD080;
        private static final int EDGE_SCROLL_ZONE = 16;
        private static final int CONTROL = FlatIconButton.SIZE;
        private static final int CONTROL_GAP = 2;
        private static final int NUMBER_COLOR = 0xFF909090;
        private static final int NAME_COLOR = 0xFFFFFFFF;
        private static final int ID_COLOR = 0xFF9A9A9A;
        private static final int MISSING_COLOR = 0xFFA0A0A0;
        private static final Component EDIT_ICON = Component.literal("✎");
        private static final Component REMOVE_ICON = Component.literal("✕");

        private int draggedIndex = -1;
        private int dragGrabOffset;
        private int dragMouseY;

        PriorityList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);
            refresh();
        }

        void refresh() {
            double scroll = getScrollAmount();
            clearEntries();
            for (int index = 0; index < order.size(); index++) addEntry(new Row(index));
            setScrollAmount(scroll);
        }

        @Override
        public int getRowWidth() {
            return Math.min(ROW_WIDTH, this.width - 40);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0 && mouseX < getScrollbarPosition() && isMouseOver(mouseX, mouseY)) {
                Row row = getEntryAtPosition(mouseX, mouseY);
                // Only the grip on the right picks a row up; the rest of the row is a label and buttons.
                if (row != null && overHandle(mouseX)) {
                    draggedIndex = row.index;
                    dragGrabOffset = (int) mouseY - getRowTop(row.index);
                    dragMouseY = (int) mouseY;
                    return true;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            if (draggedIndex >= 0) {
                dragMouseY = (int) mouseY;
                return true;
            }
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        @Override
        public boolean mouseReleased(double mouseX, double mouseY, int button) {
            if (draggedIndex >= 0 && button == 0) {
                int slot = insertionSlot();
                int target = slot > draggedIndex ? slot - 1 : slot;
                if (target != draggedIndex) order.add(target, order.remove(draggedIndex));
                draggedIndex = -1;
                refresh();
                return true;
            }
            return super.mouseReleased(mouseX, mouseY, button);
        }

        private int handleLeft() {
            return getRowLeft() + getRowWidth() - CONTROL;
        }

        private int removeLeft() {
            return handleLeft() - CONTROL_GAP - CONTROL;
        }

        private int editLeft() {
            return removeLeft() - CONTROL_GAP - CONTROL;
        }

        private static int controlTop(int rowTop, int rowHeight) {
            return rowTop + (rowHeight - CONTROL) / 2;
        }

        private boolean overHandle(double mouseX) {
            return mouseX >= handleLeft() && mouseX < handleLeft() + CONTROL;
        }

        /** Grip: the same flat square as the buttons, with three horizontal bars instead of an icon. */
        private void drawHandle(GuiGraphics guiGraphics, int rowTop, int rowHeight, boolean hovered) {
            int left = handleLeft();
            int top = controlTop(rowTop, rowHeight);
            guiGraphics.fill(left, top, left + CONTROL, top + CONTROL,
                    hovered ? FlatIconButton.BACKGROUND_HOVERED : FlatIconButton.BACKGROUND);
            int grip = hovered ? FlatIconButton.ICON_HOVERED : FlatIconButton.ICON;
            int centerY = top + CONTROL / 2;
            for (int offset = -4; offset <= 4; offset += 4) {
                guiGraphics.fill(left + 5, centerY + offset - 1, left + CONTROL - 5, centerY + offset + 1, grip);
            }
        }

        /** Gap the dragged row would drop into, 0..size, from the dragged row's center. */
        private int insertionSlot() {
            int center = dragMouseY - dragGrabOffset + itemHeight / 2;
            int slot = Math.round((center - getRowTop(0)) / (float) itemHeight);
            return Math.max(0, Math.min(order.size(), slot));
        }

        @Override
        public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            if (draggedIndex >= 0) {
                dragMouseY = mouseY;
                // Holding the row near an edge scrolls, so long lists can be reordered end to end.
                if (mouseY < getY() + EDGE_SCROLL_ZONE) setScrollAmount(getScrollAmount() - 4);
                else if (mouseY > getBottom() - EDGE_SCROLL_ZONE) setScrollAmount(getScrollAmount() + 4);
            }
            super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
            if (draggedIndex < 0 || draggedIndex >= order.size()) return;

            int left = getRowLeft();
            int right = left + getRowWidth();
            int slot = insertionSlot();
            int lineY = slot < order.size() ? getRowTop(slot) - 2 : getRowBottom(order.size() - 1) + 1;
            guiGraphics.enableScissor(getX(), getY(), getRight(), getBottom());
            guiGraphics.fill(left - 2, lineY - 1, right + 2, lineY + 1, INSERT_LINE);
            guiGraphics.disableScissor();

            int top = Math.max(getY(), Math.min(getBottom() - itemHeight, dragMouseY - dragGrabOffset));
            guiGraphics.pose().pushPose();
            guiGraphics.pose().translate(0.0F, 0.0F, 200.0F);
            int height = itemHeight - 4;
            guiGraphics.fill(left - 2, top, right + 2, top + height, DRAGGED_BACKGROUND);
            guiGraphics.renderOutline(left - 2, top, right - left + 4, height, DRAGGED_BORDER);
            drawRowText(guiGraphics, draggedIndex, left, top, height);
            FlatIconButton.draw(guiGraphics, editLeft(), controlTop(top, height), EDIT_ICON, false);
            FlatIconButton.draw(guiGraphics, removeLeft(), controlTop(top, height), REMOVE_ICON, false);
            drawHandle(guiGraphics, top, height, true);
            guiGraphics.pose().popPose();
        }

        /** Number, name and id; the id keeps its colour when the line is cut to fit before the controls. */
        private void drawRowText(GuiGraphics guiGraphics, int index, int left, int top, int height) {
            var font = Minecraft.getInstance().font;
            String mod = order.get(index);
            int textY = top + (height - font.lineHeight) / 2 + 1;
            int numberWidth = font.width(order.size() + ".");
            String number = (index + 1) + ".";
            guiGraphics.drawString(font, number, left + 4 + numberWidth - font.width(number), textY, NUMBER_COLOR, false);

            int x = left + 4 + numberWidth + 6;
            int limit = editLeft() - 6;
            if (ModList.get().isLoaded(mod)) {
                x = drawClipped(guiGraphics, modName(mod).getString(), x, textY, limit, NAME_COLOR);
                drawClipped(guiGraphics, " (" + mod + ")", x, textY, limit, ID_COLOR);
            } else {
                x = drawClipped(guiGraphics, mod, x, textY, limit, MISSING_COLOR);
                drawClipped(guiGraphics, Component.translatable(KEY + "not_installed").getString(), x, textY, limit, ID_COLOR);
            }
        }

        /** Draws text up to {@code limit}, ending with an ellipsis when cut; returns where the next part starts. */
        private int drawClipped(GuiGraphics guiGraphics, String text, int x, int y, int limit, int color) {
            var font = Minecraft.getInstance().font;
            if (x >= limit) return limit;
            if (x + font.width(text) <= limit) {
                guiGraphics.drawString(font, text, x, y, color, false);
                return x + font.width(text);
            }
            String cut = font.plainSubstrByWidth(text, Math.max(0, limit - x - font.width("…"))) + "…";
            guiGraphics.drawString(font, cut, x, y, color, false);
            return limit;
        }

        private final class Row extends ContainerObjectSelectionList.Entry<Row> {
            private final int index;
            private final FlatIconButton edit;
            private final FlatIconButton remove;

            Row(int index) {
                this.index = index;
                this.edit = new FlatIconButton(EDIT_ICON, Component.translatable(KEY + "edit"), button -> openEditor(index));
                this.remove = new FlatIconButton(REMOVE_ICON, Component.translatable(KEY + "remove"), button -> pendingRemoval = index);
            }

            @Override
            public void render(GuiGraphics guiGraphics, int rowIndex, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                if (index == draggedIndex) {
                    // The row is drawn under the cursor; leave an empty slot where it was picked up.
                    guiGraphics.fill(left - 2, top, left + width + 2, top + height, PLACEHOLDER);
                    return;
                }
                boolean handleHovered = hovering && draggedIndex < 0 && overHandle(mouseX);
                guiGraphics.fill(left - 2, top, left + width + 2, top + height, handleHovered ? ROW_BACKGROUND_HOVERED : ROW_BACKGROUND);
                drawRowText(guiGraphics, index, left, top, height);
                edit.setPosition(editLeft(), controlTop(top, height));
                remove.setPosition(removeLeft(), controlTop(top, height));
                edit.render(guiGraphics, mouseX, mouseY, partialTick);
                remove.render(guiGraphics, mouseX, mouseY, partialTick);
                drawHandle(guiGraphics, top, height, handleHovered);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(edit, remove);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(edit, remove);
            }
        }
    }
}

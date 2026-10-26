package dev.client.config;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/**
 * Edits a list of strings, one text field per entry; "Done" stays disabled while an entry is
 * invalid.
 */
final class StringListScreen extends Screen {
    private static final int MARGIN = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int VALID_COLOR = 0xE0E0E0;
    private static final int INVALID_COLOR = 0xFF5555;

    private final Screen parent;
    private final Component hint;
    private final Predicate<String> valid;
    private final Consumer<List<String>> onDone;
    private final List<String> values;
    private EntryList list;
    private Button done;
    private int pendingRemoval = -1;
    private boolean pendingAdd;

    StringListScreen(
            Screen parent,
            Component title,
            Component hint,
            List<String> values,
            Predicate<String> valid,
            Consumer<List<String>> onDone) {
        super(title);
        this.parent = parent;
        this.hint = hint;
        this.valid = valid;
        this.onDone = onDone;
        this.values = new ArrayList<>(values);
    }

    @Override
    protected void init() {
        int listY = MARGIN + 32;
        list =
                addRenderableWidget(
                        new EntryList(
                                this.minecraft,
                                this.width,
                                Math.max(48, this.height - listY - 36),
                                listY));
        int buttonY = this.height - 28;
        addRenderableWidget(
                Button.builder(
                                Component.translatable("gui.ores_and_drills.settings.list.add"),
                                button -> pendingAdd = true)
                        .bounds(this.width / 2 - 155, buttonY, 100, 20)
                        .build());
        done =
                addRenderableWidget(
                        Button.builder(
                                        Component.translatable(
                                                "gui.ores_and_drills.ore_settings.done"),
                                        button -> {
                                            onDone.accept(
                                                    values.stream()
                                                            .map(String::trim)
                                                            .filter(value -> !value.isEmpty())
                                                            .toList());
                                            this.minecraft.setScreen(parent);
                                        })
                                .bounds(this.width / 2 - 50, buttonY, 100, 20)
                                .build());
        addRenderableWidget(
                Button.builder(
                                Component.translatable("gui.ores_and_drills.ore_settings.cancel"),
                                button -> onClose())
                        .bounds(this.width / 2 + 55, buttonY, 100, 20)
                        .build());
    }

    @Override
    public void tick() {
        // Rows change on the next tick: the click that asked for it is still being dispatched to a
        // row.
        if (pendingRemoval >= 0 && pendingRemoval < values.size()) {
            values.remove(pendingRemoval);
            list.refresh();
        }
        pendingRemoval = -1;
        if (pendingAdd) {
            pendingAdd = false;
            values.add("");
            list.refresh();
            list.setScrollAmount(list.getMaxScroll());
        }
        done.active =
                values.stream()
                        .map(String::trim)
                        .allMatch(value -> value.isEmpty() || valid.test(value));
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, MARGIN - 6, 0xFFFFFF);
        guiGraphics.drawCenteredString(this.font, hint, this.width / 2, MARGIN + 10, 0xA0A0A0);
    }

    private final class EntryList extends ContainerObjectSelectionList<EntryList.Row> {
        EntryList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);
            refresh();
        }

        void refresh() {
            double scroll = getScrollAmount();
            clearEntries();
            for (int index = 0; index < values.size(); index++) addEntry(new Row(index));
            setScrollAmount(scroll);
        }

        @Override
        public int getRowWidth() {
            return Math.min(320, this.width - 40);
        }

        private final class Row extends ContainerObjectSelectionList.Entry<Row> {
            private final EditBox field;
            private final Button remove;

            Row(int index) {
                field =
                        new EditBox(
                                Minecraft.getInstance().font,
                                0,
                                0,
                                getRowWidth() - 24,
                                20,
                                Component.empty());
                field.setMaxLength(256);
                field.setValue(values.get(index));
                field.setResponder(
                        text -> {
                            values.set(index, text);
                            field.setTextColor(
                                    text.isBlank() || valid.test(text.trim())
                                            ? VALID_COLOR
                                            : INVALID_COLOR);
                        });
                field.setTextColor(
                        valid.test(values.get(index).trim()) ? VALID_COLOR : INVALID_COLOR);
                remove =
                        Button.builder(Component.literal("✕"), button -> pendingRemoval = index)
                                .size(20, 20)
                                .build();
            }

            @Override
            public void render(
                    GuiGraphics guiGraphics,
                    int rowIndex,
                    int top,
                    int left,
                    int width,
                    int height,
                    int mouseX,
                    int mouseY,
                    boolean hovering,
                    float partialTick) {
                field.setPosition(left, top + 1);
                remove.setPosition(left + width - 20, top + 1);
                field.render(guiGraphics, mouseX, mouseY, partialTick);
                remove.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(field, remove);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(field, remove);
            }
        }
    }
}

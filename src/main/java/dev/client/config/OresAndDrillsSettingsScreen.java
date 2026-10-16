package dev.client.config;

import dev.OresAndDrillsMod;
import dev.client.worldcreation.ModPrioritiesScreen;
import dev.config.ConfigReloader;
import dev.config.OreDepositConfig;
import dev.config.OreSettingsPresetManager;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.common.ModConfigSpec;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The mod's own config screen (mod list "Config" button). Edits are held here and written to the common
 * config together on "Done"; "Cancel" discards all of them, including mod priorities and excluded ores.
 */
public final class OresAndDrillsSettingsScreen extends Screen {
    private static final int MARGIN = 20;
    private static final int ROW_HEIGHT = 24;
    private static final int WIDGET_WIDTH = 150;
    private static final String KEY = "gui.ores_and_drills.settings.";

    private final Screen parent;
    private final Values values;
    private SettingsList list;

    /** Pending edits; survives init() so resizing and sub-screens keep them. */
    private static final class Values {
        int minSpacing;
        int maxSpacing;
        double growth;
        double maxMultiplier;
        List<String> disabledOres;
        List<String> modPriorities;
        String preset;

        static Values current() {
            Values values = new Values();
            values.minSpacing = get(OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING);
            values.maxSpacing = get(OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING);
            values.growth = get(OreDepositConfig.ORE_MINING_EXPONENTIAL_GROWTH);
            values.maxMultiplier = get(OreDepositConfig.ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER);
            values.disabledOres = List.copyOf(get(OreDepositConfig.DISABLED_DEPOSIT_ORES));
            values.modPriorities = List.copyOf(get(OreDepositConfig.MOD_PRIORITIES));
            values.preset = get(OreDepositConfig.ACTIVE_PRESET);
            return values;
        }

        static Values defaults() {
            Values values = new Values();
            values.minSpacing = OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING.getDefault();
            values.maxSpacing = OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING.getDefault();
            values.growth = OreDepositConfig.ORE_MINING_EXPONENTIAL_GROWTH.getDefault();
            values.maxMultiplier = OreDepositConfig.ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER.getDefault();
            values.disabledOres = List.copyOf(OreDepositConfig.DISABLED_DEPOSIT_ORES.getDefault());
            values.modPriorities = List.copyOf(OreDepositConfig.MOD_PRIORITIES.getDefault());
            values.preset = OreDepositConfig.ACTIVE_PRESET.getDefault();
            return values;
        }

        private static <T> T get(ModConfigSpec.ConfigValue<T> value) {
            try {
                return value.get();
            } catch (IllegalStateException exception) {
                return value.getDefault();
            }
        }
    }

    public OresAndDrillsSettingsScreen(Screen parent) {
        super(Component.translatable(KEY + "title"));
        this.parent = parent;
        this.values = Values.current();
    }

    @Override
    protected void init() {
        int listY = MARGIN + 16;
        double scroll = list == null ? 0.0D : list.getScrollAmount();
        list = addRenderableWidget(new SettingsList(this.minecraft, this.width, Math.max(48, this.height - listY - 36), listY));
        populate();
        list.setScrollAmount(scroll);

        int buttonY = this.height - 28;
        addRenderableWidget(Button.builder(Component.translatable(KEY + "reset"), button -> {
            Values defaults = Values.defaults();
            copy(defaults, values);
            rebuildWidgets();
        }).bounds(this.width / 2 - 155, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.done"), button -> onDone())
                .bounds(this.width / 2 - 50, buttonY, 100, 20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.ores_and_drills.ore_settings.cancel"), button -> onClose())
                .bounds(this.width / 2 + 55, buttonY, 100, 20).build());
    }

    private void populate() {
        int min = OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING_LIMIT;
        int max = OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING_LIMIT;

        list.section("deposits");
        list.setting("min_spacing", new RangeSlider(WIDGET_WIDTH, min, max, 10, values.minSpacing,
                value -> blocks(value), value -> values.minSpacing = (int) value));
        list.setting("max_spacing", new RangeSlider(WIDGET_WIDTH, min, max, 10, values.maxSpacing,
                value -> blocks(value), value -> values.maxSpacing = (int) value));

        list.section("mining");
        list.setting("growth", new RangeSlider(WIDGET_WIDTH, 0.0D, 1.0D, 0.01D, values.growth,
                value -> Component.literal(String.format(Locale.ROOT, "%.2f", value)), value -> values.growth = value));
        list.setting("max_multiplier", new RangeSlider(WIDGET_WIDTH, 1.0D, 100.0D, 0.5D, values.maxMultiplier,
                value -> Component.literal(String.format(Locale.ROOT, "×%.1f", value)), value -> values.maxMultiplier = value));

        list.section("generation");
        list.setting("preset", presetWidget());
        list.setting("disabled_ores", Button.builder(
                Component.translatable(KEY + "edit", values.disabledOres.size()),
                button -> this.minecraft.setScreen(new StringListScreen(this,
                        Component.translatable(KEY + "disabled_ores"),
                        Component.translatable(KEY + "disabled_ores.hint"),
                        values.disabledOres,
                        OresAndDrillsSettingsScreen::isBlockOrTagId,
                        edited -> values.disabledOres = List.copyOf(edited))))
                .size(WIDGET_WIDTH, 20).build());
        list.setting("mod_priorities", Button.builder(Component.translatable(KEY + "configure"),
                button -> this.minecraft.setScreen(new ModPrioritiesScreen(this, values.modPriorities,
                        edited -> values.modPriorities = List.copyOf(edited))))
                .size(WIDGET_WIDTH, 20).build());
    }

    private static Component blocks(double value) {
        return Component.translatable(KEY + "blocks", (int) value);
    }

    /** Presets come from datapacks, so outside a world the list may be unknown; then the id is typed in. */
    private AbstractWidget presetWidget() {
        Set<ResourceLocation> known = new LinkedHashSet<>(OreSettingsPresetManager.presets().keySet());
        ResourceLocation current = ResourceLocation.tryParse(values.preset);
        if (current != null) known.add(current);
        if (known.size() > 1 && current != null) {
            return CycleButton.<ResourceLocation>builder(id -> Component.literal(id.toString()))
                    .withValues(List.copyOf(known)).withInitialValue(current).displayOnlyValue()
                    .create(0, 0, WIDGET_WIDTH, 20, Component.empty(), (button, value) -> values.preset = value.toString());
        }
        EditBox box = new EditBox(this.font, 0, 0, WIDGET_WIDTH, 20, Component.empty());
        box.setMaxLength(256);
        box.setValue(values.preset);
        box.setResponder(text -> {
            values.preset = text.trim();
            box.setTextColor(ResourceLocation.tryParse(values.preset) != null ? 0xE0E0E0 : 0xFF5555);
        });
        return box;
    }

    private static boolean isBlockOrTagId(String id) {
        String location = id.startsWith("#") ? id.substring(1) : id;
        return !location.isEmpty() && ResourceLocation.tryParse(location) != null;
    }

    private static void copy(Values from, Values to) {
        to.minSpacing = from.minSpacing;
        to.maxSpacing = from.maxSpacing;
        to.growth = from.growth;
        to.maxMultiplier = from.maxMultiplier;
        to.disabledOres = from.disabledOres;
        to.modPriorities = from.modPriorities;
        to.preset = from.preset;
    }

    private void onDone() {
        OreDepositConfig.MIN_LARGE_DEPOSIT_SPACING.set(Math.min(values.minSpacing, values.maxSpacing));
        OreDepositConfig.MAX_LARGE_DEPOSIT_SPACING.set(Math.max(values.minSpacing, values.maxSpacing));
        OreDepositConfig.ORE_MINING_EXPONENTIAL_GROWTH.set(values.growth);
        OreDepositConfig.ORE_MINING_MAX_EXPONENTIAL_MULTIPLIER.set(values.maxMultiplier);
        OreDepositConfig.DISABLED_DEPOSIT_ORES.set(values.disabledOres);
        OreDepositConfig.MOD_PRIORITIES.set(values.modPriorities);
        if (ResourceLocation.tryParse(values.preset) != null) OreDepositConfig.ACTIVE_PRESET.set(values.preset);
        OreDepositConfig.SPEC.save();
        ConfigReloader.reload();
        OresAndDrillsMod.LOGGER.debug("Ores & Drills: settings saved from the config screen");
        this.minecraft.setScreen(parent);
    }

    @Override
    public void onClose() {
        this.minecraft.setScreen(parent);
    }

    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.render(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.drawCenteredString(this.font, this.title, this.width / 2, MARGIN - 6, 0xFFFFFF);
    }

    private final class SettingsList extends ContainerObjectSelectionList<SettingsList.Row> {
        SettingsList(Minecraft minecraft, int width, int height, int y) {
            super(minecraft, width, height, y, ROW_HEIGHT);
        }

        void section(String key) {
            addEntry(new SectionRow(Component.translatable(KEY + "section." + key)));
        }

        void setting(String key, AbstractWidget widget) {
            Component description = Component.translatable(KEY + key + ".tooltip");
            widget.setTooltip(Tooltip.create(description));
            addEntry(new SettingRow(Component.translatable(KEY + key), description, widget));
        }

        @Override
        public int getRowWidth() {
            return Math.min(420, this.width - 40);
        }

        abstract class Row extends ContainerObjectSelectionList.Entry<Row> {
        }

        private final class SectionRow extends Row {
            private final Component title;

            SectionRow(Component title) {
                this.title = title.copy().withStyle(ChatFormatting.BOLD, ChatFormatting.YELLOW);
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                var font = Minecraft.getInstance().font;
                int textY = top + height - font.lineHeight - 3;
                guiGraphics.drawString(font, title, left, textY, 0xFFFFFF);
                guiGraphics.hLine(left, left + width, top + height - 1, 0x60FFFFFF);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of();
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of();
            }
        }

        private final class SettingRow extends Row {
            private final Component label;
            private final Component description;
            private final AbstractWidget widget;

            SettingRow(Component label, Component description, AbstractWidget widget) {
                this.label = label;
                this.description = description;
                this.widget = widget;
            }

            @Override
            public void render(GuiGraphics guiGraphics, int index, int top, int left, int width, int height,
                               int mouseX, int mouseY, boolean hovering, float partialTick) {
                var font = Minecraft.getInstance().font;
                int labelWidth = width - WIDGET_WIDTH - 8;
                String text = label.getString();
                if (font.width(text) > labelWidth) {
                    text = font.plainSubstrByWidth(text, labelWidth - font.width("…")) + "…";
                }
                int textY = top + (height - font.lineHeight) / 2;
                guiGraphics.drawString(font, text, left, textY, 0xE0E0E0);
                if (hovering && mouseX < left + labelWidth) {
                    // The label explains the setting too, not only the widget's own tooltip.
                    setTooltipForNextRenderPass(font.split(label.copy().append("\n").append(
                            description.copy().withStyle(ChatFormatting.GRAY)), 260));
                }
                widget.setPosition(left + width - WIDGET_WIDTH, top + (height - 20) / 2);
                widget.render(guiGraphics, mouseX, mouseY, partialTick);
            }

            @Override
            public List<? extends GuiEventListener> children() {
                return List.of(widget);
            }

            @Override
            public List<? extends NarratableEntry> narratables() {
                return List.of(widget);
            }
        }
    }
}

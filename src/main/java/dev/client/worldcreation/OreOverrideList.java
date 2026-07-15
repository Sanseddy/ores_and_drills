package dev.client.worldcreation;

import dev.config.OreOverrides;
import dev.world.level.levelgen.OreSpawnDimensions;
import dev.world.level.levelgen.OreUnifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.ContainerObjectSelectionList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.narration.NarratableEntry;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.locale.Language;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.fml.ModList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Scrollable, Excel-style table grouped by mod namespace. Each material row groups stone/deepslate/nether variants
 * via {@link OreUnifier#materialKeyFor(Block)} and exposes one enabled toggle plus 3 generation sliders.
 */
public final class OreOverrideList extends ContainerObjectSelectionList<OreOverrideList.Entry> {
    public static final int ROW_HEIGHT = 24;
    public static final int TABLE_X_OFFSET = -8;
    private static final int SCROLLBAR_GAP = 4;
    private static final int HEADER_HEIGHT = 24;
    private static final int DIMENSION_LINE_HEIGHT = 10;
    private static final int ROW_VERTICAL_PADDING = 6;
    private static final int HEADER_BACKGROUND = 0x80202020;
    private static final int HEADER_BORDER = 0x60FFFFFF;
    private static final int HEADER_TEXT = 0xD0D0D0;
    private static final int CATEGORY_BACKGROUND = 0x60303030;
    private static final int CATEGORY_BACKGROUND_HOVERED = 0x80505050;
    private static final int CATEGORY_TEXT = 0xFFE0E0E0;
    private static final int MIN_TABLE_WIDTH = 320;
    private static final int MAX_TABLE_WIDTH = 600;
    private static final int HORIZONTAL_MARGIN = 40;
    private static final int MIN_LABEL_WIDTH = 110;
    private static final int MAX_LABEL_WIDTH = 190;
    private static final int MIN_DIMENSION_WIDTH = 70;
    private static final int MAX_DIMENSION_WIDTH = 90;
    private static final int MAX_SLIDER_WIDTH = 110;
    private static final int SLIDER_GAP = 0;
    private static final double MULTIPLIER_MIN = 0.1D;
    private static final double MULTIPLIER_MAX = 5.0D;
    private static final int ROW_BACKGROUND_EVEN = 0x20FFFFFF;
    private static final int ROW_BACKGROUND_ODD = 0x10FFFFFF;
    private static final int ROW_BORDER = 0x40FFFFFF;
    private static final int ENABLED_TOGGLE_SIZE = 14;
    private static final int CHECKBOX_COLUMN_WIDTH = 20;
    private static final int SLIDER_INSET = 1;

    private final int rowWidth;
    private final int labelWidth;
    private final int dimensionWidth;
    private final int sliderWidth;
    private final List<ModGroup> groups = new ArrayList<>();
    private final SquareToggle masterToggle = new SquareToggle(0, 0, true);

    public OreOverrideList(
            Minecraft minecraft,
            int width,
            int height,
            int y,
            List<Block> canonicalOres,
            Map<ResourceLocation, OreOverrides.OreOverride> initial,
            Set<ResourceLocation> initialDisabled
    ) {
        super(minecraft, width, height, y, rowHeightFor(canonicalOres));
        setRenderHeader(true, HEADER_HEIGHT);
        int targetRowWidth = Math.min(MAX_TABLE_WIDTH, Math.max(MIN_TABLE_WIDTH, width - HORIZONTAL_MARGIN));
        this.labelWidth = Math.min(MAX_LABEL_WIDTH, Math.max(MIN_LABEL_WIDTH, targetRowWidth * 30 / 100));
        this.dimensionWidth = Math.min(MAX_DIMENSION_WIDTH, Math.max(MIN_DIMENSION_WIDTH, targetRowWidth * 16 / 100));
        this.sliderWidth = Math.min(MAX_SLIDER_WIDTH, Math.max(45, (targetRowWidth - labelWidth - dimensionWidth - 2 * SLIDER_GAP) / 3));
        this.rowWidth = CHECKBOX_COLUMN_WIDTH + labelWidth + dimensionWidth + 3 * sliderWidth + 2 * SLIDER_GAP;

        Map<String, List<ResourceLocation>> byMaterial = new LinkedHashMap<>();
        for (Block block : canonicalOres) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }

            String material = OreUnifier.materialKeyFor(block);
            byMaterial.computeIfAbsent(material.isEmpty() ? id.getPath() : material, ignored -> new ArrayList<>()).add(id);
        }

        List<MaterialRowData> materials = new ArrayList<>();
        for (Map.Entry<String, List<ResourceLocation>> entry : byMaterial.entrySet()) {
            String material = entry.getKey();
            List<ResourceLocation> members = List.copyOf(entry.getValue());
            ResourceLocation preferred = preferredDisplayMember(material, members);
            ResourceLocation categoryId = preferred != null ? preferred : members.getFirst();
            OreOverrides.OreOverride existing = firstNonDefault(members, initial);
            boolean enabled = members.stream().noneMatch(initialDisabled::contains);
            materials.add(new MaterialRowData(
                    material,
                    categoryId.getNamespace(),
                    members,
                    displayName(material, members),
                    dimensionInfo(members),
                    existing,
                    enabled,
                    bestPriorityRank(members),
                    rarityRank(material, members)
            ));
        }

        materials.sort(Comparator
                .comparingInt(MaterialRowData::modPriorityRank)
                .thenComparing(MaterialRowData::namespace)
                .thenComparingInt(MaterialRowData::rarityRank)
                .thenComparing(MaterialRowData::material));

        Map<String, ModGroup> byNamespace = new LinkedHashMap<>();
        for (MaterialRowData material : materials) {
            ModGroup group = byNamespace.computeIfAbsent(material.namespace(), ModGroup::new);
            group.rows.add(new OreRow(material));
        }

        groups.addAll(byNamespace.values());
        rebuildEntries();
    }

    private void rebuildEntries() {
        double scroll = getScrollAmount();
        clearEntries();
        for (ModGroup group : groups) {
            addEntry(new CategoryRow(group));
            if (group.expanded) {
                for (OreRow row : group.rows) {
                    addEntry(row);
                }
            }
        }
        setScrollAmount(scroll);
    }

    /** Sorts material rows by the same mod-priority order OreUnifier uses to pick canonical ores. */
    private static int bestPriorityRank(List<ResourceLocation> members) {
        int best = Integer.MAX_VALUE;
        for (ResourceLocation member : members) {
            Block block = BuiltInRegistries.BLOCK.get(member);
            if (block != null) {
                best = Math.min(best, OreUnifier.modPriorityRank(block));
            }
        }
        return best;
    }

    private static OreOverrides.OreOverride firstNonDefault(List<ResourceLocation> members, Map<ResourceLocation, OreOverrides.OreOverride> initial) {
        for (ResourceLocation member : members) {
            OreOverrides.OreOverride override = initial.get(member);
            if (override != null) {
                return override;
            }
        }
        return OreOverrides.DEFAULT;
    }

    private static int rarityRank(String material, List<ResourceLocation> members) {
        String text = (material + " " + members).toLowerCase(Locale.ROOT);
        if (containsAny(text, "coal", "copper", "iron")) {
            return 0;
        }
        if (containsAny(text, "tin", "zinc", "lead", "aluminum", "aluminium", "nickel", "sulfur", "fluorite", "apatite")) {
            return 1;
        }
        if (containsAny(text, "gold", "redstone", "lapis", "quartz", "osmium", "silver", "cinnabar")) {
            return 2;
        }
        if (containsAny(text, "diamond", "emerald", "uranium", "uraninite", "ancient_debris", "platinum", "iridium")) {
            return 3;
        }
        return 1;
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) {
            if (value.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private static int rowHeightFor(List<Block> canonicalOres) {
        Map<String, List<ResourceLocation>> byMaterial = new LinkedHashMap<>();
        for (Block block : canonicalOres) {
            ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
            if (id == null) {
                continue;
            }

            String material = OreUnifier.materialKeyFor(block);
            byMaterial.computeIfAbsent(material.isEmpty() ? id.getPath() : material, ignored -> new ArrayList<>()).add(id);
        }

        int maxDimensions = 1;
        for (List<ResourceLocation> members : byMaterial.values()) {
            maxDimensions = Math.max(maxDimensions, dimensionKeys(members).size());
        }
        return Math.max(ROW_HEIGHT, ROW_VERTICAL_PADDING + maxDimensions * DIMENSION_LINE_HEIGHT);
    }

    @Override
    public int getRowWidth() {
        return rowWidth;
    }

    @Override
    public int getRowLeft() {
        return super.getRowLeft() + TABLE_X_OFFSET;
    }

    @Override
    protected int getScrollbarPosition() {
        // Vanilla anchors the scrollbar to the list's un-shifted row bounds, so it doesn't follow our
        // TABLE_X_OFFSET and can end up past the widget's right edge (invisible) once the table is
        // forced to MIN_TABLE_WIDTH on a narrow/high-GUI-scale screen. Deriving it from our own
        // getRowRight() keeps it glued to the table, and clamping keeps it on-screen either way.
        return Math.min(getRowRight() + SCROLLBAR_GAP, getX() + getWidth() - SCROLLBAR_WIDTH);
    }

    public int labelWidth() {
        return labelWidth;
    }

    public int dimensionWidth() {
        return dimensionWidth;
    }

    public int sliderWidth() {
        return sliderWidth;
    }

    public int sliderGap() {
        return SLIDER_GAP;
    }

    public int frequencyColumnX(int rowLeft) {
        return dimensionColumnX(rowLeft) + dimensionWidth;
    }

    public int dimensionColumnX(int rowLeft) {
        return labelColumnX(rowLeft) + labelWidth;
    }

    public int checkboxColumnX(int rowLeft) {
        return rowLeft;
    }

    public int labelColumnX(int rowLeft) {
        return rowLeft + CHECKBOX_COLUMN_WIDTH;
    }

    public int sizeColumnX(int rowLeft) {
        return frequencyColumnX(rowLeft) + sliderWidth + SLIDER_GAP;
    }

    public int richnessColumnX(int rowLeft) {
        return sizeColumnX(rowLeft) + sliderWidth + SLIDER_GAP;
    }

    @Override
    public void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        super.renderWidget(guiGraphics, mouseX, mouseY, partialTick);
        renderStickyHeader(guiGraphics, mouseX, mouseY, partialTick);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && isMasterToggleHit(mouseX, mouseY)) {
            toggleAllEnabled();
            return true;
        }
        if (isWithinHeaderBounds(mouseX, mouseY)) {
            // The sticky header is a render-only overlay: rows scrolled up past it are only hidden by a
            // scissor in renderListItems, not actually removed, so without this guard a click here would
            // fall through to whatever row is currently scrolled underneath instead of hitting nothing.
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    private boolean isMasterToggleHit(double mouseX, double mouseY) {
        int x = masterToggleX();
        int y = masterToggleY();
        return mouseX >= x && mouseX < x + ENABLED_TOGGLE_SIZE && mouseY >= y && mouseY < y + ENABLED_TOGGLE_SIZE;
    }

    private boolean isWithinHeaderBounds(double mouseX, double mouseY) {
        int rowLeft = getRowLeft();
        int rowRight = rowLeft + getRowWidth();
        return mouseX >= rowLeft && mouseX < rowRight && mouseY >= getY() && mouseY < getY() + HEADER_HEIGHT;
    }

    private int masterToggleX() {
        return checkboxColumnX(getRowLeft()) + (CHECKBOX_COLUMN_WIDTH - ENABLED_TOGGLE_SIZE) / 2;
    }

    private int masterToggleY() {
        return getY() + (HEADER_HEIGHT - ENABLED_TOGGLE_SIZE) / 2;
    }

    private void toggleAllEnabled() {
        boolean newState = !anyRowEnabled();
        for (ModGroup group : groups) {
            for (OreRow row : group.rows) {
                row.enabledToggle.setSelected(newState);
            }
        }
    }

    /** The header toggle shows "on" whenever at least one ore is enabled, and "off" only when every ore is disabled. */
    private boolean anyRowEnabled() {
        for (ModGroup group : groups) {
            for (OreRow row : group.rows) {
                if (row.enabledToggle.selected()) {
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    protected void renderListItems(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // Rows scrolled up past the sticky header are only hidden by the scissor below, not actually
        // removed — the entry underneath the header still gets a render(..., mouseX, mouseY, ...) call
        // with the real cursor position, so its hover highlight / slider tooltip would otherwise render
        // right through the header. Feeding an off-screen position while the cursor is over the header
        // makes every row (and its child widgets) correctly compute isHovered = false for this pass.
        if (isWithinHeaderBounds(mouseX, mouseY)) {
            mouseX = -1;
            mouseY = -1;
        }

        guiGraphics.enableScissor(getX(), getY() + HEADER_HEIGHT + 1, getX() + getWidth(), getY() + height);
        super.renderListItems(guiGraphics, mouseX, mouseY, partialTick);
        guiGraphics.disableScissor();
    }

    private void renderStickyHeader(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        int rowLeft = getRowLeft();
        int headerY = getY();
        int headerBottom = headerY + HEADER_HEIGHT;
        int rowRight = rowLeft + getRowWidth();
        int labelX = labelColumnX(rowLeft);
        int dimensionX = dimensionColumnX(rowLeft);
        int frequencyX = frequencyColumnX(rowLeft);
        int sizeX = sizeColumnX(rowLeft);
        int richnessX = richnessColumnX(rowLeft);

        guiGraphics.fill(rowLeft, headerY, rowRight, headerBottom, HEADER_BACKGROUND);
        guiGraphics.hLine(rowLeft, rowRight, headerY, HEADER_BORDER);
        guiGraphics.hLine(rowLeft, rowRight, headerBottom, HEADER_BORDER);
        guiGraphics.vLine(rowLeft, headerY, headerBottom, HEADER_BORDER);
        guiGraphics.vLine(labelX, headerY, headerBottom, HEADER_BORDER);
        guiGraphics.vLine(dimensionX, headerY, headerBottom, HEADER_BORDER);
        guiGraphics.vLine(frequencyX, headerY, headerBottom, HEADER_BORDER);
        guiGraphics.vLine(sizeX - SLIDER_GAP / 2, headerY, headerBottom, HEADER_BORDER);
        guiGraphics.vLine(richnessX - SLIDER_GAP / 2, headerY, headerBottom, HEADER_BORDER);
        guiGraphics.vLine(rowRight, headerY, headerBottom, HEADER_BORDER);

        masterToggle.setSelected(anyRowEnabled());
        masterToggle.setX(masterToggleX());
        masterToggle.setY(masterToggleY());
        masterToggle.render(guiGraphics, mouseX, mouseY, partialTick);

        int textY = headerY + 9;
        guiGraphics.drawString(Minecraft.getInstance().font, Component.translatable("gui.ores_and_drills.ore_settings.column_ore"), labelX + 4, textY, HEADER_TEXT);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.translatable("gui.ores_and_drills.ore_settings.column_dimension"),
                dimensionX + 4, textY, HEADER_TEXT);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.translatable("gui.ores_and_drills.ore_settings.column_frequency"),
                frequencyX + 4, textY, HEADER_TEXT);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.translatable("gui.ores_and_drills.ore_settings.column_size"),
                sizeX + 4, textY, HEADER_TEXT);
        guiGraphics.drawString(Minecraft.getInstance().font, Component.translatable("gui.ores_and_drills.ore_settings.column_richness"),
                richnessX + 4, textY, HEADER_TEXT);
    }

    /** Every member block id of a material row whose toggle got disabled. */
    public Set<ResourceLocation> collectDisabled() {
        Set<ResourceLocation> result = new LinkedHashSet<>();
        for (ModGroup group : groups) {
            for (OreRow row : group.rows) {
                if (!row.enabledToggle.selected()) {
                    result.addAll(row.members);
                }
            }
        }
        return result;
    }

    /** Serializes the editable per-material sliders for persistence in the new world's level.dat. */
    public List<String> collectOverrides() {
        List<String> result = new ArrayList<>();
        for (ModGroup group : groups) {
            for (OreRow row : group.rows) {
                double frequency = row.frequencySlider.multiplier();
                double size = row.sizeSlider.multiplier();
                double richness = row.richnessSlider.multiplier();
                if (frequency == 1.0D && size == 1.0D && richness == 1.0D) {
                    continue;
                }
                for (ResourceLocation member : row.members) {
                    result.add(member + "|" + frequency + "|" + size + "|" + richness);
                }
            }
        }
        return result;
    }

    public abstract static class Entry extends ContainerObjectSelectionList.Entry<Entry> {
    }

    private final class CategoryRow extends Entry {
        private final ModGroup group;

        private CategoryRow(ModGroup group) {
            this.group = group;
        }

        @Override
        public void render(
                GuiGraphics guiGraphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick
        ) {
            int rowWidth = getRowWidth();
            guiGraphics.fill(left, top, left + rowWidth, top + height, hovering ? CATEGORY_BACKGROUND_HOVERED : CATEGORY_BACKGROUND);
            guiGraphics.hLine(left, left + rowWidth, top + height - 1, ROW_BORDER);
            String arrow = group.expanded ? " \u25be" : " \u25b8";
            guiGraphics.drawString(Minecraft.getInstance().font, group.displayName.copy().append(arrow), left + 6, centeredTextY(top, height), CATEGORY_TEXT);
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button != 0) {
                return false;
            }

            group.expanded = !group.expanded;
            rebuildEntries();
            return true;
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

    private final class OreRow extends Entry {
        private final Component label;
        private final DimensionInfo dimensionInfo;
        private final List<ResourceLocation> members;
        private final SquareToggle enabledToggle;
        private final MultiplierSlider frequencySlider;
        private final MultiplierSlider sizeSlider;
        private final MultiplierSlider richnessSlider;

        private OreRow(MaterialRowData data) {
            this.label = data.label();
            this.dimensionInfo = data.dimensionInfo();
            this.members = data.members();
            this.enabledToggle = new SquareToggle(0, 0, data.enabled());
            int widgetWidth = sliderWidth - SLIDER_INSET * 2 - 1;
            this.frequencySlider = new MultiplierSlider(0, 0, widgetWidth, 20, null, MULTIPLIER_MIN, MULTIPLIER_MAX, data.override().frequency());
            this.sizeSlider = new MultiplierSlider(0, 0, widgetWidth, 20, null, MULTIPLIER_MIN, MULTIPLIER_MAX, data.override().size());
            this.richnessSlider = new MultiplierSlider(0, 0, widgetWidth, 20, null, MULTIPLIER_MIN, MULTIPLIER_MAX, data.override().richness());
        }

        @Override
        public void render(
                GuiGraphics guiGraphics,
                int index,
                int top,
                int left,
                int width,
                int height,
                int mouseX,
                int mouseY,
                boolean hovering,
                float partialTick
        ) {
            int rowWidth = getRowWidth();
            int labelColumnLeft = labelColumnX(left);
            guiGraphics.fill(left, top, left + rowWidth, top + height, index % 2 == 0 ? ROW_BACKGROUND_EVEN : ROW_BACKGROUND_ODD);
            guiGraphics.hLine(left, left + rowWidth, top + height - 1, ROW_BORDER);
            guiGraphics.vLine(labelColumnLeft, top, top + height - 1, ROW_BORDER);
            guiGraphics.vLine(dimensionColumnX(left), top, top + height - 1, ROW_BORDER);
            guiGraphics.vLine(frequencyColumnX(left), top, top + height - 1, ROW_BORDER);
            guiGraphics.vLine(sizeColumnX(left) - SLIDER_GAP / 2, top, top + height - 1, ROW_BORDER);
            guiGraphics.vLine(richnessColumnX(left) - SLIDER_GAP / 2, top, top + height - 1, ROW_BORDER);

            enabledToggle.setX(checkboxColumnX(left) + (CHECKBOX_COLUMN_WIDTH - ENABLED_TOGGLE_SIZE) / 2);
            enabledToggle.setY(top + (height - enabledToggle.getHeight()) / 2);
            enabledToggle.render(guiGraphics, mouseX, mouseY, partialTick);

            int labelX = labelColumnLeft + 4;
            guiGraphics.drawString(Minecraft.getInstance().font, fit(label, dimensionColumnX(left) - labelX - 4), labelX, centeredTextY(top, height), 0xFFFFFF);
            renderDimensionLabels(guiGraphics, dimensionColumnX(left) + 4, top, height);

            int sliderY = top + (height - 20) / 2;
            frequencySlider.setX(frequencyColumnX(left) + SLIDER_INSET + 1);
            frequencySlider.setY(sliderY);
            sizeSlider.setX(sizeColumnX(left) + SLIDER_INSET + 1);
            sizeSlider.setY(sliderY);
            richnessSlider.setX(richnessColumnX(left) + SLIDER_INSET + 1);
            richnessSlider.setY(sliderY);

            frequencySlider.render(guiGraphics, mouseX, mouseY, partialTick);
            sizeSlider.render(guiGraphics, mouseX, mouseY, partialTick);
            richnessSlider.render(guiGraphics, mouseX, mouseY, partialTick);
        }

        @Override
        public List<? extends GuiEventListener> children() {
            return List.of(enabledToggle, frequencySlider, sizeSlider, richnessSlider);
        }

        @Override
        public List<? extends NarratableEntry> narratables() {
            return List.of(enabledToggle, frequencySlider, sizeSlider, richnessSlider);
        }

        private void renderDimensionLabels(GuiGraphics guiGraphics, int x, int top, int height) {
            List<Component> labels = dimensionInfo.labels();
            int totalHeight = labels.size() * DIMENSION_LINE_HEIGHT;
            int y = top + Math.max(2, (height - totalHeight) / 2);
            for (Component label : labels) {
                guiGraphics.drawString(Minecraft.getInstance().font, fit(label, dimensionWidth - 8), x, y, 0xD0D0D0);
                y += DIMENSION_LINE_HEIGHT;
            }
        }
    }

    private static final class SquareToggle extends AbstractWidget {
        private boolean selected;

        private SquareToggle(int x, int y, boolean selected) {
            super(x, y, ENABLED_TOGGLE_SIZE, ENABLED_TOGGLE_SIZE, Component.empty());
            this.selected = selected;
        }

        private boolean selected() {
            return selected;
        }

        private void setSelected(boolean selected) {
            this.selected = selected;
        }

        @Override
        public void onClick(double mouseX, double mouseY, int button) {
            selected = !selected;
        }

        @Override
        protected void renderWidget(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
            int x = getX();
            int y = getY();
            int border = 0xFFE0E0E0;
            int fill = selected ? 0x80E0E0E0 : 0x00000000;
            int hoverBorder = 0xFFFFFFFF;
            int color = isHovered() ? hoverBorder : border;
            int borderWidth = 2;

            guiGraphics.fill(x, y, x + width, y + borderWidth, color);
            guiGraphics.fill(x, y + height - borderWidth, x + width, y + height, color);
            guiGraphics.fill(x, y + borderWidth, x + borderWidth, y + height - borderWidth, color);
            guiGraphics.fill(x + width - borderWidth, y + borderWidth, x + width, y + height - borderWidth, color);
            if (selected) {
                guiGraphics.fill(x + 4, y + 4, x + width - 4, y + height - 4, fill);
            }
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput narrationElementOutput) {
            defaultButtonNarrationText(narrationElementOutput);
        }
    }

    private static Component displayName(String material, List<ResourceLocation> members) {
        ResourceLocation preferred = preferredDisplayMember(material, members);
        if (preferred != null) {
            Block block = BuiltInRegistries.BLOCK.get(preferred);
            String fallback = humanizeOrePath(preferred.getPath());
            String resolved = Language.getInstance().getOrDefault(block.getDescriptionId(), fallback);
            return Component.literal(stripBaseVariantName(resolved));
        }
        return Component.literal(humanizeMaterial(material) + " Ore");
    }

    private static DimensionInfo dimensionInfo(List<ResourceLocation> members) {
        Set<OreSpawnDimensions.SpawnDimension> keys = dimensionKeys(members);
        List<Component> parts = new ArrayList<>();
        if (keys.contains(OreSpawnDimensions.SpawnDimension.OVERWORLD)) {
            parts.add(Component.translatable("dimension.minecraft.overworld"));
        }
        if (keys.contains(OreSpawnDimensions.SpawnDimension.NETHER)) {
            parts.add(Component.translatable("dimension.minecraft.the_nether"));
        }
        if (keys.contains(OreSpawnDimensions.SpawnDimension.END)) {
            parts.add(Component.translatable("dimension.minecraft.the_end"));
        }
        if (keys.contains(OreSpawnDimensions.SpawnDimension.UNKNOWN)) {
            parts.add(Component.literal("?"));
        }
        if (parts.isEmpty()) {
            parts.add(Component.literal("-"));
        }
        return new DimensionInfo(List.copyOf(parts));
    }

    private static Set<OreSpawnDimensions.SpawnDimension> dimensionKeys(List<ResourceLocation> members) {
        return OreSpawnDimensions.dimensionsFor(members);
    }

    private static Component fit(Component component, int maxWidth) {
        Minecraft minecraft = Minecraft.getInstance();
        String text = component.getString();
        if (minecraft.font.width(text) <= maxWidth) {
            return component;
        }
        return Component.literal(minecraft.font.plainSubstrByWidth(text, Math.max(0, maxWidth - minecraft.font.width("..."))) + "...");
    }

    private static int centeredTextY(int top, int height) {
        return top + (height - Minecraft.getInstance().font.lineHeight) / 2;
    }

    private static String humanizeOrePath(String path) {
        String value = path.toLowerCase(Locale.ROOT);
        value = stripPrefix(value, "deepslate_");
        value = stripPrefix(value, "nether_");
        value = stripPrefix(value, "end_stone_");
        value = stripPrefix(value, "end_");
        return humanizeMaterial(value);
    }

    private static String stripBaseVariantName(String name) {
        String result = name.trim();
        String lower = result.toLowerCase(Locale.ROOT);
        if (lower.startsWith("deepslate ")) {
            result = result.substring("deepslate ".length());
        } else if (lower.startsWith("nether ")) {
            result = result.substring("nether ".length());
        } else if (lower.startsWith("end stone ")) {
            result = result.substring("end stone ".length());
        } else if (lower.startsWith("end ")) {
            result = result.substring("end ".length());
        } else if (lower.startsWith("глубинносланцевая ")) {
            result = result.substring("глубинносланцевая ".length());
        } else if (lower.startsWith("глубинносланцевый ")) {
            result = result.substring("глубинносланцевый ".length());
        } else if (lower.startsWith("незер-")) {
            result = result.substring("незер-".length());
        } else if (lower.startsWith("незерская ")) {
            result = result.substring("незерская ".length());
        } else if (lower.startsWith("эндская ")) {
            result = result.substring("эндская ".length());
        } else if (lower.startsWith("эндерняковая ")) {
            result = result.substring("эндерняковая ".length());
        }
        if (result.isEmpty()) {
            return name;
        }
        return Character.toUpperCase(result.charAt(0)) + result.substring(1);
    }

    private static ResourceLocation preferredDisplayMember(String material, List<ResourceLocation> members) {
        ResourceLocation fallback = null;
        for (ResourceLocation member : members) {
            String path = member.getPath();
            if (path.equals(material + "_ore") || path.equals("ore_" + material)) {
                return member;
            }
            if (fallback == null && !isBaseNamedVariant(path)) {
                fallback = member;
            }
        }
        return fallback != null ? fallback : members.isEmpty() ? null : members.getFirst();
    }

    private static boolean isBaseNamedVariant(String path) {
        return path.contains("deepslate")
                || path.contains("netherrack")
                || path.startsWith("nether_")
                || path.startsWith("end_")
                || path.startsWith("end_stone_");
    }

    private static String humanizeMaterial(String material) {
        String[] parts = material.replace('-', '_').split("_");
        StringBuilder result = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                result.append(part.substring(1));
            }
        }
        return result.isEmpty() ? material : result.toString();
    }

    private static String stripPrefix(String value, String prefix) {
        return value.startsWith(prefix) ? value.substring(prefix.length()) : value;
    }

    private static final class ModGroup {
        private final String namespace;
        private final Component displayName;
        private final List<OreRow> rows = new ArrayList<>();
        private boolean expanded = true;

        private ModGroup(String namespace) {
            this.namespace = namespace;
            this.displayName = Component.literal(modDisplayName(namespace));
        }
    }

    private static String modDisplayName(String namespace) {
        return ModList.get()
                .getModContainerById(namespace)
                .map(container -> container.getModInfo().getDisplayName())
                .filter(name -> !name.isBlank())
                .orElse(namespace);
    }

    private record MaterialRowData(
            String material,
            String namespace,
            List<ResourceLocation> members,
            Component label,
            DimensionInfo dimensionInfo,
            OreOverrides.OreOverride override,
            boolean enabled,
            int modPriorityRank,
            int rarityRank
    ) {
    }

    private record DimensionInfo(List<Component> labels) {
    }
}

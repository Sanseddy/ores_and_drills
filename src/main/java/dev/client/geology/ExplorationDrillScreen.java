package dev.client.geology;

import dev.OresAndDrillsMod;
import dev.client.geology.layout.Rect;
import dev.client.geology.layout.TextFit;
import dev.client.geology.lidar.LidarClientCache;
import dev.client.geology.lidar.LidarView;
import dev.client.geology.lidar.LidarViewerScreen;
import dev.registry.ModDataComponents;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.exploration.BoreholeLog;
import dev.world.geology.exploration.ExplorationDrillBlockEntity;
import dev.world.geology.exploration.ExplorationDrillMenu;
import dev.world.geology.ore.OreIdentification;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;

/**
 * Exploration drill console: energy, depth reached and the analysis of the core's ore. The
 * inventory part keeps the standard layout; the analysis lives in a side panel sized to its text,
 * beside the console where the screen has room and as a tooltip of the console's text area where it
 * has not. The borehole itself — core, sensed ore and the analyser's voids — is shown only in 3D,
 * in the LiDAR log the "3D log" button opens, which grows live while the drill works.
 */
public final class ExplorationDrillScreen extends AbstractContainerScreen<ExplorationDrillMenu> {
    private static final ResourceLocation TEXTURE =
            ResourceLocation.fromNamespaceAndPath(
                    OresAndDrillsMod.MOD_ID, "textures/gui/exploration_drill.png");
    private static final String KEY = "gui.ores_and_drills.exploration_drill.";
    private static final int CORE_X = 8;
    private static final int CORE_Y = 18;
    private static final int CORE_W = 14;
    private static final int CORE_H = 72;
    private static final int INFO_X = 42;
    private static final int INFO_Y = 56;
    private static final int INFO_RIGHT = 104;
    private static final int VIEW_X = 108;
    private static final int VIEW_Y = 58;
    private static final int VIEW_W = 40;
    private static final int INFO_BOTTOM = ExplorationDrillMenu.INVENTORY_Y - 12;
    private static final int PANEL_MIN = 110;
    private static final int PANEL_MAX = 200;

    private Button startButton;
    private Button stopButton;
    private FontMeasure measure;

    public ExplorationDrillScreen(ExplorationDrillMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        imageWidth = 176;
        imageHeight = 180;
        inventoryLabelY = ExplorationDrillMenu.INVENTORY_Y - 11;
        LidarClientCache.requestSync(menu.lidarOwner());
    }

    @Override
    protected void init() {
        super.init();
        measure = new FontMeasure(font);
        titleLabelX = 8;
        startButton =
                addRenderableWidget(
                        Button.builder(
                                        Component.translatable(KEY + "start"),
                                        button -> click(ExplorationDrillMenu.BUTTON_START))
                                .bounds(leftPos + 108, topPos + 16, 62, 18)
                                .build());
        stopButton =
                addRenderableWidget(
                        Button.builder(
                                        Component.translatable(KEY + "stop"),
                                        button -> click(ExplorationDrillMenu.BUTTON_STOP))
                                .bounds(leftPos + 108, topPos + 36, 62, 18)
                                .build());
        addRenderableWidget(
                Button.builder(Component.translatable(KEY + "view3d"), button -> openViewer())
                        .bounds(leftPos + VIEW_X, topPos + VIEW_Y, VIEW_W, 18)
                        .tooltip(Tooltip.create(Component.translatable(KEY + "view3d.hint")))
                        .build());
    }

    /** Opens the borehole's LiDAR log; the console comes back when it closes. */
    private void openViewer() {
        if (minecraft == null) {
            return;
        }
        BlockPos hole = menu.hole();
        minecraft.setScreen(
                new LidarViewerScreen(
                        this,
                        Component.translatable(KEY + "view3d.title"),
                        menu.lidarOwner(),
                        hole.getX() + 0.5D,
                        hole.getY(),
                        hole.getZ() + 0.5D,
                        this::scene,
                        this::marks));
    }

    private LidarViewerScreen.Scene scene() {
        int analyzer = CaveAnalyzer.clamp(menu.value(ExplorationDrillMenu.DATA_ANALYZER));
        double radius =
                Math.max(
                                ExplorationDrillBlockEntity.LOGGING_RADIUS,
                                CaveAnalyzer.drillRadius(analyzer))
                        + 4.0D;
        int top = menu.hole().getY() + 2;
        int bottom = Math.min(menu.value(ExplorationDrillMenu.DATA_BOTTOM), top - 16);
        return new LidarViewerScreen.Scene(radius, top, bottom, menu.hole().getY());
    }

    /** The drill on the surface and its bit at the current depth. */
    private List<LidarView.Mark> marks() {
        BlockPos pos = menu.hole();
        List<LidarView.Mark> marks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            marks.add(
                    new LidarView.Mark(
                            pos.getX() + 0.5D,
                            pos.getY() + i * 0.3D,
                            pos.getZ() + 0.5D,
                            0xFFFFFFFF,
                            4));
        }
        int state = menu.value(ExplorationDrillMenu.DATA_STATE);
        if (state == ExplorationDrillBlockEntity.STATE_DRILLING
                || state == ExplorationDrillBlockEntity.STATE_NO_POWER) {
            marks.add(
                    new LidarView.Mark(
                            pos.getX() + 0.5D,
                            menu.value(ExplorationDrillMenu.DATA_CURSOR) + 0.5D,
                            pos.getZ() + 0.5D,
                            0xFFFF5050,
                            5));
        }
        return marks;
    }

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        int state = menu.value(ExplorationDrillMenu.DATA_STATE);
        boolean drilling =
                state == ExplorationDrillBlockEntity.STATE_DRILLING
                        || state == ExplorationDrillBlockEntity.STATE_NO_POWER;
        startButton.active = !drilling && menu.sample().isEmpty();
        stopButton.active = drilling;
    }

    @Nullable
    private BoreholeLog log() {
        return menu.sample().get(ModDataComponents.BOREHOLE_LOG);
    }

    /** Where the analysis panel goes: right of the console, else left, else nowhere. */
    @Nullable
    private Rect panel() {
        int wanted = PANEL_MIN;
        for (Component line : analysisLines()) {
            wanted = Math.max(wanted, font.width(line) + 10);
        }
        int right = width - (leftPos + imageWidth) - 6;
        int left = leftPos - 6;
        int panelWidth = Math.min(PANEL_MAX, wanted);
        int lines = 0;
        int x;
        if (right >= PANEL_MIN) {
            panelWidth = Math.min(panelWidth, right);
            x = leftPos + imageWidth + 4;
        } else if (left >= PANEL_MIN) {
            panelWidth = Math.min(panelWidth, left);
            x = leftPos - 4 - panelWidth;
        } else {
            return null;
        }
        for (Component line : analysisLines()) {
            lines += TextFit.wrap(line.getString(), panelWidth - 10, measure).size();
        }
        int height = Math.min(imageHeight, lines * (font.lineHeight + 1) + 10);
        return new Rect(x, topPos, panelWidth, height);
    }

    /** The analyser's verdict on the core, in words. */
    private List<Component> analysisLines() {
        List<Component> lines = new ArrayList<>();
        BoreholeLog log = log();
        lines.add(Component.translatable(KEY + "analysis"));
        int level =
                log != null
                        ? log.level()
                        : ScannerUpgrade.clamp(menu.value(ExplorationDrillMenu.DATA_LEVEL));
        lines.add(Component.translatable(KEY + "level", level, ScannerUpgrade.MAX_LEVEL));
        lines.add(
                Component.translatable(
                        KEY + "analyzer",
                        CaveAnalyzer.clamp(menu.value(ExplorationDrillMenu.DATA_ANALYZER)),
                        CaveAnalyzer.MAX_LEVEL));
        if (log == null) {
            lines.add(Component.translatable(KEY + "analysis.none"));
            return lines;
        }
        if (log.ore().isEmpty()) {
            lines.add(Component.translatable(KEY + "dry"));
            return lines;
        }
        lines.add(Component.translatable(KEY + "interval", log.oreTopY(), log.oreBottomY()));
        if (log.analysis().isEmpty()) {
            lines.add(Component.translatable(KEY + "analysis.unknown"));
        }
        for (BoreholeLog.Analysis candidate : log.analysis()) {
            lines.add(
                    Component.translatable(
                            KEY + "candidate",
                            SurveyText.candidate(
                                    OreIdentification.Source.ORE.ordinal(),
                                    candidate.category(),
                                    candidate.oreId()),
                            SurveyText.percent(candidate.probability())));
        }
        if (!ScannerUpgrade.namesOres(level)) {
            lines.add(Component.translatable(KEY + "analysis.classes_only"));
        }
        lines.add(
                Component.translatable(
                        KEY + "estimate",
                        log.localUnits(),
                        ExplorationDrillBlockEntity.ESTIMATE_RADIUS));
        lines.add(Component.translatable(KEY + (log.industrial() ? "industrial" : "minor")));
        return lines;
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        graphics.blit(TEXTURE, leftPos, topPos, 0, 0, imageWidth, imageHeight, 256, 256);
        int energy = menu.value(ExplorationDrillMenu.DATA_ENERGY);
        int capacity = Math.max(1, menu.value(ExplorationDrillMenu.DATA_CAPACITY));
        int barHeight = Math.round(70.0F * energy / capacity);
        graphics.fill(
                leftPos + 29,
                topPos + 19 + 70 - barHeight,
                leftPos + 35,
                topPos + 19 + 70,
                0xFFE04040);
        renderCore(graphics);
        Rect panel = panel();
        if (panel != null) {
            VanillaUi.panel(graphics, panel);
            graphics.enableScissor(panel.x(), panel.y(), panel.right(), panel.bottom());
            int y = panel.y() + 5;
            boolean first = true;
            for (Component line : analysisLines()) {
                for (String part : TextFit.wrap(line.getString(), panel.width() - 10, measure)) {
                    graphics.drawString(
                            font,
                            part,
                            panel.x() + 5,
                            y,
                            first ? VanillaUi.TEXT_ACCENT : VanillaUi.TEXT,
                            false);
                    y += font.lineHeight + 1;
                }
                first = false;
            }
            graphics.disableScissor();
        }
    }

    /**
     * How far the borehole has got, as a gauge in the console's column well. The core itself is
     * shown in the 3D log.
     */
    private void renderCore(GuiGraphics graphics) {
        int x = leftPos + CORE_X;
        int y = topPos + CORE_Y;
        int top = menu.value(ExplorationDrillMenu.DATA_TOP);
        int bottom = menu.value(ExplorationDrillMenu.DATA_BOTTOM);
        int span = Math.max(1, top - bottom);
        int state = menu.value(ExplorationDrillMenu.DATA_STATE);
        int cursor = menu.value(ExplorationDrillMenu.DATA_CURSOR);
        if (state == ExplorationDrillBlockEntity.STATE_IDLE) {
            return;
        }
        int drilled = Mth.clamp(top - cursor, 0, span);
        graphics.fill(x, y, x + CORE_W, y + drilled * CORE_H / span, 0xFF6E6E6E);
        graphics.fill(x, y, x + 1, y + drilled * CORE_H / span, 0xFF9A9A9A);
    }

    /** Short status lines of the console area, each cut to the area's width. */
    private List<Component> infoLines() {
        int state = menu.value(ExplorationDrillMenu.DATA_STATE);
        String key =
                switch (state) {
                    case ExplorationDrillBlockEntity.STATE_DRILLING -> "state.drilling";
                    case ExplorationDrillBlockEntity.STATE_DONE -> "state.done";
                    case ExplorationDrillBlockEntity.STATE_NO_POWER -> "state.no_power";
                    case ExplorationDrillBlockEntity.STATE_BLOCKED -> "state.blocked";
                    default -> "state.idle";
                };
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(KEY + key));
        lines.add(
                Component.translatable(
                        KEY + "depth", menu.value(ExplorationDrillMenu.DATA_CURSOR)));
        BoreholeLog log = log();
        if (log != null && log.ore().isPresent()) {
            BoreholeLog.Analysis best = log.analysis().isEmpty() ? null : log.analysis().getFirst();
            lines.add(
                    best == null
                            ? Component.translatable(KEY + "found_unknown", log.oreTopY())
                            : Component.translatable(
                                    KEY + "found",
                                    SurveyText.candidate(
                                            OreIdentification.Source.ORE.ordinal(),
                                            best.category(),
                                            best.oreId()),
                                    SurveyText.percent(best.probability())));
        } else if (log != null) {
            lines.add(Component.translatable(KEY + "dry"));
        }
        return lines;
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        int width = INFO_RIGHT - INFO_X;
        graphics.drawString(
                font,
                TextFit.ellipsize(title.getString(), 100, measure),
                titleLabelX,
                titleLabelY,
                0xFF404040,
                false);
        graphics.drawString(
                font,
                TextFit.ellipsize(playerInventoryTitle.getString(), imageWidth - 16, measure),
                inventoryLabelX,
                inventoryLabelY,
                0xFF404040,
                false);
        int y = INFO_Y;
        for (Component line : infoLines()) {
            if (y + font.lineHeight > INFO_BOTTOM) {
                break;
            }
            graphics.drawString(
                    font,
                    TextFit.ellipsize(line.getString(), width, measure),
                    INFO_X,
                    y,
                    0xFF404040,
                    false);
            y += font.lineHeight + 1;
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
        if (mouseX >= leftPos + 28
                && mouseX < leftPos + 36
                && mouseY >= topPos + 18
                && mouseY < topPos + 90) {
            graphics.renderTooltip(
                    font,
                    Component.translatable(
                            KEY + "energy",
                            SurveyText.integer(menu.value(ExplorationDrillMenu.DATA_ENERGY)),
                            SurveyText.integer(menu.value(ExplorationDrillMenu.DATA_CAPACITY))),
                    mouseX,
                    mouseY);
        } else if (mouseX >= leftPos + INFO_X
                && mouseX < leftPos + INFO_RIGHT
                && mouseY >= topPos + INFO_Y
                && mouseY < topPos + INFO_BOTTOM) {
            // Full text of the console lines and, without room for the side panel, the analysis.
            List<Component> tooltip = new ArrayList<>(infoLines());
            if (panel() == null) {
                tooltip.addAll(analysisLines());
            }
            graphics.renderComponentTooltip(font, tooltip, mouseX, mouseY);
        }
    }
}

package dev.client.geology;

import dev.client.geology.layout.ColumnLayout;
import dev.client.geology.layout.Rect;
import dev.client.geology.layout.StationLayout;
import dev.client.geology.layout.TextFit;
import dev.client.geology.lidar.ClientLidarMap;
import dev.client.geology.lidar.LidarClientCache;
import dev.client.geology.lidar.LidarObjectActions;
import dev.client.geology.lidar.LidarQuality;
import dev.client.geology.lidar.LidarRenderer;
import dev.client.geology.lidar.LidarView;
import dev.network.SeismicStationRequestPayload;
import dev.network.SeismicSurveyListPayload;
import dev.network.SeismicSurveyMapPayload;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.SurveyMode;
import dev.world.geology.SurveyPreset;
import dev.world.geology.UpgradeModuleItem;
import dev.world.geology.lidar.LidarCoverage;
import dev.world.geology.lidar.LidarObject;
import dev.world.geology.station.SeismicStationBlockEntity;
import dev.world.geology.station.SeismicStationMenu;
import dev.world.geology.station.StationState;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Station console in the style of vanilla containers. The left side is the interactive LiDAR view:
 * the point cloud the survey builds while it runs, refined by every later survey. The right side
 * holds four tabs — the survey itself (one detail slider with its estimates, start, progress), the
 * selected structure (details, copy coordinates, map waypoint), the survey log and the equipment.
 * Everything shown comes from what the server revealed and interpreted.
 */
public final class SeismicStationScreen extends AbstractContainerScreen<SeismicStationMenu> {
    private static final int BUTTON_HEIGHT = 20;
    private static final int GAP = 3;
    private static final int VIEW_BACKGROUND = 0xFF06090E;

    /** Choices remembered for this client session so reopening the console keeps them. */
    private static int lastPreset = 2;

    private static LidarRenderer.Mode lastMode = LidarRenderer.Mode.BOTH;

    private enum Tab {
        SCAN,
        OBJECT,
        LOG,
        EQUIPMENT
    }

    private Tab tab = Tab.SCAN;
    private int seenRevision = -1;
    private int selectedSurvey = -1;
    private int newestSeen = -1;
    private int selectedObject = -1;
    private int sidebarScroll;
    private int sidebarMaxScroll;
    private boolean openingChild;

    private FontMeasure measure;
    private StationLayout.Result layout;
    private final LidarView view;
    private final List<Entry> entries = new ArrayList<>();
    private final List<Button> tabButtons = new ArrayList<>();
    private PresetSlider presetSlider;
    private Button startButton;
    private Button stopButton;
    private Button modeButton;
    private Button resetViewButton;
    private Button centerButton;
    private Button qualityButton;
    private Button previousObjectButton;
    private Button nextObjectButton;
    private Button copyButton;
    private Button waypointButton;
    private Button previousSurveyButton;
    private Button nextSurveyButton;
    private ClearMapButton clearMapButton;
    private Boolean clearMapBusy;

    public SeismicStationScreen(SeismicStationMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        BlockPos site = menu.site();
        this.view = new LidarView(site.getX() + 0.5D, site.getY(), site.getZ() + 0.5D);
        LidarClientCache.requestSync(menu.stationPos());
    }

    // ------------------------------------------------------------------------------------------
    // Setup and layout

    @Override
    protected void init() {
        openingChild = false;
        measure = new FontMeasure(font);
        layout = computeLayout();
        imageWidth = layout.panel().width();
        imageHeight = layout.panel().height();
        super.init();
        tabButtons.clear();
        entries.clear();
        for (Tab target : Tab.values()) {
            Button button =
                    addRenderableWidget(
                            Button.builder(tabLabel(target), b -> switchTab(target))
                                    .bounds(0, 0, 10, 10)
                                    .tooltip(Tooltip.create(text("tab." + key(target) + ".hint")))
                                    .build());
            tabButtons.add(button);
        }
        presetSlider = addRenderableWidget(new PresetSlider());
        startButton =
                button(
                        text("start"),
                        text("start.hint"),
                        () -> click(SeismicStationMenu.BUTTON_PRESET + presetSlider.index()));
        stopButton =
                button(
                        text("stop"),
                        text("stop.hint"),
                        () -> click(SeismicStationMenu.BUTTON_CANCEL));
        modeButton =
                button(Component.empty(), text("display.hint"), () -> lastMode = lastMode.next());
        resetViewButton = button(text("view.reset"), text("view.reset.hint"), view::resetCamera);
        centerButton =
                button(text("view.center"), text("view.center.hint"), this::centerOnSelection);
        qualityButton =
                button(
                        Component.empty(),
                        text("quality.hint"),
                        () -> LidarQuality.set(LidarQuality.current().next()));
        previousObjectButton =
                button(text("object.previous"), text("object.previous.hint"), () -> stepObject(-1));
        nextObjectButton =
                button(text("object.next"), text("object.next.hint"), () -> stepObject(1));
        copyButton = button(text("object.copy"), text("object.copy.hint"), this::copySelection);
        waypointButton =
                button(text("object.waypoint"), text("object.waypoint.hint"), this::placeWaypoint);
        previousSurveyButton =
                button(text("survey.previous"), text("survey.previous.hint"), () -> stepSurvey(-1));
        nextSurveyButton =
                button(text("survey.next"), text("survey.next.hint"), () -> stepSurvey(1));
        clearMapButton =
                addRenderableWidget(new ClearMapButton(text("view.clear"), this::confirmClearMap));
        clearMapBusy = null;

        BooleanSupplier scan = () -> tab == Tab.SCAN;
        BooleanSupplier object = () -> tab == Tab.OBJECT;
        BooleanSupplier log = () -> tab == Tab.LOG;
        BooleanSupplier equipment = () -> tab == Tab.EQUIPMENT;
        entries.add(new TextEntry(this::equipmentSummary, scan));
        entries.add(new WidgetEntry(presetSlider, scan));
        entries.add(new TextEntry(this::presetLines, scan));
        entries.add(new RowEntry(startButton, stopButton, scan));
        entries.add(new BarEntry(this::progressLabel, this::progressFraction, 0xFF3FA34D, scan));
        entries.add(new TextEntry(this::frontLines, scan));
        entries.add(new BarEntry(this::energyLabel, this::energyFraction, 0xFFB83B3B, scan));
        entries.add(new WidgetEntry(modeButton, scan));
        entries.add(new RowEntry(resetViewButton, centerButton, scan));
        entries.add(new WidgetEntry(qualityButton, scan));

        entries.add(new RowEntry(previousObjectButton, nextObjectButton, object));
        entries.add(new TextEntry(this::objectLines, object));
        entries.add(new RowEntry(copyButton, waypointButton, object));

        entries.add(new RowEntry(previousSurveyButton, nextSurveyButton, log));
        entries.add(new TextEntry(this::surveyLines, log));

        entries.add(new TextEntry(this::scannerLines, equipment));
        entries.add(new TextEntry(this::analyzerLines, equipment));
        entries.add(new TextEntry(this::oreTableLines, equipment));
        updateWidgets();
        applyLayout();
    }

    private Button button(Component label, Component hint, Runnable action) {
        return addRenderableWidget(
                Button.builder(label, b -> action.run())
                        .bounds(0, 0, 10, BUTTON_HEIGHT)
                        .tooltip(Tooltip.create(hint))
                        .build());
    }

    private StationLayout.Result computeLayout() {
        int[] tabWidths = new int[Tab.values().length];
        for (Tab target : Tab.values()) {
            tabWidths[target.ordinal()] = font.width(tabLabel(target));
        }
        int preferred = 0;
        for (Component label :
                List.of(
                        text("start"),
                        text("stop"),
                        text("view.reset"),
                        text("view.center"),
                        text("object.copy"),
                        text("object.waypoint"),
                        text("survey.previous"),
                        text("survey.next"))) {
            preferred = Math.max(preferred, font.width(label));
        }
        // Two buttons share a row; a label plus button padding must fit in each half.
        preferred = Math.max(preferred * 2 + 24, 170);
        return StationLayout.compute(width, height, tabWidths, preferred, font.lineHeight);
    }

    private static String key(Tab tab) {
        return tab.name().toLowerCase(Locale.ROOT);
    }

    private static Component tabLabel(Tab tab) {
        return text("tab." + key(tab));
    }

    /** Places the widgets of the side column for this frame; hides what does not fit. */
    private void applyLayout() {
        Rect view = layout.view();
        clearMapButton.setPosition(view.right() - ClearMapButton.SIZE - 4, view.y() + 4);
        for (int i = 0; i < tabButtons.size(); i++) {
            Rect rect = layout.tabs().get(i);
            Button button = tabButtons.get(i);
            button.setRectangle(rect.width(), rect.height(), rect.x(), rect.y());
            button.active = tab.ordinal() != i;
        }
        Rect sidebar = layout.sidebar();
        int width = sidebar.width();
        int[] heights = heights(width);
        ColumnLayout.Result column = ColumnLayout.compute(sidebar, heights, GAP, sidebarScroll);
        if (column.maxScroll() > 0) {
            // Leave room for the scroll bar.
            width -= 5;
            heights = heights(width);
            column =
                    ColumnLayout.compute(
                            new Rect(sidebar.x(), sidebar.y(), width, sidebar.height()),
                            heights,
                            GAP,
                            sidebarScroll);
        }
        sidebarScroll = column.scroll();
        sidebarMaxScroll = column.maxScroll();
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            entry.place(column.items().get(i), entry.shown() && column.visible()[i]);
        }
    }

    private int[] heights(int width) {
        int[] heights = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            heights[i] = entry.shown() ? entry.height(width) : 0;
        }
        return heights;
    }

    // ------------------------------------------------------------------------------------------
    // State

    private void click(int id) {
        if (minecraft != null && minecraft.gameMode != null) {
            minecraft.gameMode.handleInventoryButtonClick(menu.containerId, id);
        }
    }

    /** The map holds every survey of the station, so clearing it asks first. */
    private void confirmClearMap() {
        if (minecraft == null) {
            return;
        }
        openingChild = true;
        minecraft.setScreen(
                new ConfirmScreen(
                        confirmed -> {
                            if (confirmed) {
                                click(SeismicStationMenu.BUTTON_CLEAR_MAP);
                                selectedObject = -1;
                            }
                            minecraft.setScreen(this);
                        },
                        text("view.clear.confirm.title"),
                        text("view.clear.confirm.message"),
                        text("view.clear"),
                        CommonComponents.GUI_CANCEL));
    }

    @Override
    public void removed() {
        super.removed();
        if (!openingChild) {
            SeismicClientData.clear();
            view.close();
        }
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        if (SeismicClientData.revision() != seenRevision) {
            seenRevision = SeismicClientData.revision();
            onDataChanged();
        }
        if (map().object(selectedObject) == null) {
            selectedObject = -1;
        }
        updateWidgets();
    }

    private BlockPos station() {
        return menu.stationPos();
    }

    /** Where the station measures from; geometry is drawn around it, not around the block. */
    private BlockPos site() {
        return menu.site();
    }

    private ClientLidarMap map() {
        return LidarClientCache.map(station());
    }

    @Nullable
    private SeismicSurveyListPayload list() {
        return SeismicClientData.list(station());
    }

    @Nullable
    private SeismicSurveyMapPayload report() {
        SeismicSurveyMapPayload report = SeismicClientData.map(station());
        return report != null && report.surveyId() == selectedSurvey ? report : null;
    }

    @Nullable
    private LidarObject selected() {
        return map().object(selectedObject);
    }

    private void onDataChanged() {
        SeismicSurveyListPayload list = list();
        if (list == null || list.surveys().isEmpty()) {
            return;
        }
        int newest = list.surveys().getLast().id();
        boolean known = list.surveys().stream().anyMatch(entry -> entry.id() == selectedSurvey);
        if (!known || newest != newestSeen) {
            newestSeen = newest;
            selectSurvey(newest);
        }
    }

    private void selectSurvey(int surveyId) {
        selectedSurvey = surveyId;
        PacketDistributor.sendToServer(new SeismicStationRequestPayload(station(), surveyId));
    }

    private void stepSurvey(int direction) {
        SeismicSurveyListPayload list = list();
        if (list == null || list.surveys().isEmpty()) {
            return;
        }
        int index = Mth.clamp(surveyIndex(list) + direction, 0, list.surveys().size() - 1);
        selectSurvey(list.surveys().get(index).id());
    }

    private int surveyIndex(@Nullable SeismicSurveyListPayload list) {
        if (list == null) {
            return -1;
        }
        for (int i = 0; i < list.surveys().size(); i++) {
            if (list.surveys().get(i).id() == selectedSurvey) {
                return i;
            }
        }
        return -1;
    }

    /** Objects of the shown layers, most certain first: the order the step buttons walk. */
    private List<LidarObject> browsable() {
        List<LidarObject> objects = new ArrayList<>();
        for (LidarObject object : map().objects()) {
            if (object.kind().cave() ? lastMode.caves() : lastMode.ores()) {
                objects.add(object);
            }
        }
        objects.sort(
                Comparator.comparingDouble((LidarObject o) -> -o.confidence())
                        .thenComparingInt(LidarObject::id));
        return objects;
    }

    private void stepObject(int direction) {
        List<LidarObject> objects = browsable();
        if (objects.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i).id() == selectedObject) {
                index = i;
            }
        }
        index = index < 0 ? 0 : Math.floorMod(index + direction, objects.size());
        select(objects.get(index).id());
        view.focus(objects.get(index));
    }

    private void select(int objectId) {
        selectedObject = objectId;
        if (objectId >= 0) {
            tab = Tab.OBJECT;
            sidebarScroll = 0;
        }
    }

    private void centerOnSelection() {
        LidarObject object = selected();
        if (object != null) {
            view.focus(object);
        }
    }

    private void copySelection() {
        LidarObject object = selected();
        if (object != null) {
            LidarObjectActions.copy(object, columnMiddle());
        }
    }

    private void placeWaypoint() {
        LidarObject object = selected();
        if (object != null) {
            openingChild = true;
            LidarObjectActions.placeWaypoint(this, object, columnMiddle());
            if (minecraft != null && minecraft.screen == this) {
                openingChild = false;
            }
        }
    }

    private void switchTab(Tab target) {
        tab = target;
        sidebarScroll = 0;
    }

    private StationState stationState() {
        return StationState.byId(menu.value(SeismicStationMenu.DATA_STATE));
    }

    private int level() {
        return ScannerUpgrade.clamp(menu.value(SeismicStationMenu.DATA_LEVEL));
    }

    private int analyzer() {
        return CaveAnalyzer.clamp(menu.value(SeismicStationMenu.DATA_ANALYZER));
    }

    private List<SurveyPreset> presets() {
        return SurveyPreset.of(
                menu.value(SeismicStationMenu.DATA_DETAILED_RADIUS),
                menu.value(SeismicStationMenu.DATA_REGIONAL_RADIUS));
    }

    private void updateWidgets() {
        if (startButton == null) {
            return;
        }
        boolean busy = stationState().surveying();
        clearMapButton.active = !busy && (map().sampleCount() > 0 || !map().objects().isEmpty());
        if (!Boolean.valueOf(busy).equals(clearMapBusy)) {
            clearMapBusy = busy;
            clearMapButton.setTooltip(
                    Tooltip.create(text(busy ? "view.clear.busy" : "view.clear.hint")));
        }
        startButton.active = !busy;
        stopButton.active = busy;
        presetSlider.active = !busy;
        presetSlider.refresh();
        modeButton.setMessage(text("display", text("display." + key(lastMode))));
        qualityButton.setMessage(
                text(
                        "quality",
                        text("quality." + LidarQuality.current().name().toLowerCase(Locale.ROOT))));
        boolean hasObject = selected() != null;
        centerButton.active = hasObject;
        copyButton.active = hasObject;
        waypointButton.active = hasObject;
        boolean anyObject = !browsable().isEmpty();
        previousObjectButton.active = anyObject;
        nextObjectButton.active = anyObject;
        SeismicSurveyListPayload list = list();
        int count = list == null ? 0 : list.surveys().size();
        int index = surveyIndex(list);
        previousSurveyButton.active = count > 0 && index > 0;
        nextSurveyButton.active = count > 0 && index < count - 1;
    }

    private static String key(LidarRenderer.Mode mode) {
        return mode.name().toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------------------------------
    // Scan tab

    private List<Line> equipmentSummary() {
        return List.of(
                Line.of(text("level", level(), ScannerUpgrade.MAX_LEVEL), VanillaUi.TEXT_ACCENT),
                Line.of(
                        text("analyzer", analyzer(), CaveAnalyzer.MAX_LEVEL),
                        VanillaUi.TEXT_ACCENT));
    }

    private int baseTicks(SurveyMode mode) {
        return menu.value(
                mode == SurveyMode.REGIONAL
                        ? SeismicStationMenu.DATA_REGIONAL_TICKS
                        : SeismicStationMenu.DATA_DETAILED_TICKS);
    }

    private int maximumRadius(SurveyMode mode) {
        return menu.value(
                mode == SurveyMode.REGIONAL
                        ? SeismicStationMenu.DATA_REGIONAL_RADIUS
                        : SeismicStationMenu.DATA_DETAILED_RADIUS);
    }

    private List<Line> presetLines() {
        SurveyPreset preset = presets().get(presetSlider.index());
        int level = level();
        int analyzer = analyzer();
        int ticks =
                SurveyPreset.durationTicks(
                        baseTicks(preset.mode()),
                        preset.radius(),
                        maximumRadius(preset.mode()),
                        level);
        int cost =
                menu.value(
                                preset.mode() == SurveyMode.REGIONAL
                                        ? SeismicStationMenu.DATA_REGIONAL_COST
                                        : SeismicStationMenu.DATA_DETAILED_COST)
                        * ticks;
        double cell = preset.cellSize(level);
        List<Line> lines = new ArrayList<>();
        lines.add(Line.of(text("estimate.radius", preset.radius()), VanillaUi.TEXT));
        lines.add(Line.of(text("estimate.cell", SurveyText.number(cell, 1)), VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text(
                                "estimate.accuracy",
                                SurveyText.number(
                                        cell * 0.5D * ScannerUpgrade.positionFactor(level), 1)),
                        VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text(
                                ScannerUpgrade.resolvesDepth(preset.mode(), level)
                                        ? "estimate.depth"
                                        : "estimate.no_depth"),
                        ScannerUpgrade.resolvesDepth(preset.mode(), level)
                                ? VanillaUi.TEXT
                                : VanillaUi.TEXT_WARNING));
        if (CaveAnalyzer.installed(analyzer)) {
            int caveRadius = CaveAnalyzer.radius(analyzer, preset.radius());
            lines.add(
                    Line.of(
                            text("estimate.caves", caveRadius, CaveAnalyzer.spacing(analyzer)),
                            VanillaUi.TEXT));
            double speed = CaveAnalyzer.sweepSpeed(analyzer);
            int analysis = (int) Math.round(ticks / speed) - ticks;
            lines.add(
                    Line.of(
                            analysis > 0
                                    ? text("estimate.duration_caves", ticks / 20, analysis / 20)
                                    : text("estimate.duration", ticks / 20),
                            VanillaUi.TEXT));
        } else {
            lines.add(Line.of(text("estimate.no_caves"), VanillaUi.TEXT_DIM));
            lines.add(Line.of(text("estimate.duration", ticks / 20), VanillaUi.TEXT));
        }
        lines.add(Line.of(text("estimate.energy", SurveyText.integer(cost)), VanillaUi.TEXT));
        return lines;
    }

    private double progressFraction() {
        return menu.value(SeismicStationMenu.DATA_PROGRESS) / 1000.0D;
    }

    private Component progressLabel() {
        StationState state = stationState();
        if (!state.surveying() && state != StationState.FINISHING) {
            return text("progress.idle");
        }
        return text("progress", Math.round(progressFraction() * 100.0D));
    }

    private List<Line> frontLines() {
        StationState state = stationState();
        if (!state.surveying()) {
            return List.of();
        }
        List<Line> lines = new ArrayList<>();
        lines.add(
                Line.of(
                        text(
                                "front.pulse",
                                menu.value(SeismicStationMenu.DATA_FRONT),
                                menu.value(SeismicStationMenu.DATA_RADIUS)),
                        VanillaUi.TEXT));
        int caves = menu.value(SeismicStationMenu.DATA_CAVE_RADIUS);
        if (caves > 0) {
            lines.add(
                    Line.of(
                            text(
                                    "front.caves",
                                    menu.value(SeismicStationMenu.DATA_CAVE_FRONT),
                                    caves),
                            VanillaUi.TEXT));
        }
        lines.add(
                Line.of(
                        text("stage." + state.name().toLowerCase(Locale.ROOT)),
                        VanillaUi.TEXT_DIM));
        return lines;
    }

    private Component energyLabel() {
        return text(
                "energy",
                SurveyText.integer(menu.value(SeismicStationMenu.DATA_ENERGY)),
                SurveyText.integer(menu.value(SeismicStationMenu.DATA_CAPACITY)));
    }

    private double energyFraction() {
        return menu.value(SeismicStationMenu.DATA_ENERGY)
                / (double) Math.max(1, menu.value(SeismicStationMenu.DATA_CAPACITY));
    }

    // ------------------------------------------------------------------------------------------
    // Object, log and equipment tabs

    private double columnMiddle() {
        double top = site().getY();
        double bottom = top - 64.0D;
        for (LidarCoverage c : map().coverage()) {
            bottom = Math.min(bottom, c.bottomY());
        }
        return (top + bottom) * 0.5D;
    }

    private List<Line> objectLines() {
        LidarObject object = selected();
        if (object == null) {
            List<Line> lines = new ArrayList<>();
            lines.add(Line.of(text("object.none"), VanillaUi.TEXT_DIM));
            lines.add(Line.of(text("object.count", browsable().size()), VanillaUi.TEXT_DIM));
            return lines;
        }
        long time =
                minecraft != null && minecraft.level != null ? minecraft.level.getGameTime() : 0L;
        long stale = menu.value(SeismicStationMenu.DATA_STALE_DAYS) * 24_000L;
        List<Line> lines = new ArrayList<>();
        for (LidarObjectActions.Line line :
                LidarObjectActions.describe(object, columnMiddle(), site().getY(), time, stale)) {
            lines.add(Line.of(line.text(), LidarObjectActions.color(line.style())));
        }
        return lines;
    }

    private List<Line> surveyLines() {
        SeismicSurveyListPayload list = list();
        int index = surveyIndex(list);
        if (list == null || index < 0) {
            return List.of(Line.of(text("survey.none"), VanillaUi.TEXT_DIM));
        }
        SeismicSurveyListPayload.Entry entry = list.surveys().get(index);
        List<Line> lines = new ArrayList<>();
        lines.add(
                Line.of(
                        text(
                                "survey.label",
                                index + 1,
                                list.surveys().size(),
                                text("mode." + SurveyMode.byId(entry.mode()).serializedName()),
                                entry.radius()),
                        VanillaUi.TEXT_ACCENT));
        lines.add(
                Line.of(
                        text(
                                "survey.where",
                                entry.centerX(),
                                entry.centerZ(),
                                SurveyText.number(entry.gameTime() / 24000.0D, 1)),
                        VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text("survey.level", entry.level(), ScannerUpgrade.MAX_LEVEL),
                        VanillaUi.TEXT));
        SeismicSurveyMapPayload report = report();
        if (report == null) {
            lines.add(Line.of(text("survey.loading"), VanillaUi.TEXT_DIM));
            return lines;
        }
        lines.add(
                Line.of(
                        text(
                                "survey.geophones",
                                Math.round(report.metric(SeismicSurveyMapPayload.METRIC_GEOPHONES)),
                                SurveyText.number(
                                        report.metric(SeismicSurveyMapPayload.METRIC_RECEIVERS),
                                        1)),
                        VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text(
                                "survey.coverage",
                                SurveyText.percent(
                                        report.metric(SeismicSurveyMapPayload.METRIC_COVERAGE))),
                        VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text(
                                "survey.quality",
                                SurveyText.percent(
                                        report.metric(SeismicSurveyMapPayload.METRIC_QUALITY))),
                        VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text(
                                "survey.lateral",
                                SurveyText.number(
                                        report.metric(SeismicSurveyMapPayload.METRIC_LATERAL), 1)),
                        VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text(
                                "survey.depth_error",
                                SurveyText.percent(
                                        report.metric(SeismicSurveyMapPayload.METRIC_DEPTH_ERROR))),
                        VanillaUi.TEXT));
        lines.add(
                Line.of(
                        text(
                                "survey.targets",
                                report.targets().size(),
                                SurveyText.number(
                                        report.metric(SeismicSurveyMapPayload.METRIC_PEAK), 1)),
                        VanillaUi.TEXT));
        return lines;
    }

    private List<Line> scannerLines() {
        int level = level();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.of(text("level", level, ScannerUpgrade.MAX_LEVEL), VanillaUi.TEXT_ACCENT));
        if (level == 0) {
            lines.add(Line.of(text("level.none"), VanillaUi.TEXT_DIM));
        } else {
            MutableComponent improvements = Component.empty();
            for (int i = 1; i <= level; i++) {
                if (i > 1) {
                    improvements.append(", ");
                }
                improvements.append(UpgradeModuleItem.improvementName(i));
            }
            lines.add(Line.of(text("level.improvements", improvements), VanillaUi.TEXT));
        }
        if (level < ScannerUpgrade.MAX_LEVEL) {
            lines.add(
                    Line.of(
                            text("level.next", UpgradeModuleItem.improvementName(level + 1)),
                            VanillaUi.TEXT_DIM));
        }
        return lines;
    }

    private List<Line> analyzerLines() {
        int analyzer = analyzer();
        List<Line> lines = new ArrayList<>();
        lines.add(
                Line.of(text("analyzer", analyzer, CaveAnalyzer.MAX_LEVEL), VanillaUi.TEXT_ACCENT));
        if (!CaveAnalyzer.installed(analyzer)) {
            lines.add(Line.of(text("analyzer.none"), VanillaUi.TEXT_DIM));
        } else {
            lines.add(Line.of(analyzerEffects(analyzer), VanillaUi.TEXT));
        }
        if (analyzer < CaveAnalyzer.MAX_LEVEL) {
            lines.add(
                    Line.of(
                            text("analyzer.next", analyzerEffects(analyzer + 1)),
                            VanillaUi.TEXT_DIM));
        }
        return lines;
    }

    private static Component analyzerEffects(int level) {
        int step = CaveAnalyzer.confirmStep(level);
        return text(
                "analyzer.effects",
                CaveAnalyzer.spacing(level),
                CaveAnalyzer.radius(level, Integer.MAX_VALUE),
                SurveyText.number(CaveAnalyzer.jitter(level), 1),
                step <= 0 ? text("analyzer.links.unchecked") : text("analyzer.links.step", step));
    }

    private List<Line> oreTableLines() {
        SeismicSurveyListPayload list = list();
        List<Line> lines = new ArrayList<>();
        lines.add(Line.of(text("ores.title"), VanillaUi.TEXT_ACCENT));
        if (list == null || list.ores().isEmpty()) {
            lines.add(Line.of(text("ores.none"), VanillaUi.TEXT_DIM));
            return lines;
        }
        lines.add(Line.of(text("ores.legend"), VanillaUi.TEXT_DIM));
        List<SeismicSurveyListPayload.OreRow> rows = new ArrayList<>(list.ores());
        rows.sort(Comparator.comparingDouble(row -> -row.prevalence()));
        for (SeismicSurveyListPayload.OreRow row : rows) {
            lines.add(
                    Line.of(
                            text(
                                    "ores.row",
                                    SurveyText.blockName(row.oreId()),
                                    SurveyText.category(row.category()),
                                    SurveyText.number(row.prevalence() * 100.0D, 1),
                                    row.generationKnown()
                                            ? text("ores.heights", row.minY(), row.maxY())
                                            : text("ores.heights_unknown"),
                                    level() >= 1
                                            ? SurveyText.percent(row.identification())
                                            : text("ores.class_only"),
                                    SurveyText.percent(row.identificationMax())),
                            VanillaUi.TEXT));
        }
        return lines;
    }

    // ------------------------------------------------------------------------------------------
    // Rendering

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        applyLayout();
        VanillaUi.panel(graphics, layout.panel());
        Rect title = layout.title();
        if (title.width() > 8) {
            graphics.drawString(
                    font,
                    TextFit.ellipsize(this.title.getString(), title.width() - 2, measure),
                    title.x() + 2,
                    title.y() + (title.height() - font.lineHeight) / 2 + 1,
                    VanillaUi.TEXT,
                    false);
        }
        Rect viewRect = layout.view();
        VanillaUi.inset(graphics, viewRect, VIEW_BACKGROUND);
        renderView(graphics, viewRect);
        Rect sidebar = layout.sidebar();
        graphics.enableScissor(sidebar.x(), sidebar.y(), sidebar.right(), sidebar.bottom());
        for (Entry entry : entries) {
            entry.render(graphics);
        }
        graphics.disableScissor();
        if (sidebarMaxScroll > 0) {
            int track = sidebar.height();
            int thumb = Math.max(12, track * track / (track + sidebarMaxScroll));
            int thumbY = sidebar.y() + (track - thumb) * sidebarScroll / sidebarMaxScroll;
            VanillaUi.inset(
                    graphics,
                    new Rect(sidebar.right() - 4, sidebar.y(), 4, sidebar.height()),
                    0xFF8B8B8B);
            graphics.fill(sidebar.right() - 4, thumbY, sidebar.right(), thumbY + thumb, 0xFFE0E0E0);
        }
        renderStatus(graphics, mouseX, mouseY);
    }

    private void renderView(GuiGraphics graphics, Rect rect) {
        ClientLidarMap map = map();
        double radius = Math.max(64.0D, menu.value(SeismicStationMenu.DATA_RADIUS));
        double top = site().getY() + 24.0D;
        double bottom = site().getY() - 64.0D;
        double sx = site().getX() + 0.5D;
        double sz = site().getZ() + 0.5D;
        for (LidarCoverage c : map.coverage()) {
            radius = Math.max(radius, Math.hypot(c.x() - sx, c.z() - sz) + c.radius());
            bottom = Math.min(bottom, c.bottomY());
        }
        view.scene(radius, top, bottom);
        StationState state = stationState();
        LidarView.Fronts fronts =
                state.surveying()
                        ? new LidarView.Fronts(
                                menu.value(SeismicStationMenu.DATA_FRONT),
                                menu.value(SeismicStationMenu.DATA_CAVE_FRONT),
                                menu.value(SeismicStationMenu.DATA_RADIUS),
                                menu.value(SeismicStationMenu.DATA_CAVE_RADIUS))
                        : LidarView.Fronts.NONE;
        view.render(graphics, font, rect, map, lastMode, fronts, marks(), selectedObject);
        if (map.objects().isEmpty() && map.sampleCount() == 0) {
            Component hint = text(state.surveying() ? "view.waiting" : "view.empty");
            List<String> parts = TextFit.wrap(hint.getString(), rect.width() - 16, measure);
            int lineHeight = font.lineHeight + 2;
            int y = rect.centerY() - parts.size() * lineHeight / 2;
            for (String part : parts) {
                graphics.drawString(
                        font, part, rect.centerX() - font.width(part) / 2, y, 0xFFB0BCCC, true);
                y += lineHeight;
            }
        }
    }

    /** The station, its geophones and the boreholes around it. */
    private List<LidarView.Mark> marks() {
        List<LidarView.Mark> marks = new ArrayList<>();
        BlockPos pos = site();
        for (int i = 0; i < 6; i++) {
            marks.add(
                    new LidarView.Mark(
                            pos.getX() + 0.5D,
                            pos.getY() + i * 0.6D,
                            pos.getZ() + 0.5D,
                            0xFFFFFFFF,
                            4));
        }
        SeismicSurveyListPayload list = list();
        if (list == null) {
            return marks;
        }
        for (BlockPos geophone : list.geophones()) {
            marks.add(
                    new LidarView.Mark(
                            geophone.getX() + 0.5D,
                            geophone.getY() + 0.5D,
                            geophone.getZ() + 0.5D,
                            0xFFFFE65A,
                            3));
        }
        for (SeismicSurveyListPayload.Hole hole : list.boreholes()) {
            for (int y = hole.top().getY(); y >= hole.bottomY(); y -= 3) {
                boolean ore =
                        hole.ore().isPresent() && y <= hole.oreTopY() && y >= hole.oreBottomY();
                marks.add(
                        new LidarView.Mark(
                                hole.top().getX() + 0.5D,
                                y,
                                hole.top().getZ() + 0.5D,
                                ore ? 0xFFFFC23A : 0x90B0B0B0,
                                ore ? 3 : 2));
            }
        }
        return marks;
    }

    private void renderStatus(GuiGraphics graphics, int mouseX, int mouseY) {
        Rect status = layout.status();
        Component left;
        if (layout.view().contains(mouseX, mouseY)) {
            left = text("status.controls");
        } else {
            left = statusText();
        }
        Component right = text("status.points", view.points(), map().objects().size());
        int rightWidth = font.width(right);
        int y = status.y() + (status.height() - font.lineHeight) / 2 + 1;
        boolean roomForRight = status.width() - rightWidth - 8 > 80;
        int leftWidth = roomForRight ? status.width() - rightWidth - 12 : status.width() - 4;
        graphics.drawString(
                font,
                TextFit.ellipsize(left.getString(), leftWidth, measure),
                status.x() + 2,
                y,
                VanillaUi.TEXT,
                false);
        if (roomForRight) {
            graphics.drawString(
                    font, right, status.right() - rightWidth - 2, y, VanillaUi.TEXT_DIM, false);
        }
    }

    private Component statusText() {
        int status = menu.value(SeismicStationMenu.DATA_STATUS);
        StationState state = stationState();
        if (state == StationState.NO_POWER) {
            return text("status.no_power");
        }
        if (state.surveying()) {
            return text("status.surveying", Math.round(progressFraction() * 100.0D));
        }
        return switch (status) {
            case 1 -> text("status.finished");
            case 2 -> text("status.failed");
            case 3 -> text("status.cancelled");
            case SeismicStationBlockEntity.STATUS_MOVED -> text("status.moved");
            default -> text("status.idle");
        };
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {}

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        renderTooltip(graphics, mouseX, mouseY);
    }

    // ------------------------------------------------------------------------------------------
    // Input

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // The container screen treats any click on its panel as a slot click; the view sits on
        // the panel, so it goes first, after the one button drawn over it. A click on that button
        // while it is inactive must not reach the view either.
        if (clearMapButton.visible
                && mouseX >= clearMapButton.getX()
                && mouseX < clearMapButton.getRight()
                && mouseY >= clearMapButton.getY()
                && mouseY < clearMapButton.getBottom()) {
            clearMapButton.mouseClicked(mouseX, mouseY, button);
            return true;
        }
        if (view.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(
            double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (view.mouseDragged(mouseX, mouseY, button, dragX, dragY)) {
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        int picked = view.mouseReleased(mouseX, mouseY, button);
        if (picked != Integer.MIN_VALUE) {
            if (picked >= 0) {
                select(picked);
            } else {
                selectedObject = -1;
            }
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (view.mouseScrolled(mouseX, mouseY, scrollY)) {
            return true;
        }
        if (layout.sidebar().contains(mouseX, mouseY) && sidebarMaxScroll > 0) {
            sidebarScroll =
                    Mth.clamp(
                            sidebarScroll - (int) Math.round(scrollY * 12.0D), 0, sidebarMaxScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private static Component text(String key, Object... args) {
        return SurveyText.text(key, args);
    }

    // ------------------------------------------------------------------------------------------
    // Side column entries

    private record Line(Component text, int color) {
        static Line of(Component text, int color) {
            return new Line(text, color);
        }
    }

    private interface Entry {
        boolean shown();

        int height(int width);

        void place(Rect rect, boolean visible);

        void render(GuiGraphics graphics);
    }

    private final class TextEntry implements Entry {
        private final Supplier<List<Line>> lines;
        private final BooleanSupplier condition;
        private Rect rect = Rect.EMPTY;
        private boolean visible;

        private TextEntry(Supplier<List<Line>> lines, BooleanSupplier condition) {
            this.lines = lines;
            this.condition = condition;
        }

        @Override
        public boolean shown() {
            return condition.getAsBoolean() && !lines.get().isEmpty();
        }

        @Override
        public int height(int width) {
            int count = 0;
            for (Line line : lines.get()) {
                count += TextFit.wrap(line.text().getString(), width - 2, measure).size();
            }
            return count * (font.lineHeight + 1);
        }

        @Override
        public void place(Rect rect, boolean visible) {
            this.rect = rect;
            this.visible = visible;
        }

        @Override
        public void render(GuiGraphics graphics) {
            if (!visible) {
                return;
            }
            int y = rect.y();
            for (Line line : lines.get()) {
                for (String part :
                        TextFit.wrap(line.text().getString(), rect.width() - 2, measure)) {
                    graphics.drawString(font, part, rect.x() + 1, y, line.color(), false);
                    y += font.lineHeight + 1;
                }
            }
        }
    }

    private static final class WidgetEntry implements Entry {
        private final AbstractWidget widget;
        private final BooleanSupplier condition;

        private WidgetEntry(AbstractWidget widget, BooleanSupplier condition) {
            this.widget = widget;
            this.condition = condition;
        }

        @Override
        public boolean shown() {
            return condition.getAsBoolean();
        }

        @Override
        public int height(int width) {
            return BUTTON_HEIGHT;
        }

        @Override
        public void place(Rect rect, boolean visible) {
            widget.visible = visible;
            widget.setRectangle(rect.width(), BUTTON_HEIGHT, rect.x(), rect.y());
        }

        @Override
        public void render(GuiGraphics graphics) {}
    }

    private final class RowEntry implements Entry {
        private final AbstractWidget first;
        private final AbstractWidget second;
        private final BooleanSupplier condition;

        private RowEntry(AbstractWidget first, AbstractWidget second, BooleanSupplier condition) {
            this.first = first;
            this.second = second;
            this.condition = condition;
        }

        /** Two labels too wide for half the column each go on separate rows. */
        private boolean stacked(int width) {
            int half = (width - GAP) / 2;
            return font.width(first.getMessage()) + 8 > half
                    || font.width(second.getMessage()) + 8 > half;
        }

        @Override
        public boolean shown() {
            return condition.getAsBoolean();
        }

        @Override
        public int height(int width) {
            return stacked(width) ? BUTTON_HEIGHT * 2 + GAP : BUTTON_HEIGHT;
        }

        @Override
        public void place(Rect rect, boolean visible) {
            first.visible = visible;
            second.visible = visible;
            if (stacked(rect.width())) {
                first.setRectangle(rect.width(), BUTTON_HEIGHT, rect.x(), rect.y());
                second.setRectangle(
                        rect.width(), BUTTON_HEIGHT, rect.x(), rect.y() + BUTTON_HEIGHT + GAP);
                return;
            }
            List<Rect> cells = ColumnLayout.row(rect.withHeight(BUTTON_HEIGHT), 2, GAP);
            first.setRectangle(cells.get(0).width(), BUTTON_HEIGHT, cells.get(0).x(), rect.y());
            second.setRectangle(cells.get(1).width(), BUTTON_HEIGHT, cells.get(1).x(), rect.y());
        }

        @Override
        public void render(GuiGraphics graphics) {}
    }

    private final class BarEntry implements Entry {
        private final Supplier<Component> label;
        private final Supplier<Double> fraction;
        private final int color;
        private final BooleanSupplier condition;
        private Rect rect = Rect.EMPTY;
        private boolean visible;

        private BarEntry(
                Supplier<Component> label,
                Supplier<Double> fraction,
                int color,
                BooleanSupplier condition) {
            this.label = label;
            this.fraction = fraction;
            this.color = color;
            this.condition = condition;
        }

        @Override
        public boolean shown() {
            return condition.getAsBoolean();
        }

        @Override
        public int height(int width) {
            return font.lineHeight + 8;
        }

        @Override
        public void place(Rect rect, boolean visible) {
            this.rect = rect;
            this.visible = visible;
        }

        @Override
        public void render(GuiGraphics graphics) {
            if (!visible) {
                return;
            }
            graphics.drawString(
                    font,
                    TextFit.ellipsize(label.get().getString(), rect.width() - 2, measure),
                    rect.x() + 1,
                    rect.y(),
                    VanillaUi.TEXT,
                    false);
            VanillaUi.bar(
                    graphics,
                    new Rect(rect.x() + 1, rect.y() + font.lineHeight + 2, rect.width() - 2, 4),
                    fraction.get(),
                    color);
        }
    }

    /** The one survey slider: programme and radius from close and fine to wide and coarse. */
    private final class PresetSlider extends AbstractSliderButton {
        private PresetSlider() {
            super(0, 0, 10, BUTTON_HEIGHT, Component.empty(), position(lastPreset));
            updateMessage();
        }

        private static double position(int index) {
            return index / (double) (SurveyPreset.COUNT - 1);
        }

        int index() {
            return Mth.clamp(
                    (int) Math.round(value * (SurveyPreset.COUNT - 1)), 0, SurveyPreset.COUNT - 1);
        }

        /** Re-reads radii that change with the scanner level. */
        void refresh() {
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            if (menu == null) {
                return;
            }
            List<SurveyPreset> presets = presets();
            SurveyPreset preset = presets.get(Math.min(index(), presets.size() - 1));
            setMessage(text("preset.slider", text("preset.name." + index())));
            setTooltip(Tooltip.create(text("preset.hint")));
        }

        @Override
        protected void applyValue() {
            value = position(index());
            lastPreset = index();
        }
    }
}

package dev.client.geology.lidar;

import dev.client.geology.FontMeasure;
import dev.client.geology.SurveyText;
import dev.client.geology.VanillaUi;
import dev.client.geology.layout.Rect;
import dev.client.geology.layout.TextFit;
import dev.world.geology.lidar.LidarObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * A full-window LiDAR view for equipment whose own console is too small for one, such as the
 * exploration drill. It shows the same map the console streams, live, with the selected structure's
 * details and actions beside it; closing it returns to the console.
 */
public final class LidarViewerScreen extends Screen {
    private static final int SIDE = 150;
    private static final int MARGIN = 8;

    /** The volume to frame and where depths are measured from. */
    public record Scene(double radius, double top, double bottom, double datumY) {}

    private final Screen parent;
    private final BlockPos owner;
    private final LidarView view;
    private final Supplier<Scene> scene;
    private final Supplier<List<LidarView.Mark>> marks;
    private LidarRenderer.Mode mode = LidarRenderer.Mode.BOTH;
    private int selected = -1;
    private boolean openingChild;
    private FontMeasure measure;
    private Button modeButton;
    private Button copyButton;
    private Button waypointButton;
    private Button centerButton;

    public LidarViewerScreen(
            Screen parent,
            Component title,
            BlockPos owner,
            double originX,
            double originY,
            double originZ,
            Supplier<Scene> scene,
            Supplier<List<LidarView.Mark>> marks) {
        super(title);
        this.parent = parent;
        this.owner = owner.immutable();
        this.view = new LidarView(originX, originY, originZ);
        this.scene = scene;
        this.marks = marks;
    }

    private Rect panel() {
        return new Rect(MARGIN, MARGIN, width - MARGIN * 2, height - MARGIN * 2);
    }

    private Rect viewRect() {
        Rect panel = panel();
        int side = Math.min(SIDE, panel.width() / 2);
        return new Rect(
                panel.x() + 6,
                panel.y() + font.lineHeight + 10,
                panel.width() - side - 16,
                panel.height() - font.lineHeight - 30);
    }

    private Rect side() {
        Rect panel = panel();
        Rect view = viewRect();
        return new Rect(
                view.right() + 6, view.y(), panel.right() - view.right() - 12, view.height());
    }

    @Override
    protected void init() {
        openingChild = false;
        measure = new FontMeasure(font);
        Rect side = side();
        int y = side.y();
        modeButton =
                addRenderableWidget(
                        Button.builder(Component.empty(), b -> mode = mode.next())
                                .bounds(side.x(), y, side.width(), 20)
                                .tooltip(Tooltip.create(SurveyText.text("display.hint")))
                                .build());
        y += 22;
        int half = (side.width() - 2) / 2;
        addRenderableWidget(
                Button.builder(SurveyText.text("view.reset"), b -> view.resetCamera())
                        .bounds(side.x(), y, half, 20)
                        .tooltip(Tooltip.create(SurveyText.text("view.reset.hint")))
                        .build());
        centerButton =
                addRenderableWidget(
                        Button.builder(SurveyText.text("view.center"), b -> center())
                                .bounds(side.x() + half + 2, y, side.width() - half - 2, 20)
                                .tooltip(Tooltip.create(SurveyText.text("view.center.hint")))
                                .build());
        y += 22;
        addRenderableWidget(
                Button.builder(SurveyText.text("object.previous"), b -> step(-1))
                        .bounds(side.x(), y, half, 20)
                        .tooltip(Tooltip.create(SurveyText.text("object.previous.hint")))
                        .build());
        addRenderableWidget(
                Button.builder(SurveyText.text("object.next"), b -> step(1))
                        .bounds(side.x() + half + 2, y, side.width() - half - 2, 20)
                        .tooltip(Tooltip.create(SurveyText.text("object.next.hint")))
                        .build());
        int bottom = side.bottom();
        addRenderableWidget(
                Button.builder(SurveyText.text("viewer.back"), b -> onClose())
                        .bounds(side.x(), bottom - 20, side.width(), 20)
                        .build());
        copyButton =
                addRenderableWidget(
                        Button.builder(SurveyText.text("object.copy"), b -> copy())
                                .bounds(side.x(), bottom - 64, side.width(), 20)
                                .tooltip(Tooltip.create(SurveyText.text("object.copy.hint")))
                                .build());
        waypointButton =
                addRenderableWidget(
                        Button.builder(SurveyText.text("object.waypoint"), b -> waypoint())
                                .bounds(side.x(), bottom - 42, side.width(), 20)
                                .tooltip(Tooltip.create(SurveyText.text("object.waypoint.hint")))
                                .build());
        LidarClientCache.requestSync(owner);
    }

    private ClientLidarMap map() {
        return LidarClientCache.map(owner);
    }

    private LidarObject object() {
        return map().object(selected);
    }

    private List<LidarObject> browsable() {
        List<LidarObject> objects = new ArrayList<>();
        for (LidarObject object : map().objects()) {
            if (object.kind().cave() ? mode.caves() : mode.ores()) {
                objects.add(object);
            }
        }
        objects.sort(Comparator.comparingDouble(LidarObject::y).reversed());
        return objects;
    }

    private void step(int direction) {
        List<LidarObject> objects = browsable();
        if (objects.isEmpty()) {
            return;
        }
        int index = -1;
        for (int i = 0; i < objects.size(); i++) {
            if (objects.get(i).id() == selected) {
                index = i;
            }
        }
        index = index < 0 ? 0 : Math.floorMod(index + direction, objects.size());
        selected = objects.get(index).id();
        view.focus(objects.get(index));
    }

    private void center() {
        LidarObject object = object();
        if (object != null) {
            view.focus(object);
        }
    }

    private double middle() {
        Scene current = scene.get();
        return (current.top() + current.bottom()) * 0.5D;
    }

    private void copy() {
        LidarObject object = object();
        if (object != null) {
            LidarObjectActions.copy(object, middle());
        }
    }

    private void waypoint() {
        LidarObject object = object();
        if (object != null) {
            openingChild = true;
            LidarObjectActions.placeWaypoint(this, object, middle());
            if (minecraft != null && minecraft.screen == this) {
                openingChild = false;
            }
        }
    }

    @Override
    public void tick() {
        if (map().object(selected) == null) {
            selected = -1;
        }
        boolean has = selected >= 0;
        copyButton.active = has;
        waypointButton.active = has;
        centerButton.active = has;
        modeButton.setMessage(
                SurveyText.text(
                        "display",
                        SurveyText.text("display." + mode.name().toLowerCase(Locale.ROOT))));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void renderBackground(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(graphics, mouseX, mouseY, partialTick);
        Rect panel = panel();
        VanillaUi.panel(graphics, panel);
        graphics.drawString(font, title, panel.x() + 7, panel.y() + 6, VanillaUi.TEXT, false);
        Rect rect = viewRect();
        VanillaUi.inset(graphics, rect, 0xFF06090E);
        Scene current = scene.get();
        view.scene(current.radius(), current.top(), current.bottom());
        view.render(
                graphics, font, rect, map(), mode, LidarView.Fronts.NONE, marks.get(), selected);
        renderDetails(graphics, current);
        Component status =
                rect.contains(mouseX, mouseY)
                        ? SurveyText.text("status.controls")
                        : SurveyText.text("status.points", view.points(), map().objects().size());
        graphics.drawString(
                font,
                TextFit.ellipsize(status.getString(), panel.width() - 14, measure),
                panel.x() + 7,
                panel.bottom() - font.lineHeight - 6,
                VanillaUi.TEXT,
                false);
    }

    private void renderDetails(GuiGraphics graphics, Scene current) {
        Rect side = side();
        int top = side.y() + 70;
        int bottom = side.bottom() - 68;
        graphics.enableScissor(side.x(), top, side.right(), bottom);
        int y = top;
        LidarObject object = object();
        List<LidarObjectActions.Line> lines = new ArrayList<>();
        if (object == null) {
            lines.add(
                    new LidarObjectActions.Line(
                            SurveyText.text("object.none"), LidarObjectActions.Style.DIM));
        } else {
            long time =
                    minecraft != null && minecraft.level != null
                            ? minecraft.level.getGameTime()
                            : 0L;
            lines.addAll(LidarObjectActions.describe(object, middle(), current.datumY(), time, 0L));
        }
        for (LidarObjectActions.Line line : lines) {
            for (String part : TextFit.wrap(line.text().getString(), side.width() - 2, measure)) {
                graphics.drawString(
                        font, part, side.x() + 1, y, LidarObjectActions.color(line.style()), false);
                y += font.lineHeight + 1;
            }
        }
        graphics.disableScissor();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) {
            return true;
        }
        return view.mouseClicked(mouseX, mouseY, button);
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
            selected = picked;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        return view.mouseScrolled(mouseX, mouseY, scrollY)
                || super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public void removed() {
        if (!openingChild) {
            view.close();
        }
    }

    @Override
    public void onClose() {
        if (minecraft != null) {
            minecraft.setScreen(parent);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}

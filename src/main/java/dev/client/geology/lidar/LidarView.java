package dev.client.geology.lidar;

import com.mojang.blaze3d.platform.InputConstants;
import dev.client.geology.SurveyText;
import dev.client.geology.layout.Rect;
import dev.world.geology.lidar.LidarCoverage;
import dev.world.geology.lidar.LidarObject;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * The interactive LiDAR viewport shared by the station and drill screens. Left drag turns the
 * camera around its target, right drag pans across the screen plane, Shift with right drag or the
 * middle button moves it up and down, the wheel zooms, and a click without dragging selects the
 * structure under the cursor. The camera stays usable while a survey is still adding points.
 */
public final class LidarView implements AutoCloseable {
    private static final int[] EXAGGERATIONS = {1, 2, 3, 4, 5, 6, 8, 10, 12, 16};
    private static final double DRAG_THRESHOLD = 3.0D;

    private final double originX;
    private final double originY;
    private final double originZ;
    private final LidarCamera camera = new LidarCamera();
    private final LidarRenderer renderer;
    private double sceneRadius = 64.0D;
    private double topY;
    private double bottomY;
    private int exaggeration = 1;
    private int dragButton = -1;
    private boolean dragMoved;
    private double dragStartX;
    private double dragStartY;
    private Rect lastRect = Rect.EMPTY;
    private Matrix4f lastView = new Matrix4f();
    private Matrix4f lastProjection = new Matrix4f();
    private final List<Picked> picked = new ArrayList<>();

    /** A marker drawn in the view: equipment, geophones, borehole points. */
    public record Mark(double x, double y, double z, int argb, int size) {}

    /** Survey fronts in blocks from the origin, or ≤ 0 when not travelling. */
    public record Fronts(double pulse, double cave, double pulseRadius, double caveRadius) {
        public static final Fronts NONE = new Fronts(0.0D, 0.0D, 0.0D, 0.0D);
    }

    private record Picked(int id, float x, float y, float depth, float radius) {}

    public LidarView(double originX, double originY, double originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.renderer = new LidarRenderer(originX, originY, originZ);
    }

    /**
     * Sets the volume the view frames: a horizontal radius around the origin between two heights.
     * The vertical exaggeration makes a wide, shallow survey readable; it is printed in the view.
     */
    public void scene(double radius, double top, double bottom) {
        boolean changed = Math.abs(radius - sceneRadius) > 0.5D || top != topY || bottom != bottomY;
        sceneRadius = Math.max(8.0D, radius);
        topY = top;
        bottomY = Math.min(bottom, top - 1.0D);
        int wanted = (int) Math.floor(sceneRadius * 0.5D / Math.max(16.0D, topY - bottomY));
        int chosen = 1;
        for (int value : EXAGGERATIONS) {
            if (value <= wanted) {
                chosen = value;
            }
        }
        exaggeration = chosen;
        if (changed || !camera.placed()) {
            resetCamera();
        }
    }

    public int exaggeration() {
        return exaggeration;
    }

    public void resetCamera() {
        double middle = ((topY + bottomY) * 0.5D - originY) * exaggeration;
        double height = (topY - bottomY) * exaggeration * 0.5D;
        camera.reset(Math.max(sceneRadius, height), middle);
    }

    /** Centres the camera on an object without changing the direction it looks from. */
    public void focus(LidarObject object) {
        double y = object.depthKnown() ? object.y() : (topY + bottomY) * 0.5D;
        double size = Math.max(Math.max(object.sizeX(), object.sizeZ()), 8.0D);
        if (Float.isFinite(object.sizeY())) {
            size = Math.max(size, object.sizeY() * exaggeration);
        }
        camera.focus(
                object.x() - originX, (y - originY) * exaggeration, object.z() - originZ, size);
    }

    public void render(
            GuiGraphics graphics,
            Font font,
            Rect rect,
            ClientLidarMap map,
            LidarRenderer.Mode mode,
            Fronts fronts,
            List<Mark> marks,
            int selected) {
        lastRect = rect;
        LidarQuality quality = LidarQuality.current();
        float top = (float) topY;
        float bottom = (float) bottomY;
        renderer.update(map, quality, id -> colorOf(map, id), top, bottom);
        renderer.highlight(map, selected, top, bottom);

        lastView = camera.view();
        lastProjection = camera.projection(rect.width(), rect.height(), sceneRadius * 2.0D);
        List<float[]> overlay = overlay(map, mode, fronts, marks, selected);
        graphics.flush();
        renderer.render(
                new LidarRenderer.Frame(
                        rect.x(),
                        rect.y(),
                        rect.width(),
                        rect.height(),
                        lastView,
                        lastProjection,
                        camera.eye(),
                        exaggeration,
                        mode,
                        (float) fronts.pulse(),
                        (float) fronts.cave(),
                        (float) Math.max(4.0D, sceneRadius * 0.04D),
                        overlay),
                quality);
        labels(graphics, font, rect, map, mode, selected);
    }

    private int colorOf(ClientLidarMap map, int id) {
        LidarObject object = map.object(id);
        return object == null ? 0 : LidarNames.color(object);
    }

    private static boolean shown(LidarObject object, LidarRenderer.Mode mode) {
        return object.kind().cave() ? mode.caves() : mode.ores();
    }

    private List<float[]> overlay(
            ClientLidarMap map,
            LidarRenderer.Mode mode,
            Fronts fronts,
            List<Mark> marks,
            int selected) {
        List<float[]> points = new ArrayList<>();
        for (LidarCoverage c : map.coverage()) {
            if (c.cave() ? !mode.caves() : !mode.ores()) {
                continue;
            }
            int color = c.cave() ? 0x705FD8FF : 0x6080C090;
            ring(points, c.x(), c.topY(), c.z(), c.radius(), color, 2);
            ring(points, c.x(), c.bottomY(), c.z(), c.radius(), (color & 0xFFFFFF) | 0x40000000, 2);
            for (int i = 0; i < 4; i++) {
                double angle = Math.PI * 0.5D * i;
                double x = c.x() + Math.cos(angle) * c.radius();
                double z = c.z() + Math.sin(angle) * c.radius();
                line(points, x, c.topY(), z, x, c.bottomY(), z, (color & 0xFFFFFF) | 0x30000000);
            }
        }
        if (fronts.pulse() > 0.0D && fronts.pulse() < fronts.pulseRadius() && mode.ores()) {
            for (int level = 0; level <= 6; level++) {
                double y = topY - (topY - bottomY) * level / 6.0D;
                ring(
                        points,
                        originX,
                        y,
                        originZ,
                        fronts.pulse(),
                        level == 0 ? 0xF0C8FFF0 : 0x5090FFD8,
                        level == 0 ? 3 : 2);
            }
        }
        if (fronts.cave() > 0.0D && fronts.cave() < fronts.caveRadius() && mode.caves()) {
            for (int level = 0; level <= 4; level++) {
                double y = topY - (topY - bottomY) * level / 4.0D;
                ring(points, originX, y, originZ, fronts.cave(), 0x80C77DFF, 2);
            }
        }
        for (Mark mark : marks) {
            points.add(
                    LidarRenderer.overlayPoint(
                            mark.x(),
                            mark.y(),
                            mark.z(),
                            originX,
                            originY,
                            originZ,
                            mark.argb(),
                            mark.size()));
        }
        LidarObject chosen = map.object(selected);
        if (chosen != null) {
            box(points, chosen);
        }
        return points;
    }

    private void ring(
            List<float[]> points,
            double cx,
            double y,
            double cz,
            double radius,
            int argb,
            int size) {
        int n = (int) Math.max(48, Math.min(360, radius * 0.75D));
        for (int i = 0; i < n; i++) {
            double angle = Math.PI * 2.0D * i / n;
            points.add(
                    LidarRenderer.overlayPoint(
                            cx + Math.cos(angle) * radius,
                            y,
                            cz + Math.sin(angle) * radius,
                            originX,
                            originY,
                            originZ,
                            argb,
                            size));
        }
    }

    private void line(
            List<float[]> points,
            double ax,
            double ay,
            double az,
            double bx,
            double by,
            double bz,
            int argb) {
        double length =
                Math.sqrt(
                        (bx - ax) * (bx - ax)
                                + (by - ay) * (by - ay) * exaggeration * exaggeration
                                + (bz - az) * (bz - az));
        int n = (int) Math.max(2, Math.min(96, length / 4.0D));
        for (int i = 0; i <= n; i++) {
            double t = i / (double) n;
            points.add(
                    LidarRenderer.overlayPoint(
                            ax + (bx - ax) * t,
                            ay + (by - ay) * t,
                            az + (bz - az) * t,
                            originX,
                            originY,
                            originZ,
                            argb,
                            2));
        }
    }

    /** Corner brackets around the selected object's estimated extent, errors included. */
    private void box(List<float[]> points, LidarObject object) {
        double hx = Math.max(1.0D, object.sizeX() * 0.5D + object.positionError());
        double hz = Math.max(1.0D, object.sizeZ() * 0.5D + object.positionError());
        double cy;
        double hy;
        if (object.depthKnown()) {
            cy = object.y();
            double half = Float.isFinite(object.sizeY()) ? object.sizeY() * 0.5D : 0.0D;
            hy =
                    Math.max(
                            1.0D,
                            half
                                    + (Float.isFinite(object.depthError())
                                            ? object.depthError()
                                            : 4.0D));
        } else {
            cy = (topY + bottomY) * 0.5D;
            hy = (topY - bottomY) * 0.5D;
        }
        double radius = Math.max(hx, hz);
        double arm = radius * 0.35D;
        for (int sx = -1; sx <= 1; sx += 2) {
            for (int sy = -1; sy <= 1; sy += 2) {
                for (int sz = -1; sz <= 1; sz += 2) {
                    double x = object.x() + sx * radius;
                    double y = cy + sy * hy;
                    double z = object.z() + sz * radius;
                    line(points, x, y, z, x - sx * arm, y, z, 0xF0FFFFFF);
                    line(points, x, y, z, x, y, z - sz * arm, 0xF0FFFFFF);
                    line(points, x, y, z, x, y - sy * Math.min(hy * 0.5D, arm), z, 0xF0FFFFFF);
                }
            }
        }
    }

    private float[] screen(double x, double y, double z) {
        return LidarCamera.project(
                lastView,
                lastProjection,
                (float) (x - originX),
                (float) ((y - originY) * exaggeration),
                (float) (z - originZ),
                lastRect.width(),
                lastRect.height());
    }

    private void labels(
            GuiGraphics graphics,
            Font font,
            Rect rect,
            ClientLidarMap map,
            LidarRenderer.Mode mode,
            int selected) {
        picked.clear();
        graphics.enableScissor(rect.x(), rect.y(), rect.right(), rect.bottom());
        List<LidarObject> objects = new ArrayList<>(map.objects());
        // Survey targets come before caves: an analyser maps dozens of confident caves, which
        // would otherwise take every label and leave the deposits unmarked.
        objects.sort(
                Comparator.comparing((LidarObject o) -> o.kind().cave())
                        .thenComparingDouble(o -> -o.confidence()));
        int labelled = 0;
        for (LidarObject object : objects) {
            if (!shown(object, mode)) {
                continue;
            }
            double y = object.depthKnown() ? object.y() : (topY + bottomY) * 0.5D;
            float[] centre = screen(object.x(), y, object.z());
            if (centre == null) {
                continue;
            }
            float radiusBlocks = Math.max(object.sizeX(), object.sizeZ()) * 0.5F;
            float[] edge = screen(object.x() + radiusBlocks, y, object.z());
            float radius =
                    edge == null
                            ? 6.0F
                            : (float) Math.hypot(edge[0] - centre[0], edge[1] - centre[1]);
            picked.add(new Picked(object.id(), centre[0], centre[1], centre[2], radius));
            boolean isSelected = object.id() == selected;
            if (!isSelected && (labelled >= 24 || object.confidence() < 0.2F)) {
                continue;
            }
            int px = rect.x() + Math.round(centre[0]);
            int py = rect.y() + Math.round(centre[1]);
            if (px < rect.x() || py < rect.y() || px > rect.right() || py > rect.bottom()) {
                continue;
            }
            labelled++;
            String label = isSelected ? "▶ " + LidarNames.name(object).getString() : "•";
            int color = isSelected ? 0xFFFFFF60 : 0xFF000000 | LidarNames.color(object);
            graphics.drawString(font, label, px + 3, py - font.lineHeight / 2, color, true);
        }
        float[] north = screen(originX, topY, originZ - sceneRadius * 1.08D);
        if (north != null) {
            Component label = SurveyText.text("compass.north");
            graphics.drawString(
                    font,
                    label,
                    rect.x() + Math.round(north[0]) - font.width(label) / 2,
                    rect.y() + Math.round(north[1]) - 4,
                    0xFFE0E0E0,
                    true);
        }
        if (exaggeration > 1) {
            graphics.drawString(
                    font,
                    SurveyText.text("view.exaggeration", exaggeration),
                    rect.x() + 4,
                    rect.bottom() - font.lineHeight - 3,
                    0xFF9FB0C8,
                    true);
        }
        graphics.disableScissor();
    }

    /** The object under the cursor in the last frame, nearest to the camera, or -1. */
    public int pick(double mouseX, double mouseY) {
        double localX = mouseX - lastRect.x();
        double localY = mouseY - lastRect.y();
        int best = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        for (Picked p : picked) {
            double distance = Math.hypot(localX - p.x(), localY - p.y());
            double reach = Math.max(6.0D, p.radius());
            if (distance <= reach) {
                // Prefer the object whose centre is closest relative to its size, then the nearest.
                double score = distance / reach + p.depth() * 1.0E-6D;
                if (score < bestScore) {
                    bestScore = score;
                    best = p.id();
                }
            }
        }
        return best;
    }

    /** Starts a drag inside the view; returns whether the view took the click. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!lastRect.contains((int) mouseX, (int) mouseY)) {
            return false;
        }
        dragButton = button;
        dragMoved = false;
        dragStartX = mouseX;
        dragStartY = mouseY;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (dragButton != button) {
            return false;
        }
        if (Math.hypot(mouseX - dragStartX, mouseY - dragStartY) > DRAG_THRESHOLD) {
            dragMoved = true;
        }
        if (!dragMoved) {
            return true;
        }
        boolean shift = Screen.hasShiftDown();
        if (button == InputConstants.MOUSE_BUTTON_LEFT && !shift) {
            camera.rotate(dx, dy);
        } else if (button == InputConstants.MOUSE_BUTTON_MIDDLE
                || (button == InputConstants.MOUSE_BUTTON_RIGHT && shift)) {
            camera.lift(dy, lastRect.height());
        } else {
            camera.pan(dx, dy, lastRect.height());
        }
        return true;
    }

    /**
     * Ends a drag. Returns the picked object id (or -1 for empty space) when it was a click, or
     * {@link Integer#MIN_VALUE} when it was a drag or not this view's.
     */
    public int mouseReleased(double mouseX, double mouseY, int button) {
        if (dragButton != button) {
            return Integer.MIN_VALUE;
        }
        dragButton = -1;
        if (dragMoved || button != InputConstants.MOUSE_BUTTON_LEFT) {
            return Integer.MIN_VALUE;
        }
        return pick(mouseX, mouseY);
    }

    public boolean mouseScrolled(double mouseX, double mouseY, double scrollY) {
        if (!lastRect.contains((int) mouseX, (int) mouseY)) {
            return false;
        }
        camera.zoom(scrollY);
        return true;
    }

    public boolean dragging() {
        return dragButton >= 0 && dragMoved;
    }

    public int points() {
        return renderer.points();
    }

    /** Short help of the camera controls, for a tooltip. */
    public static List<Component> controls() {
        return List.of(
                SurveyText.text("view.controls.rotate"),
                SurveyText.text("view.controls.pan"),
                SurveyText.text("view.controls.lift"),
                SurveyText.text("view.controls.zoom"),
                SurveyText.text("view.controls.select"));
    }

    @Override
    public void close() {
        Minecraft.getInstance().execute(renderer::close);
    }

    public Vector3f target() {
        return camera.target();
    }
}

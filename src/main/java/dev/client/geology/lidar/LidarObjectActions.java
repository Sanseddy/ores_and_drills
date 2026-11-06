package dev.client.geology.lidar;

import dev.client.geology.SurveyText;
import dev.client.map.MapWaypoints;
import dev.world.geology.CaveAnalysis;
import dev.world.geology.SurveyTarget;
import dev.world.geology.lidar.LidarObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;

/**
 * What the screens say about a selected structure and what the player can do with it: copy its
 * coordinates and place a map waypoint. Every outcome is reported as it really happened.
 */
public final class LidarObjectActions {
    private static final SystemToast.SystemToastId TOAST = new SystemToast.SystemToastId(3500L);

    private LidarObjectActions() {}

    /** A line of the details panel; {@code style} picks its colour. */
    public record Line(Component text, Style style) {}

    public enum Style {
        TITLE,
        NORMAL,
        DIM,
        WARNING
    }

    /**
     * @param fallbackY height quoted for a target whose depth was not resolved
     * @param datumY height depths are measured from
     * @param staleTicks age from which a measurement counts as outdated, or ≤ 0 to ignore age
     */
    public static List<Line> describe(
            LidarObject object, double fallbackY, double datumY, long gameTime, long staleTicks) {
        List<Line> lines = new ArrayList<>();
        lines.add(new Line(LidarNames.name(object), Style.TITLE));
        lines.add(
                new Line(text("kind." + object.kind().name().toLowerCase(Locale.ROOT)), Style.DIM));
        LidarCoordinates at = LidarCoordinates.of(object, fallbackY);
        lines.add(new Line(coordinates(at), Style.NORMAL));
        if (at.approximate()) {
            lines.add(
                    new Line(
                            at.depthError() < 0
                                    ? text("approximate.no_depth", at.error())
                                    : text("approximate", at.error(), at.depthError()),
                            Style.WARNING));
        }
        if (object.depthKnown()) {
            lines.add(
                    new Line(
                            text("depth", Math.max(0, Math.round(datumY - object.y()))),
                            Style.NORMAL));
        } else {
            lines.add(new Line(text("depth.unknown"), Style.WARNING));
        }
        switch (object.kind()) {
            case TARGET -> target(object, lines);
            case CAVE, CORE_VOID -> cave(object, lines);
            case CORE_ORE -> core(object, lines);
        }
        lines.add(
                new Line(
                        text("confidence", SurveyText.percent(object.confidence())), Style.NORMAL));
        if (object.kind() == LidarObject.Kind.TARGET || object.kind() == LidarObject.Kind.CAVE) {
            long age = Math.max(0L, gameTime - object.time());
            lines.add(
                    new Line(
                            text(
                                    "measured",
                                    object.surveys(),
                                    SurveyText.number(age / 24000.0D, 1)),
                            Style.DIM));
            if (staleTicks > 0L && age >= staleTicks) {
                lines.add(new Line(text("stale"), Style.WARNING));
            }
        }
        return lines;
    }

    private static void target(LidarObject object, List<Line> lines) {
        lines.add(
                new Line(
                        text(
                                "size",
                                Math.round(object.sizeX()),
                                Math.round(object.sizeZ()),
                                Float.isFinite(object.sizeY())
                                        ? Component.literal(
                                                String.valueOf(Math.round(object.sizeY())))
                                        : text("unresolved")),
                        Style.NORMAL));
        lines.add(
                new Line(
                        text(
                                "signal",
                                SurveyText.number(object.intensity(), 1),
                                SurveyText.percent(LidarNames.oreProbability(object))),
                        Style.NORMAL));
        candidates(object, lines);
    }

    private static void cave(LidarObject object, List<Line> lines) {
        lines.add(
                new Line(
                        text(
                                "cave.size",
                                Math.round(object.sizeX()),
                                Math.round(object.sizeY()),
                                Math.round(object.sizeZ())),
                        Style.NORMAL));
        if (object.volume() > 0.0F) {
            lines.add(new Line(text("cave.volume", Math.round(object.volume())), Style.NORMAL));
        }
        String fill =
                switch (object.fill()) {
                    case CaveAnalysis.WATER -> "water";
                    case CaveAnalysis.LAVA -> "lava";
                    default -> "air";
                };
        lines.add(new Line(text("cave.fill." + fill), Style.NORMAL));
        if (object.kind() == LidarObject.Kind.CAVE) {
            lines.add(
                    new Line(
                            text("cave.links", object.links(), object.suspectedLinks()),
                            Style.NORMAL));
        }
    }

    private static void core(LidarObject object, List<Line> lines) {
        lines.add(new Line(text("core.interval", Math.round(object.sizeY())), Style.NORMAL));
        candidates(object, lines);
    }

    private static void candidates(LidarObject object, List<Line> lines) {
        if (object.candidates().isEmpty()) {
            lines.add(new Line(text("candidates.none"), Style.DIM));
            return;
        }
        lines.add(new Line(text("candidates"), Style.NORMAL));
        for (SurveyTarget.Candidate candidate : object.candidates()) {
            lines.add(
                    new Line(
                            text(
                                    "candidate",
                                    LidarNames.candidateName(candidate),
                                    SurveyText.percent(candidate.probability())),
                            Style.NORMAL));
        }
    }

    public static Component coordinates(LidarCoordinates at) {
        Component plain = Component.literal(at.clipboard());
        return at.approximate() ? text("coordinates.approximate", plain) : plain;
    }

    /** Copies the object's coordinates to the clipboard and confirms it. */
    public static void copy(LidarObject object, double fallbackY) {
        LidarCoordinates at = LidarCoordinates.of(object, fallbackY);
        Minecraft minecraft = Minecraft.getInstance();
        minecraft.keyboardHandler.setClipboard(at.clipboard());
        toast(
                text("copied"),
                at.approximate()
                        ? text("copied.approximate", at.clipboard())
                        : Component.literal(at.clipboard()));
    }

    /**
     * Places a waypoint in the one installed map mod, lets the player choose when several are
     * installed, and says so when none is.
     */
    public static void placeWaypoint(Screen parent, LidarObject object, double fallbackY) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            return;
        }
        LidarCoordinates at = LidarCoordinates.of(object, fallbackY);
        MapWaypoints.Request request =
                new MapWaypoints.Request(
                        LidarNames.name(object).getString(),
                        new BlockPos(at.x(), at.y(), at.z()),
                        minecraft.level.dimension(),
                        LidarNames.color(object),
                        at.approximate());
        List<MapWaypoints.Target> targets = MapWaypoints.available();
        if (targets.isEmpty()) {
            toast(
                    text("waypoint.none.title"),
                    MapWaypoints.onlyXaeroWorldMap()
                            ? text("waypoint.xaero_world_map_only")
                            : text("waypoint.none"));
            return;
        }
        if (targets.size() == 1) {
            place(targets.getFirst(), request);
            return;
        }
        minecraft.setScreen(new WaypointTargetScreen(parent, request, targets));
    }

    static void place(MapWaypoints.Target target, MapWaypoints.Request request) {
        MapWaypoints.Outcome outcome = MapWaypoints.add(target, request);
        Component title = text("waypoint." + outcome.name().toLowerCase(Locale.ROOT) + ".title");
        Component body =
                outcome == MapWaypoints.Outcome.ADDED || outcome == MapWaypoints.Outcome.DUPLICATE
                        ? text("waypoint.where", MapWaypoints.label(request), target.label())
                        : text(
                                "waypoint." + outcome.name().toLowerCase(Locale.ROOT),
                                target.label());
        toast(title, body);
    }

    private static void toast(Component title, Component body) {
        SystemToast.addOrUpdate(Minecraft.getInstance().getToasts(), TOAST, title, body);
    }

    static Component text(String key, Object... args) {
        return SurveyText.text("lidar." + key, args);
    }

    /** Formatting of a details line in the panel's style. */
    public static int color(Style style) {
        return switch (style) {
            case TITLE -> 0xFF1F3F7F;
            case NORMAL -> 0xFF404040;
            case DIM -> 0xFF6B6B6B;
            case WARNING -> 0xFF8B2E00;
        };
    }
}

package dev.compat.xaero;

import dev.client.map.MapWaypoints;
import java.io.IOException;
import java.util.Locale;
import xaero.common.minimap.waypoints.Waypoint;
import xaero.hud.minimap.BuiltInHudModules;
import xaero.hud.minimap.module.MinimapSession;
import xaero.hud.minimap.waypoint.WaypointColor;
import xaero.hud.minimap.waypoint.WaypointPurpose;
import xaero.hud.minimap.waypoint.set.WaypointSet;
import xaero.hud.minimap.world.MinimapWorld;

/**
 * Waypoints in Xaero's Minimap, which Xaero's World Map shows as well. Xaero keeps the waypoints of
 * the dimension the player is in as the current world, so a waypoint goes there and the world file
 * is saved as Xaero's own waypoint screen does. Only loaded when the minimap is installed.
 */
public final class XaeroWaypoints {
    private XaeroWaypoints() {}

    public static MapWaypoints.Outcome add(MapWaypoints.Request request) {
        MinimapSession session = BuiltInHudModules.MINIMAP.getCurrentSession();
        if (session == null || session.getWorldManager() == null) {
            return MapWaypoints.Outcome.NOT_READY;
        }
        MinimapWorld world = session.getWorldManager().getCurrentWorld();
        if (world == null || world.getCurrentWaypointSet() == null) {
            return MapWaypoints.Outcome.NOT_READY;
        }
        if (world.getDimId() != null && !world.getDimId().equals(request.dimension())) {
            return MapWaypoints.Outcome.WRONG_DIMENSION;
        }
        WaypointSet set = world.getCurrentWaypointSet();
        String name = MapWaypoints.label(request);
        for (Waypoint existing : set.getWaypoints()) {
            if (existing.getX() == request.pos().getX()
                    && existing.getY() == request.pos().getY()
                    && existing.getZ() == request.pos().getZ()
                    && name.equals(existing.getName())) {
                return MapWaypoints.Outcome.DUPLICATE;
            }
        }
        Waypoint waypoint =
                new Waypoint(
                        request.pos().getX(),
                        request.pos().getY(),
                        request.pos().getZ(),
                        name,
                        initials(request),
                        nearest(request.rgb()),
                        WaypointPurpose.NORMAL,
                        false);
        set.add(waypoint);
        try {
            session.getWorldManagerIO().saveWorld(world);
        } catch (IOException exception) {
            set.remove(waypoint);
            return MapWaypoints.Outcome.FAILED;
        }
        return MapWaypoints.Outcome.ADDED;
    }

    private static String initials(MapWaypoints.Request request) {
        String trimmed = request.name().trim();
        String first = trimmed.isEmpty() ? "?" : trimmed.substring(0, 1).toUpperCase(Locale.ROOT);
        return request.approximate() ? first + "~" : first;
    }

    /** Xaero offers a fixed palette; the closest of its colours stands in for the object's. */
    private static WaypointColor nearest(int rgb) {
        WaypointColor best = WaypointColor.WHITE;
        long bestDistance = Long.MAX_VALUE;
        for (WaypointColor color : WaypointColor.values()) {
            int hex = color.getHex();
            long dr = ((hex >> 16) & 0xFF) - ((rgb >> 16) & 0xFF);
            long dg = ((hex >> 8) & 0xFF) - ((rgb >> 8) & 0xFF);
            long db = (hex & 0xFF) - (rgb & 0xFF);
            long distance = dr * dr + dg * dg + db * db;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = color;
            }
        }
        return best;
    }
}

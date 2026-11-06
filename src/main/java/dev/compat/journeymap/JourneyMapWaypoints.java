package dev.compat.journeymap;

import dev.OresAndDrillsMod;
import dev.client.map.MapWaypoints;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.common.waypoint.Waypoint;
import journeymap.api.v2.common.waypoint.WaypointFactory;

/** Waypoints through JourneyMap's public client API. Only loaded when JourneyMap is installed. */
public final class JourneyMapWaypoints {
    private JourneyMapWaypoints() {}

    public static MapWaypoints.Outcome add(MapWaypoints.Request request) {
        IClientAPI api = OreMapJourneyMapPlugin.api();
        if (api == null) {
            return MapWaypoints.Outcome.NOT_READY;
        }
        String name = MapWaypoints.label(request);
        for (Waypoint existing : api.getAllWaypoints(request.dimension())) {
            if (existing.getBlockPos().equals(request.pos()) && name.equals(existing.getName())) {
                return MapWaypoints.Outcome.DUPLICATE;
            }
        }
        Waypoint waypoint =
                WaypointFactory.createClientWaypoint(
                        OresAndDrillsMod.MOD_ID, request.pos(), name, request.dimension(), true);
        waypoint.setColor(request.rgb() & 0xFFFFFF);
        api.addWaypoint(OresAndDrillsMod.MOD_ID, waypoint);
        for (Waypoint stored : api.getAllWaypoints(request.dimension())) {
            if (stored.getBlockPos().equals(request.pos()) && name.equals(stored.getName())) {
                return MapWaypoints.Outcome.ADDED;
            }
        }
        return MapWaypoints.Outcome.FAILED;
    }
}

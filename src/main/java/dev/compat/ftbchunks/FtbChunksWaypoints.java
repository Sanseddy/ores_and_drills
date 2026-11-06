package dev.compat.ftbchunks;

import dev.client.map.MapWaypoints;
import dev.ftb.mods.ftbchunks.api.FTBChunksAPI;
import dev.ftb.mods.ftbchunks.api.client.waypoint.Waypoint;
import dev.ftb.mods.ftbchunks.api.client.waypoint.WaypointManager;
import java.util.Optional;

/** Waypoints through FTB Chunks' client API. Only loaded when FTB Chunks is installed. */
public final class FtbChunksWaypoints {
    private FtbChunksWaypoints() {}

    public static MapWaypoints.Outcome add(MapWaypoints.Request request) {
        if (FTBChunksAPI.clientApi() == null) {
            return MapWaypoints.Outcome.NOT_READY;
        }
        Optional<WaypointManager> manager =
                FTBChunksAPI.clientApi().getWaypointManager(request.dimension());
        if (manager.isEmpty()) {
            return MapWaypoints.Outcome.NOT_READY;
        }
        String name = MapWaypoints.label(request);
        for (Waypoint existing : manager.get().getAllWaypoints()) {
            if (existing.getPos().equals(request.pos()) && name.equals(existing.getName())) {
                return MapWaypoints.Outcome.DUPLICATE;
            }
        }
        Waypoint added = manager.get().addWaypointAt(request.pos(), name);
        if (added == null) {
            return MapWaypoints.Outcome.FAILED;
        }
        added.setColor(0xFF000000 | (request.rgb() & 0xFFFFFF));
        return MapWaypoints.Outcome.ADDED;
    }
}

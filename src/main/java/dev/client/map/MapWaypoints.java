package dev.client.map;

import dev.OresAndDrillsMod;
import dev.compat.ftbchunks.FtbChunksWaypoints;
import dev.compat.journeymap.JourneyMapWaypoints;
import dev.compat.xaero.XaeroWaypoints;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.neoforged.fml.ModList;

/**
 * Places waypoints in the installed map mods. Each mod has its own adapter in {@code dev.compat},
 * touched only after the mod is confirmed loaded; an adapter reports what really happened, so a
 * waypoint is never announced unless the map mod accepted it.
 */
public final class MapWaypoints {
    private MapWaypoints() {}

    public enum Target {
        XAERO("gui.ores_and_drills.waypoint.target.xaero"),
        JOURNEYMAP("gui.ores_and_drills.waypoint.target.journeymap"),
        FTB_CHUNKS("gui.ores_and_drills.waypoint.target.ftb_chunks");

        private final String key;

        Target(String key) {
            this.key = key;
        }

        public Component label() {
            return Component.translatable(key);
        }
    }

    /**
     * A waypoint to place.
     *
     * @param approximate the position carries a survey error; the name says so
     */
    public record Request(
            String name,
            BlockPos pos,
            ResourceKey<Level> dimension,
            int rgb,
            boolean approximate) {}

    public enum Outcome {
        ADDED,
        DUPLICATE,
        /** The map mod is installed but not ready (no world or session yet). */
        NOT_READY,
        /** The map mod keeps waypoints only for the dimension the player is in. */
        WRONG_DIMENSION,
        FAILED
    }

    /** Map mods whose waypoint API is present. */
    public static List<Target> available() {
        ModList mods = ModList.get();
        List<Target> targets = new ArrayList<>();
        if (mods.isLoaded("xaerominimap") || mods.isLoaded("xaerominimapfair")) {
            targets.add(Target.XAERO);
        }
        if (mods.isLoaded("journeymap")) {
            targets.add(Target.JOURNEYMAP);
        }
        if (mods.isLoaded("ftbchunks")) {
            targets.add(Target.FTB_CHUNKS);
        }
        return targets;
    }

    /** Xaero's World Map alone has no waypoints of its own; it shows those of Xaero's Minimap. */
    public static boolean onlyXaeroWorldMap() {
        ModList mods = ModList.get();
        return mods.isLoaded("xaeroworldmap")
                && !mods.isLoaded("xaerominimap")
                && !mods.isLoaded("xaerominimapfair");
    }

    public static Outcome add(Target target, Request request) {
        try {
            return switch (target) {
                case XAERO -> XaeroWaypoints.add(request);
                case JOURNEYMAP -> JourneyMapWaypoints.add(request);
                case FTB_CHUNKS -> FtbChunksWaypoints.add(request);
            };
        } catch (LinkageError | RuntimeException error) {
            OresAndDrillsMod.LOGGER.warn("Could not place a waypoint in {}", target, error);
            return Outcome.FAILED;
        }
    }

    /** The waypoint's name: the object's name, marked when its position is approximate. */
    public static String label(Request request) {
        return request.approximate() ? "~ " + request.name() : request.name();
    }
}

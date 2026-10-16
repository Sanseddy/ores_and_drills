package dev.compat.journeymap;

import dev.OresAndDrillsMod;
import dev.client.map.OreMapMarkers;
import dev.client.map.OreMapPainter;
import dev.client.map.OreMapPatch;
import dev.client.map.OreMapTooltip;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.fullscreen.IThemeButton;
import journeymap.api.v2.client.model.MapPolygonWithHoles;
import journeymap.api.v2.client.model.ShapeProperties;
import journeymap.api.v2.client.util.PolygonHelper;
import journeymap.api.v2.common.JourneyMapPlugin;
import journeymap.api.v2.common.event.FullscreenEventRegistry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;

import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * JourneyMap integration through its public API: every patch becomes a native polygon overlay, so it shows
 * on the fullscreen map, the minimap and the web map with JourneyMap's own hover title, and the fullscreen
 * toolbar gets a native toggle button. Discovered by JourneyMap through the annotation.
 */
@JourneyMapPlugin(apiVersion = "2.0.0")
public final class OreMapJourneyMapPlugin implements IClientPlugin {
    private static final ResourceLocation ICON = ResourceLocation.fromNamespaceAndPath(
            OresAndDrillsMod.MOD_ID, "textures/gui/ore_map/ore_map_toggle_journeymap.png");
    private static final Map<Long, Shown> SHOWN = new HashMap<>();
    private static IClientAPI api;
    private static IThemeButton toggleButton;
    private static int syncedRevision;
    private static final int RETRY_TICKS = 100;
    private static Object syncedLevel;
    private static int retryCooldown;
    private static boolean warned;

    @Override
    public String getModId() {
        return OresAndDrillsMod.MOD_ID;
    }

    @Override
    public void initialize(IClientAPI clientApi) {
        api = clientApi;
        FullscreenEventRegistry.ADDON_BUTTON_DISPLAY_EVENT.subscribe(OresAndDrillsMod.MOD_ID, event ->
                toggleButton = event.getThemeButtonDisplay().addThemeToggleButton(
                        I18n.get("gui.ores_and_drills.ore_map.toggle"), ICON, OreMapMarkers.isEnabled(), button -> {
                            OreMapMarkers.toggle();
                            button.setToggled(OreMapMarkers.isEnabled());
                        }));
    }

    /** Mirrors the discovery store into JourneyMap overlays whenever it or the toggle changes. */
    public static void tick() {
        if (api == null || retryCooldown > 0 && --retryCooldown > 0) {
            return;
        }
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.level == null) {
            SHOWN.clear();
            syncedLevel = null;
            return;
        }
        int revision = OreMapMarkers.revision();
        if (minecraft.level == syncedLevel && revision == syncedRevision) {
            return;
        }
        try {
            if (minecraft.level != syncedLevel) {
                // A new world or dimension instance: start from a clean slate rather than trusting old overlays.
                api.removeAll(OresAndDrillsMod.MOD_ID);
                SHOWN.clear();
            }
            if (toggleButton != null) {
                toggleButton.setToggled(OreMapMarkers.isEnabled());
            }
            sync();
            syncedLevel = minecraft.level;
            syncedRevision = revision;
        } catch (Exception exception) {
            // JourneyMap may still be setting up the world; try again shortly instead of giving up.
            if (!warned) {
                warned = true;
                OresAndDrillsMod.LOGGER.warn("Could not show ore deposits on JourneyMap yet; retrying", exception);
            }
            retryCooldown = RETRY_TICKS;
        }
    }

    private static void sync() throws Exception {
        if (!OreMapMarkers.isEnabled()) {
            api.removeAll(OresAndDrillsMod.MOD_ID);
            SHOWN.clear();
            return;
        }
        Set<Long> present = new HashSet<>();
        for (OreMapPatch patch : OreMapMarkers.all()) {
            if (patch.isEmpty()) {
                continue;
            }
            present.add(patch.id());
            Shown shown = SHOWN.get(patch.id());
            if (shown != null && shown.patch() == patch) {
                continue;
            }
            if (shown != null) {
                shown.overlays().forEach(api::remove);
            }
            List<PolygonOverlay> overlays = overlaysFor(patch);
            for (PolygonOverlay overlay : overlays) {
                api.show(overlay);
            }
            SHOWN.put(patch.id(), new Shown(patch, overlays));
        }
        SHOWN.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().overlays().forEach(api::remove);
            return true;
        });
    }

    /** One flat-coloured polygon (with holes) per connected part of the patch, like Factorio's map tiles. */
    private static List<PolygonOverlay> overlaysFor(OreMapPatch patch) {
        int centerX = patch.center().getX();
        int centerZ = patch.center().getZ();
        int extent = patch.extent();
        Path2D.Double rows = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int z = centerZ - extent; z < centerZ + extent; z++) {
            int runStart = Integer.MIN_VALUE;
            for (int x = centerX - extent; x <= centerX + extent; x++) {
                boolean filled = x < centerX + extent && patch.cellAt(x, z) != null;
                if (filled && runStart == Integer.MIN_VALUE) {
                    runStart = x;
                } else if (!filled && runStart != Integer.MIN_VALUE) {
                    rows.append(new Rectangle2D.Double(runStart, z, x - runStart, 1.0D), false);
                    runStart = Integer.MIN_VALUE;
                }
            }
        }

        ShapeProperties shape = new ShapeProperties()
                .setFillColor(patch.baseColor())
                .setFillOpacity(OreMapPainter.TILE_ALPHA)
                .setStrokeOpacity(0.0F)
                .setStrokeWidth(0.0F);
        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION, patch.dimension());
        String title = OreMapTooltip.title(patch);
        List<PolygonOverlay> overlays = new ArrayList<>();
        for (MapPolygonWithHoles polygon : PolygonHelper.createPolygonFromArea(new Area(rows), patch.center().getY())) {
            PolygonOverlay overlay = new PolygonOverlay(OresAndDrillsMod.MOD_ID, dimension, shape, polygon);
            overlay.setTitle(title)
                    .setOverlayGroupName(I18n.get("gui.ores_and_drills.ore_map.group"))
                    .setMinZoom(0)
                    .setDisplayOrder(-1000);
            overlays.add(overlay);
        }
        return overlays;
    }

    private record Shown(OreMapPatch patch, List<PolygonOverlay> overlays) {
    }
}

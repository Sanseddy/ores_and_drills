package dev.compat.journeymap;

import dev.OresAndDrillsMod;
import dev.client.map.OreMapMarkers;
import dev.client.map.OreMapPainter;
import dev.client.map.OreMapPatch;
import dev.client.map.OreMapTooltip;
import journeymap.api.v2.client.IClientAPI;
import journeymap.api.v2.client.IClientPlugin;
import journeymap.api.v2.client.display.PolygonOverlay;
import journeymap.api.v2.client.display.IOverlayListener;
import journeymap.api.v2.client.fullscreen.IThemeButton;
import journeymap.api.v2.client.event.FullscreenRenderEvent;
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
import net.minecraft.core.BlockPos;
import journeymap.api.v2.client.util.UIState;

import java.awt.geom.Point2D;

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
    /** Amounts change every tick while something mines; rebuilding polygons twice a second is plenty. */
    private static final int MIN_TICKS_BETWEEN_SYNCS = 10;
    private static Object syncedLevel;
    private static boolean syncedEnabled;
    private static int ticksSinceSync;
    private static int retryCooldown;
    private static boolean warned;
    private static Shown hoveredShown;

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
        FullscreenEventRegistry.FULLSCREEN_RENDER_EVENT.subscribe(
                OresAndDrillsMod.MOD_ID, OreMapJourneyMapPlugin::renderHoverLabel
        );
    }

    private static void renderHoverLabel(FullscreenRenderEvent event) {
        if (hoveredShown != null && hoveredShown.highlighted && OreMapMarkers.isEnabled()) {
            OreMapTooltip.renderMapLabel(
                    event.getGraphics(), hoveredShown.patch(), event.getMouseX(), event.getMouseY()
            );
        }
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
        ticksSinceSync++;
        // A new level or a toggle is shown at once; changed amounts wait for the next sync slot.
        boolean urgent = minecraft.level != syncedLevel || OreMapMarkers.isEnabled() != syncedEnabled;
        if (!urgent && (revision == syncedRevision || ticksSinceSync < MIN_TICKS_BETWEEN_SYNCS)) {
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
            syncedEnabled = OreMapMarkers.isEnabled();
            ticksSinceSync = 0;
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
                if (hoveredShown == shown) {
                    hoveredShown = null;
                }
                shown.overlays().forEach(api::remove);
            }
            Shown replacement = overlaysFor(patch);
            for (PolygonOverlay overlay : replacement.overlays()) {
                api.show(overlay);
            }
            SHOWN.put(patch.id(), replacement);
        }
        SHOWN.entrySet().removeIf(entry -> {
            if (present.contains(entry.getKey())) {
                return false;
            }
            entry.getValue().overlays().forEach(api::remove);
            if (hoveredShown == entry.getValue()) {
                hoveredShown = null;
            }
            return true;
        });
    }

    /** One flat-coloured polygon (with holes) per connected part of the patch, like Factorio's map tiles. */
    private static Shown overlaysFor(OreMapPatch patch) {
        ResourceKey<net.minecraft.world.level.Level> dimension = ResourceKey.create(Registries.DIMENSION, patch.dimension());
        String title = OreMapTooltip.title(patch);
        List<PolygonOverlay> base = polygonsFor(patch, dimension, title, false);
        List<PolygonOverlay> checker = polygonsFor(patch, dimension, title, true);
        ArrayList<PolygonOverlay> all = new ArrayList<>(base.size() + checker.size());
        all.addAll(base);
        all.addAll(checker);
        Shown shown = new Shown(patch, List.copyOf(all), List.copyOf(checker));
        IOverlayListener listener = new IOverlayListener() {
            @Override
            public void onMouseMove(UIState mapState, Point2D.Double mousePosition, BlockPos blockPosition) {
                shown.setHighlighted(true);
                hoveredShown = shown;
            }

            @Override
            public void onMouseOut(UIState mapState, Point2D.Double mousePosition, BlockPos blockPosition) {
                shown.setHighlighted(false);
                if (hoveredShown == shown) {
                    hoveredShown = null;
                }
            }
        };
        all.forEach(overlay -> overlay.setOverlayListener(listener));
        return shown;
    }

    private static List<PolygonOverlay> polygonsFor(OreMapPatch patch,
                                                     ResourceKey<net.minecraft.world.level.Level> dimension,
                                                     String title, boolean outline) {
        int centerX = patch.center().getX();
        int centerZ = patch.center().getZ();
        int extent = patch.extent();
        Path2D.Double rows = new Path2D.Double(Path2D.WIND_NON_ZERO);
        for (int z = centerZ - extent; z < centerZ + extent; z++) {
            int runStart = Integer.MIN_VALUE;
            for (int x = centerX - extent; x <= centerX + extent; x++) {
                boolean filled = x < centerX + extent && patch.cellAt(x, z) != null
                        && (outline || ((x + z) & 1) == 0);
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
                .setFillOpacity(outline ? 0.0F : OreMapPainter.TILE_ALPHA)
                .setStrokeColor(0xFFFFFF)
                .setStrokeOpacity(0.0F)
                .setStrokeWidth(outline ? 2.0F : 0.0F);
        List<PolygonOverlay> overlays = new ArrayList<>();
        for (MapPolygonWithHoles polygon : PolygonHelper.createPolygonFromArea(new Area(rows), patch.center().getY())) {
            PolygonOverlay overlay = new PolygonOverlay(OresAndDrillsMod.MOD_ID, dimension, shape, polygon);
            overlay.setTitle(title)
                    .setOverlayGroupName(I18n.get("gui.ores_and_drills.ore_map.group"))
                    .setMinZoom(0)
                    .setDisplayOrder(outline ? -999 : -1000);
            overlays.add(overlay);
        }
        return overlays;
    }

    private static final class Shown {
        private final OreMapPatch patch;
        private final List<PolygonOverlay> overlays;
        private final List<PolygonOverlay> outlineOverlays;
        private boolean highlighted;

        private Shown(OreMapPatch patch, List<PolygonOverlay> overlays, List<PolygonOverlay> outlineOverlays) {
            this.patch = patch;
            this.overlays = overlays;
            this.outlineOverlays = outlineOverlays;
        }

        OreMapPatch patch() {
            return patch;
        }

        List<PolygonOverlay> overlays() {
            return overlays;
        }

        void setHighlighted(boolean value) {
            if (highlighted == value || api == null) {
                return;
            }
            highlighted = value;
            for (PolygonOverlay overlay : outlineOverlays) {
                overlay.getShapeProperties().setStrokeOpacity(value ? 1.0F : 0.0F);
                try {
                    api.show(overlay);
                } catch (Exception exception) {
                    OresAndDrillsMod.LOGGER.debug("Could not refresh JourneyMap ore hover", exception);
                }
            }
        }
    }
}

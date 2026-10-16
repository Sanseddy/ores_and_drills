package dev.compat.ftbchunks;

import dev.client.map.DiscoveredOreDeposit;
import dev.client.map.OreMapMarkers;
import dev.client.map.OreMapPainter;
import dev.client.map.OreMapPatch;
import dev.client.map.OreMapTooltip;
import dev.client.map.OreMapTransform;
import dev.ftb.mods.ftbchunks.api.client.event.MapIconEvent;
import dev.ftb.mods.ftbchunks.api.client.icon.MapIcon;
import dev.ftb.mods.ftbchunks.api.client.icon.MapType;
import dev.ftb.mods.ftbchunks.client.FTBChunksClient;
import dev.ftb.mods.ftbchunks.client.FTBChunksClientConfig;
import dev.ftb.mods.ftbchunks.client.gui.LargeMapScreen;
import dev.ftb.mods.ftblibrary.ui.BaseScreen;
import dev.ftb.mods.ftblibrary.ui.ScreenWrapper;
import dev.ftb.mods.ftblibrary.ui.input.Key;
import dev.ftb.mods.ftblibrary.ui.input.MouseButton;
import dev.ftb.mods.ftblibrary.util.TooltipList;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/**
 * FTB Chunks layer. FTB has no polygon overlays, but its zoom-dependent map icons are positioned and sized in
 * world units, so one icon per deposit, exactly as wide as the patch, can paint every tile at the right place
 * on both the large map and the minimap. Only loaded when FTB Chunks is installed.
 */
public final class FtbChunksOreLayer {
    /** Below every waypoint and entity icon. */
    private static final int PRIORITY = Integer.MIN_VALUE / 2;

    private FtbChunksOreLayer() {
    }

    public static void register() {
        MapIconEvent.LARGE_MAP.register(FtbChunksOreLayer::addIcons);
        MapIconEvent.MINIMAP.register(FtbChunksOreLayer::addIcons);
    }

    public static boolean isLargeMap(Screen screen) {
        return screen instanceof ScreenWrapper wrapper && wrapper.getGui() instanceof LargeMapScreen;
    }

    private static void addIcons(MapIconEvent event) {
        // Icons are only collected when the map refreshes, so they are always added and hide themselves
        // when the overlay is toggled off; that way the toggle applies instantly without a map refresh.
        for (OreMapPatch patch : OreMapMarkers.all()) {
            if (patch.dimension().equals(event.getDimension().location()) && !patch.isEmpty()) {
                event.add(new PatchIcon(patch));
            }
        }
    }

    /** FTB's smoothed minimap zoom divided as in its renderer: minimap pixels per block = size * this / 64. */
    private static double minimapZoom() {
        return FTBChunksClient.INSTANCE.getZoom() / 3.5D;
    }

    private static final class PatchIcon implements MapIcon {
        private final OreMapPatch patch;
        private final Vec3 position;
        private int lastX;
        private int lastY;
        private int lastSize;

        private PatchIcon(OreMapPatch patch) {
            this.patch = patch;
            // The icon is a square centred on the corner between the centre blocks, spanning +-extent.
            this.position = new Vec3(patch.center().getX(), patch.center().getY(), patch.center().getZ());
        }

        /** A refreshed discovery replaces the patch; the icon keeps showing whatever is newest (null if hidden). */
        private OreMapPatch current() {
            return OreMapMarkers.visible(patch.id());
        }

        @Override
        public Vec3 getPos(float partialTick) {
            return position;
        }

        @Override
        public boolean isVisible(MapType mapType, double distanceToPlayer, boolean outsideVisibleArea) {
            if (mapType.isWorldIcon()) {
                return false;
            }
            // The large map asks only once, when it builds its widgets, so it keeps every patch and draw()
            // honours the toggle; that lets the toggle button work while the map stays open.
            if (!mapType.isMinimap()) {
                return true;
            }
            // On the minimap, keep patches whose tiles can still reach inside the rim even if the centre can't.
            return OreMapMarkers.isEnabled()
                    && distanceToPlayer - patch.extent() * Math.sqrt(2.0D) <= 32.0D / minimapZoom();
        }

        @Override
        public boolean isZoomDependant(MapType mapType) {
            return true;
        }

        @Override
        public double getIconScale(MapType mapType) {
            OreMapPatch shown = current();
            double diameter = (shown != null ? shown : patch).extent() * 2.0D;
            // Large map: widget size = tileSize / 128 * scale, with tileSize pixels per 512 blocks.
            // Minimap: drawn size = minimapSize * scale / 16, with minimapSize * zoom / 64 pixels per block.
            return mapType.isMinimap() ? diameter * minimapZoom() / 4.0D : diameter / 4.0D;
        }

        @Override
        public int getPriority() {
            return PRIORITY;
        }

        @Override
        public void addTooltip(TooltipList list) {
            OreMapPatch shown = current();
            if (shown == null || lastSize <= 0) {
                return;
            }
            Minecraft minecraft = Minecraft.getInstance();
            double mouseX = minecraft.mouseHandler.xpos() * minecraft.getWindow().getGuiScaledWidth()
                    / minecraft.getWindow().getScreenWidth();
            double mouseY = minecraft.mouseHandler.ypos() * minecraft.getWindow().getGuiScaledHeight()
                    / minecraft.getWindow().getScreenHeight();
            double pixelsPerBlock = largeMapPixelsPerBlock(lastSize, shown);
            int blockX = (int) Math.floor(shown.center().getX() + (mouseX - lastX - lastSize / 2.0D) / pixelsPerBlock);
            int blockZ = (int) Math.floor(shown.center().getZ() + (mouseY - lastY - lastSize / 2.0D) / pixelsPerBlock);
            DiscoveredOreDeposit.OreMapCell cell = shown.cellAt(blockX, blockZ);
            if (cell == null) {
                return;
            }
            for (Component line : OreMapTooltip.lines(shown, cell)) {
                list.add(line);
            }
        }

        @Override
        public boolean onMousePressed(BaseScreen screen, MouseButton button) {
            return false;
        }

        @Override
        public boolean onKeyPressed(BaseScreen screen, Key key) {
            return false;
        }

        @Override
        public void draw(MapType mapType, GuiGraphics graphics, int x, int y, int w, int h,
                         boolean outsideVisibleArea, int iconAlpha) {
            OreMapPatch shown = current();
            if (shown == null) {
                return;
            }
            float alpha = OreMapPainter.TILE_ALPHA * iconAlpha / 255.0F;
            if (mapType.isMinimap()) {
                drawOnMinimap(graphics, shown, alpha);
            } else {
                lastX = x;
                lastY = y;
                lastSize = w;
                OreMapPainter.paint(graphics, shown,
                        OreMapTransform.axisAligned(largeMapPixelsPerBlock(w, shown), x + w / 2.0D, y + h / 2.0D),
                        alpha, null);
            }
        }

        /** The widget size is rounded up to whole pixels; the map's region tile size is the exact scale. */
        private static double largeMapPixelsPerBlock(int widgetSize, OreMapPatch shown) {
            if (Minecraft.getInstance().screen instanceof ScreenWrapper wrapper
                    && wrapper.getGui() instanceof LargeMapScreen largeMap) {
                return largeMap.getRegionTileSize() / 512.0D;
            }
            return widgetSize / (shown.extent() * 2.0D);
        }

        /**
         * The pose maps the unit square onto the icon. FTB rotates icon positions, not icon images, so the
         * tiles are rotated here and trimmed to the minimap's circle (or square) in world units. FTB also pins
         * icons whose centre lies beyond the rim onto the rim; that shift is undone so such a patch still shows
         * the part of it that reaches into the minimap, exactly where it belongs.
         */
        private void drawOnMinimap(GuiGraphics graphics, OreMapPatch shown, float alpha) {
            Minecraft minecraft = Minecraft.getInstance();
            Player player = minecraft.player;
            if (player == null) {
                return;
            }
            boolean square = FTBChunksClientConfig.SQUARE_MINIMAP.get();
            boolean locked = FTBChunksClientConfig.MINIMAP_LOCKED_NORTH.get() || square;
            float minimapRotation = (locked ? 180.0F : -player.getYRot()) % 360.0F;
            double radians = Math.toRadians(minimapRotation - 180.0F);
            double unitsPerBlock = 1.0D / (shown.extent() * 2.0D);

            double radius = 32.0D / minimapZoom();
            Vec3 eye = player.getPosition(minecraft.getTimer().getGameTimeDeltaPartialTick(false));
            double offsetX = shown.center().getX() - eye.x;
            double offsetZ = shown.center().getZ() - eye.z;
            double distance = Math.sqrt(offsetX * offsetX + offsetZ * offsetZ);
            double centerX = 0.5D;
            double centerY = 0.5D;
            if (distance > radius) {
                double pinnedBy = 1.0D - radius / distance;
                double shiftX = offsetX * pinnedBy * unitsPerBlock;
                double shiftZ = offsetZ * pinnedBy * unitsPerBlock;
                centerX += Math.cos(radians) * shiftX - Math.sin(radians) * shiftZ;
                centerY += Math.sin(radians) * shiftX + Math.cos(radians) * shiftZ;
            }
            OreMapTransform transform = OreMapTransform.rotated(unitsPerBlock, radians, centerX, centerY);

            OreMapPainter.TileFilter insideMinimap = (dx, dz, step) -> {
                double fromPlayerX = offsetX + dx;
                double fromPlayerZ = offsetZ + dz;
                return square
                        ? Math.abs(fromPlayerX) <= radius && Math.abs(fromPlayerZ) <= radius
                        : fromPlayerX * fromPlayerX + fromPlayerZ * fromPlayerZ <= radius * radius;
            };
            // levelFor() needs real pixels per block, not the unit-square scale of this pose.
            double pixelsPerBlock = graphics.pose().last().pose().m00() * unitsPerBlock;
            OreMapPainter.paint(graphics, shown, transform, alpha, insideMinimap, pixelsPerBlock);
        }
    }
}

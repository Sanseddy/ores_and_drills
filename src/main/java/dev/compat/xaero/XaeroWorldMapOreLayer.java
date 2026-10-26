package dev.compat.xaero;

import dev.client.map.OreMapMarkers;
import dev.client.map.OreMapPainter;
import dev.client.map.OreMapPatch;
import dev.client.map.OreMapTooltip;
import dev.client.map.OreMapTransform;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.WorldMap;
import xaero.map.element.MapElementRenderHandler;
import xaero.map.element.render.ElementReader;
import xaero.map.element.render.ElementRenderInfo;
import xaero.map.element.render.ElementRenderLocation;
import xaero.map.element.render.ElementRenderProvider;
import xaero.map.element.render.ElementRenderer;
import xaero.map.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;

/**
 * Xaero's World Map layer, registered as a native map element so patches pan, zoom and clip with
 * the map and sit underneath waypoints. Only loaded when Xaero's World Map is installed.
 */
public final class XaeroWorldMapOreLayer
        extends ElementRenderer<OreMapPatch, XaeroWorldMapOreLayer.Context, XaeroWorldMapOreLayer> {
    private static final XaeroWorldMapOreLayer INSTANCE = new XaeroWorldMapOreLayer();
    private static final long HOVER_TIMEOUT_NANOS = 200_000_000L;
    private static MapElementRenderHandler registeredOn;
    private static OreMapPatch hoveredPatch;
    private static long hoveredAt;

    private XaeroWorldMapOreLayer() {
        super(new Context(), new Provider(), new Reader());
    }

    /**
     * Xaero builds its element handler late in its own client load, so registration is retried each
     * tick.
     */
    public static void ensureRegistered() {
        if (!WorldMap.loaded) {
            return;
        }
        MapElementRenderHandler handler = WorldMap.mapElementRenderHandler;
        if (handler != null && handler != registeredOn) {
            handler.add(INSTANCE);
            registeredOn = handler;
        }
    }

    public static boolean isMapScreen(Screen screen) {
        // By name: GuiMap extends a class from the jar-in-jar xaerolib, which is not on the compile
        // classpath.
        return screen != null && screen.getClass().getName().equals("xaero.map.gui.GuiMap");
    }

    /** Factorio-style tooltip for the tile under the cursor, drawn after Xaero's own UI. */
    public static void renderTooltip(GuiGraphics graphics, int mouseX, int mouseY) {
        if (hoveredPatch == null
                || System.nanoTime() - hoveredAt > HOVER_TIMEOUT_NANOS
                || !OreMapMarkers.isEnabled()) {
            return;
        }
        OreMapTooltip.renderMapLabel(graphics, hoveredPatch, mouseX, mouseY);
    }

    @Override
    public boolean shouldRender(ElementRenderLocation location, boolean pre) {
        return !pre && location == ElementRenderLocation.WORLD_MAP && OreMapMarkers.isEnabled();
    }

    @Override
    public boolean shouldBeDimScaled() {
        // Patches are stored in the coordinates of the dimension they belong to, which is the one
        // on screen.
        return false;
    }

    @Override
    public int getOrder() {
        return -1_000_000;
    }

    @Override
    public void preRender(
            ElementRenderInfo info,
            MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider rendererProvider,
            boolean pre) {
        // Xaero batches its map frame; flushing it first keeps the patches above terrain and below
        // markers.
        buffers.endBatch();
        context.dimension = info.mapDimension;

        hoveredPatch = null;
        if (info.mapDimension != null) {
            int mouseX = (int) Math.floor(info.mouseX);
            int mouseZ = (int) Math.floor(info.mouseZ);
            OreMapPatch patch = OreMapMarkers.patchAt(info.mapDimension.location(), mouseX, mouseZ);
            if (patch != null) {
                hoveredPatch = patch;
                hoveredAt = System.nanoTime();
            }
        }
    }

    @Override
    public void postRender(
            ElementRenderInfo info,
            MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider rendererProvider,
            boolean pre) {}

    @Override
    public void renderElementShadow(
            OreMapPatch patch,
            boolean highlighted,
            float optionalScale,
            double partialX,
            double partialY,
            ElementRenderInfo info,
            GuiGraphics graphics,
            MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider rendererProvider) {}

    @Override
    public boolean renderElement(
            OreMapPatch patch,
            boolean highlighted,
            double optionalDepth,
            float optionalScale,
            double partialX,
            double partialY,
            ElementRenderInfo info,
            GuiGraphics graphics,
            MultiBufferSource.BufferSource buffers,
            MultiTextureRenderTypeRendererProvider rendererProvider) {
        // The pose is centred on the deposit centre in real screen pixels; one block is info.scale
        // pixels.
        OreMapPainter.paint(
                graphics,
                patch,
                OreMapTransform.axisAligned(info.scale, partialX, partialY),
                OreMapPainter.TILE_ALPHA,
                null,
                patch == hoveredPatch);
        return true;
    }

    static final class Context {
        private ResourceKey<Level> dimension;
        private List<OreMapPatch> patches = List.of();
        private int next;
    }

    private static final class Provider extends ElementRenderProvider<OreMapPatch, Context> {
        @Override
        public void begin(ElementRenderLocation location, Context context) {
            context.patches =
                    context.dimension == null
                            ? List.of()
                            : OreMapMarkers.inDimension(context.dimension.location());
            context.next = 0;
        }

        @Override
        public boolean hasNext(ElementRenderLocation location, Context context) {
            return context.next < context.patches.size();
        }

        @Override
        public OreMapPatch getNext(ElementRenderLocation location, Context context) {
            return context.patches.get(context.next++);
        }

        @Override
        public void end(ElementRenderLocation location, Context context) {
            context.patches = List.of();
        }
    }

    private static final class Reader
            extends ElementReader<OreMapPatch, Context, XaeroWorldMapOreLayer> {
        @Override
        public boolean isHidden(OreMapPatch patch, Context context) {
            return patch.isEmpty();
        }

        @Override
        public double getRenderX(OreMapPatch patch, Context context, float partialTicks) {
            return patch.center().getX();
        }

        @Override
        public double getRenderZ(OreMapPatch patch, Context context, float partialTicks) {
            return patch.center().getZ();
        }

        /** Xaero's default test assumes icon-sized boxes; a patch scales with the zoom instead. */
        @Override
        public boolean isOnScreen(
                OreMapPatch patch,
                double cameraX,
                double cameraZ,
                int width,
                int height,
                double scale,
                double screenSizeBasedScale,
                double rendererDimDiv,
                Context context,
                float partialTicks) {
            double x = (patch.center().getX() - cameraX) * scale + width / 2.0D;
            double z = (patch.center().getZ() - cameraZ) * scale + height / 2.0D;
            double extent = patch.extent() * scale + 1.0D;
            return x + extent >= 0.0D
                    && x - extent <= width
                    && z + extent >= 0.0D
                    && z - extent <= height;
        }

        @Override
        public int getInteractionBoxLeft(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxRight(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxTop(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getInteractionBoxBottom(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxLeft(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxRight(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxTop(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getRenderBoxBottom(OreMapPatch patch, Context context, float partialTicks) {
            return 0;
        }

        @Override
        public int getLeftSideLength(OreMapPatch patch, Minecraft minecraft) {
            return 0;
        }

        @Override
        public String getMenuName(OreMapPatch patch) {
            return OreMapTooltip.oreName(patch).getString();
        }

        @Override
        public String getFilterName(OreMapPatch patch) {
            return getMenuName(patch);
        }

        @Override
        public int getMenuTextFillLeftPadding(OreMapPatch patch) {
            return 0;
        }

        @Override
        public int getRightClickTitleBackgroundColor(OreMapPatch patch) {
            return 0;
        }

        @Override
        public boolean shouldScaleBoxWithOptionalScale() {
            return false;
        }
    }
}

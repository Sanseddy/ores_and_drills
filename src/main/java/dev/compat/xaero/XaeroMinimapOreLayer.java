package dev.compat.xaero;

import dev.OresAndDrillsMod;
import dev.client.map.OreMapMarkers;
import dev.client.map.OreMapPainter;
import dev.client.map.OreMapPatch;
import dev.client.map.OreMapTooltip;
import dev.client.map.OreMapTransform;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.world.phys.Vec3;
import xaero.common.HudMod;
import xaero.common.graphics.renderer.multitexture.MultiTextureRenderTypeRendererProvider;
import xaero.common.minimap.render.MinimapFBORenderer;
import xaero.hud.minimap.element.render.MinimapElementReader;
import xaero.hud.minimap.element.render.MinimapElementRenderInfo;
import xaero.hud.minimap.element.render.MinimapElementRenderLocation;
import xaero.hud.minimap.element.render.MinimapElementRenderProvider;
import xaero.hud.minimap.element.render.MinimapElementRenderer;
import xaero.hud.minimap.element.render.MinimapElementRendererHandler;
import xaero.hud.minimap.element.render.map.MinimapElementMapRendererHandler;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

/**
 * Xaero's Minimap layer. It renders inside the minimap's own framebuffer pass, so Xaero applies its round or
 * square mask, its rotation and its frame exactly as for the terrain underneath. Xaero exposes that pass's
 * handler and its rotation only as private fields, which are the sole reflective reads here.
 */
public final class XaeroMinimapOreLayer extends MinimapElementRenderer<OreMapPatch, XaeroMinimapOreLayer.Context> {
    private static final XaeroMinimapOreLayer INSTANCE = new XaeroMinimapOreLayer();
    private static Field handlerField;
    private static Field sinField;
    private static Field cosField;
    private static Field zoomField;
    private static Field halfViewField;
    private static boolean broken;
    private static MinimapElementRendererHandler registeredOn;

    private XaeroMinimapOreLayer() {
        super(new Reader(), new Provider(), new Context());
    }

    /** The minimap creates its framebuffer pass lazily on first render, so registration is retried each tick. */
    public static void ensureRegistered() {
        if (broken || HudMod.INSTANCE == null || HudMod.INSTANCE.getMinimap() == null) {
            return;
        }
        try {
            MinimapFBORenderer renderer = HudMod.INSTANCE.getMinimap().getMinimapFBORenderer();
            if (renderer == null) {
                return;
            }
            if (handlerField == null) {
                handlerField = MinimapFBORenderer.class.getDeclaredField("minimapElementMapRendererHandler");
                handlerField.setAccessible(true);
                sinField = field("ps");
                cosField = field("pc");
                zoomField = field("zoom");
                halfViewField = field("halfWView");
            }
            Object handler = handlerField.get(renderer);
            if (handler instanceof MinimapElementRendererHandler mapHandler && mapHandler != registeredOn) {
                mapHandler.add(INSTANCE);
                registeredOn = mapHandler;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            broken = true;
            OresAndDrillsMod.LOGGER.warn("Ore deposits cannot be drawn on this Xaero's Minimap version", exception);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        Field field = MinimapElementMapRendererHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @Override
    public int getOrder() {
        return -1_000_000;
    }

    @Override
    public boolean shouldRender(MinimapElementRenderLocation location) {
        return location == MinimapElementRenderLocation.IN_MINIMAP && OreMapMarkers.isEnabled() && !broken;
    }

    @Override
    public void preRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                          MultiTextureRenderTypeRendererProvider rendererProvider) {
        context.valid = false;
        if (!(registeredOn instanceof MinimapElementMapRendererHandler handler)) {
            return;
        }
        try {
            context.sin = sinField.getDouble(handler);
            context.cos = cosField.getDouble(handler);
            context.zoom = zoomField.getDouble(handler);
            context.halfView = halfViewField.getFloat(handler);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            broken = true;
            OresAndDrillsMod.LOGGER.warn("Ore deposits cannot be drawn on this Xaero's Minimap version", exception);
            return;
        }
        context.dimension = info.mapDimension != null ? info.mapDimension.location() : info.renderEntityDimension.location();
        context.renderPos = info.renderPos;
        context.valid = context.zoom > 0.0D;
    }

    @Override
    public void postRender(MinimapElementRenderInfo info, MultiBufferSource.BufferSource buffers,
                           MultiTextureRenderTypeRendererProvider rendererProvider) {
    }

    @Override
    public boolean renderElement(OreMapPatch patch, boolean highlighted, boolean outOfBounds, double optionalDepth,
                                 float optionalScale, double partialX, double partialY, MinimapElementRenderInfo info,
                                 GuiGraphics graphics, MultiBufferSource.BufferSource buffers) {
        // Xaero places each element at (ps*dx - pc*dz, pc*dx + ps*dz) * zoom; the cells follow the same rotation.
        double zoom = context.zoom;
        OreMapTransform transform = new OreMapTransform(
                context.sin * zoom, -context.cos * zoom, partialX,
                context.cos * zoom, context.sin * zoom, partialY
        );
        OreMapPainter.paint(graphics, patch, transform, OreMapPainter.TILE_ALPHA, null);
        return true;
    }

    static final class Context {
        private boolean valid;
        private double sin;
        private double cos;
        private double zoom;
        private float halfView;
        private net.minecraft.resources.ResourceLocation dimension;
        private Vec3 renderPos = Vec3.ZERO;
        private List<OreMapPatch> patches = List.of();
        private int next;
    }

    private static final class Provider extends MinimapElementRenderProvider<OreMapPatch, Context> {
        @Override
        public void begin(MinimapElementRenderLocation location, Context context) {
            context.next = 0;
            if (!context.valid) {
                context.patches = List.of();
                return;
            }
            // Only patches that can reach the visible square (its diagonal covers any rotation).
            double reach = context.halfView * Math.sqrt(2.0D) / context.zoom;
            ArrayList<OreMapPatch> visible = new ArrayList<>();
            for (OreMapPatch patch : OreMapMarkers.inDimension(context.dimension)) {
                double limit = reach + patch.extent();
                if (Math.abs(patch.center().getX() - context.renderPos.x) <= limit
                        && Math.abs(patch.center().getZ() - context.renderPos.z) <= limit) {
                    visible.add(patch);
                }
            }
            context.patches = visible;
        }

        @Override
        public boolean hasNext(MinimapElementRenderLocation location, Context context) {
            return context.next < context.patches.size();
        }

        @Override
        public OreMapPatch getNext(MinimapElementRenderLocation location, Context context) {
            return context.patches.get(context.next++);
        }

        @Override
        public void end(MinimapElementRenderLocation location, Context context) {
            context.patches = List.of();
        }
    }

    private static final class Reader extends MinimapElementReader<OreMapPatch, Context> {
        @Override
        public boolean isHidden(OreMapPatch patch, Context context) {
            return patch.isEmpty();
        }

        @Override
        public double getRenderX(OreMapPatch patch, Context context, float partialTicks) {
            return patch.center().getX();
        }

        @Override
        public double getRenderY(OreMapPatch patch, Context context, float partialTicks) {
            return patch.center().getY();
        }

        @Override
        public double getRenderZ(OreMapPatch patch, Context context, float partialTicks) {
            return patch.center().getZ();
        }

        @Override
        public double getCoordinateScale(OreMapPatch patch, Context context, MinimapElementRenderInfo info) {
            // Patch coordinates already belong to the dimension being drawn; never rescale them.
            return info.backgroundCoordinateScale;
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

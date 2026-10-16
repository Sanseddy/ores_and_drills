package dev.client.map;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexFormat;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.renderer.GameRenderer;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;

/**
 * Draws a patch as Factorio does: every resource tile is a flat coloured square painted straight over the
 * terrain. Each map integration only supplies the {@link OreMapTransform} from patch-local block offsets to
 * its own screen space, which keeps rotation, zoom and clipping identical across Xaero, JourneyMap and FTB.
 */
public final class OreMapPainter {
    /** Resource tiles cover the terrain almost completely, letting just a hint of relief shading through. */
    public static final float TILE_ALPHA = 0.9F;

    private OreMapPainter() {
    }

    /** Optional per-tile filter in patch-local block offsets, e.g. to keep tiles inside a round minimap. */
    @FunctionalInterface
    public interface TileFilter {
        boolean keep(double centerDx, double centerDz, int step);
    }

    public static void paint(GuiGraphics graphics, OreMapPatch patch, OreMapTransform transform, float alpha,
                             TileFilter filter) {
        paint(graphics, patch, transform, alpha, filter, transform.pixelsPerBlock());
    }

    /**
     * @param screenPixelsPerBlock real on-screen size of one block, for callers whose pose already scales the
     *                             transform's units (FTB's minimap draws icons in a scaled unit square)
     */
    public static void paint(GuiGraphics graphics, OreMapPatch patch, OreMapTransform transform, float alpha,
                             TileFilter filter, double screenPixelsPerBlock) {
        if (patch.isEmpty() || alpha <= 0.0F) {
            return;
        }
        OreMapTiling.Tiles tiles = patch.tilesFor(screenPixelsPerBlock);
        if (tiles.size() == 0) {
            return;
        }

        // Anything batched before us must land underneath the patch, and nothing of ours may be deferred.
        graphics.flush();
        Matrix4f pose = graphics.pose().last().pose();
        int alphaBits = Math.round(Math.max(0.0F, Math.min(1.0F, alpha)) * 255.0F) << 24;
        int step = tiles.step();
        BufferBuilder buffer = Tesselator.getInstance().begin(VertexFormat.Mode.QUADS, DefaultVertexFormat.POSITION_COLOR);
        for (int index = 0; index < tiles.size(); index++) {
            int dx = tiles.xs()[index];
            int dz = tiles.zs()[index];
            if (filter != null && !filter.keep(dx + step * 0.5D, dz + step * 0.5D, step)) {
                continue;
            }
            int color = alphaBits | tiles.colors()[index] & 0xFFFFFF;
            vertex(buffer, pose, transform, dx, dz, color);
            vertex(buffer, pose, transform, dx, dz + step, color);
            vertex(buffer, pose, transform, dx + step, dz + step, color);
            vertex(buffer, pose, transform, dx + step, dz, color);
        }
        MeshData mesh = buffer.build();
        if (mesh == null) {
            return;
        }

        // Maps differ in depth, cull and blend state (Xaero's minimap even draws into its own framebuffer),
        // so the patch sets exactly what it needs and puts the rest back the way it found it.
        boolean depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        boolean cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        boolean blend = GL11.glIsEnabled(GL11.GL_BLEND);
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.setShaderColor(1.0F, 1.0F, 1.0F, 1.0F);
        RenderSystem.setShader(GameRenderer::getPositionColorShader);
        BufferUploader.drawWithShader(mesh);
        if (depth) {
            RenderSystem.enableDepthTest();
        }
        if (cull) {
            RenderSystem.enableCull();
        }
        if (!blend) {
            RenderSystem.disableBlend();
        }
    }

    private static void vertex(BufferBuilder buffer, Matrix4f pose, OreMapTransform transform, double dx, double dz, int color) {
        buffer.addVertex(pose, (float) transform.x(dx, dz), (float) transform.y(dx, dz), 0.0F).setColor(color);
    }
}

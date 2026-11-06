package dev.client.geology.lidar;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.Tesselator;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.mojang.blaze3d.vertex.VertexFormat;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarObject;
import dev.world.geology.lidar.LidarSample;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.function.IntUnaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.FrustumIntersection;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Draws a {@link ClientLidarMap} as a point cloud. Each bucket of the map becomes its own GPU
 * buffers — one per view layer, each with a reduced copy for distance — built once and rebuilt only
 * when the server refines that bucket, a few buckets per frame. Buckets outside the view frustum
 * are skipped; distant ones and everything beyond the point budget draw their reduced copy. Points
 * blend additively, so no sorting is needed, and an optional second pass draws a soft glow.
 */
public final class LidarRenderer implements AutoCloseable {
    private static final int REBUILDS_PER_FRAME = 24;
    private static final int LOD_STRIDE = 4;
    private static final float[][] CORNERS = {{-1, -1}, {1, -1}, {1, 1}, {-1, 1}};

    private final double originX;
    private final double originY;
    private final double originZ;
    private final Map<Long, Mesh> meshes = new HashMap<>();
    private float epoch;
    private LidarQuality builtQuality;
    private Part bodies;
    private int bodiesVersion = -1;
    private Part highlight;
    private int highlightObject = -1;
    private int highlightVersion = -1;
    private VertexBuffer overlay;
    private int lastVersion = -1;

    /** Which layers a frame shows. */
    public enum Mode {
        ORES(true, false),
        CAVES(false, true),
        BOTH(true, true);

        private final boolean ores;
        private final boolean caves;

        Mode(boolean ores, boolean caves) {
            this.ores = ores;
            this.caves = caves;
        }

        public boolean ores() {
            return ores;
        }

        public boolean caves() {
            return caves;
        }

        public Mode next() {
            return values()[(ordinal() + 1) % values().length];
        }
    }

    private static final class Part implements AutoCloseable {
        private VertexBuffer full;
        private VertexBuffer reduced;
        private int points;
        private int reducedPoints;

        @Override
        public void close() {
            if (full != null) {
                full.close();
            }
            if (reduced != null) {
                reduced.close();
            }
        }
    }

    private static final class Mesh implements AutoCloseable {
        private int revision;
        private Part ore;
        private Part cave;
        private float minX;
        private float minY;
        private float minZ;
        private float maxX;
        private float maxY;
        private float maxZ;

        @Override
        public void close() {
            if (ore != null) {
                ore.close();
            }
            if (cave != null) {
                cave.close();
            }
        }
    }

    /** Collects points of one part, each kept as a quad of four vertices. */
    private static final class PointList implements LidarPoints.Sink {
        private final List<float[]> points = new ArrayList<>();
        private final double ox;
        private final double oy;
        private final double oz;
        private final float epoch;

        private PointList(double ox, double oy, double oz, float epoch) {
            this.ox = ox;
            this.oy = oy;
            this.oz = oz;
            this.epoch = epoch;
        }

        @Override
        public void point(float x, float y, float z, int argb, int size, float born) {
            points.add(
                    new float[] {
                        (float) (x - ox),
                        (float) (y - oy),
                        (float) (z - oz),
                        Float.intBitsToFloat(argb),
                        size,
                        Math.max(-32000.0F, Math.min(32000.0F, born - epoch))
                    });
        }
    }

    public LidarRenderer(double originX, double originY, double originZ) {
        this.originX = originX;
        this.originY = originY;
        this.originZ = originZ;
        this.epoch = LidarClientCache.now();
    }

    public float time() {
        return LidarClientCache.now() - epoch;
    }

    /**
     * Brings the GPU buffers up to date with the map, a few buckets per call.
     *
     * @param objectColor RGB of an object id, or 0
     */
    public void update(
            ClientLidarMap map,
            LidarQuality quality,
            IntUnaryOperator objectColor,
            float columnTop,
            float columnBottom) {
        if (time() > 30_000.0F || quality != builtQuality) {
            // Birth ticks are stored as shorts relative to the epoch; start a new one.
            epoch = LidarClientCache.now();
            builtQuality = quality;
            meshes.values().forEach(Mesh::close);
            meshes.clear();
            bodiesVersion = -1;
            highlightVersion = -1;
            lastVersion = -1;
        }
        if (map.version() != lastVersion) {
            Iterator<Map.Entry<Long, Mesh>> iterator = meshes.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Long, Mesh> entry = iterator.next();
                if (!map.buckets().containsKey(entry.getKey())) {
                    entry.getValue().close();
                    iterator.remove();
                }
            }
            int rebuilt = 0;
            boolean pending = false;
            for (Map.Entry<Long, ClientLidarMap.Bucket> entry : map.buckets().entrySet()) {
                Mesh mesh = meshes.get(entry.getKey());
                if (mesh != null && mesh.revision == entry.getValue().revision()) {
                    continue;
                }
                if (rebuilt >= REBUILDS_PER_FRAME) {
                    pending = true;
                    break;
                }
                if (mesh != null) {
                    mesh.close();
                }
                meshes.put(entry.getKey(), build(entry.getValue(), quality, objectColor));
                rebuilt++;
            }
            if (!pending) {
                lastVersion = map.version();
            }
        }
        int objectsVersion = map.objectVersion() * 31 + quality.ordinal();
        if (objectsVersion != bodiesVersion) {
            bodiesVersion = objectsVersion;
            if (bodies != null) {
                bodies.close();
            }
            PointList list = new PointList(originX, originY, originZ, epoch);
            for (LidarObject object : map.objects()) {
                LidarPoints.body(
                        object,
                        columnTop,
                        columnBottom,
                        quality.density(),
                        objectColor.applyAsInt(object.id()),
                        -1.0E6F,
                        list);
            }
            bodies = part(list.points);
        }
    }

    /** Rebuilds the highlight of {@code objectId} (or clears it with -1) when it changed. */
    public void highlight(ClientLidarMap map, int objectId, float columnTop, float columnBottom) {
        int version = map.version() * 31 + map.objectVersion();
        if (objectId == highlightObject && version == highlightVersion) {
            return;
        }
        highlightObject = objectId;
        highlightVersion = version;
        if (highlight != null) {
            highlight.close();
            highlight = null;
        }
        LidarObject object = map.object(objectId);
        if (object == null) {
            return;
        }
        PointList list = new PointList(originX, originY, originZ, epoch);
        LidarPoints.Sink white =
                (x, y, z, argb, size, born) -> list.point(x, y, z, 0xE0FFFFFF, size + 1, -1.0E6F);
        for (LidarSample sample : map.samplesOf(objectId)) {
            LidarPoints.sample(sample, -1.0E6F, 1.0F, 0xFFFFFF, white);
        }
        LidarPoints.body(object, columnTop, columnBottom, 1.0F, 0xFFFFFF, -1.0E6F, white);
        highlight = part(list.points);
    }

    private Mesh build(
            ClientLidarMap.Bucket bucket, LidarQuality quality, IntUnaryOperator colors) {
        PointList ore = new PointList(originX, originY, originZ, epoch);
        PointList cave = new PointList(originX, originY, originZ, epoch);
        Mesh mesh = new Mesh();
        mesh.minX = mesh.minY = mesh.minZ = Float.POSITIVE_INFINITY;
        mesh.maxX = mesh.maxY = mesh.maxZ = Float.NEGATIVE_INFINITY;
        List<LidarSample> samples = bucket.samples();
        for (int i = 0; i < samples.size(); i++) {
            LidarSample sample = samples.get(i);
            PointList target = sample.kind().layer() == LidarSample.Layer.CAVE ? cave : ore;
            int color = sample.object() > 0 ? colors.applyAsInt(sample.object()) : 0;
            LidarPoints.sample(sample, bucket.born()[i], quality.density(), color, target);
            float x = (float) (sample.x() - originX);
            float y = (float) (sample.y() - originY);
            float z = (float) (sample.z() - originZ);
            mesh.minX = Math.min(mesh.minX, x - sample.spreadH());
            mesh.maxX = Math.max(mesh.maxX, x + sample.spreadH());
            mesh.minY = Math.min(mesh.minY, y - sample.spreadV());
            mesh.maxY = Math.max(mesh.maxY, y + sample.spreadV());
            mesh.minZ = Math.min(mesh.minZ, z - sample.spreadH());
            mesh.maxZ = Math.max(mesh.maxZ, z + sample.spreadH());
        }
        mesh.revision = bucket.revision();
        mesh.ore = part(ore.points);
        mesh.cave = part(cave.points);
        return mesh;
    }

    private static Part part(List<float[]> points) {
        Part part = new Part();
        if (points.isEmpty()) {
            return part;
        }
        part.full = upload(points, 1);
        part.points = points.size();
        if (points.size() >= LOD_STRIDE * 4) {
            part.reduced = upload(points, LOD_STRIDE);
            part.reducedPoints = points.size() / LOD_STRIDE;
        }
        return part;
    }

    private static VertexBuffer upload(List<float[]> points, int stride) {
        BufferBuilder builder =
                Tesselator.getInstance()
                        .begin(
                                VertexFormat.Mode.QUADS,
                                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
        for (int i = 0; i < points.size(); i += stride) {
            float[] p = points.get(i);
            int argb = Float.floatToRawIntBits(p[3]);
            int size = (int) p[4];
            int born = (int) p[5];
            for (float[] corner : CORNERS) {
                builder.addVertex(p[0], p[1], p[2])
                        .setColor(argb)
                        .setUv(corner[0], corner[1])
                        .setUv2(born, size);
            }
        }
        MeshData data = builder.build();
        VertexBuffer buffer = new VertexBuffer(VertexBuffer.Usage.STATIC);
        if (data != null) {
            buffer.bind();
            buffer.upload(data);
            VertexBuffer.unbind();
        }
        return buffer;
    }

    /** One frame of the view; see {@link #render}. */
    public record Frame(
            int x,
            int y,
            int width,
            int height,
            Matrix4f view,
            Matrix4f projection,
            Vector3f eye,
            float exaggeration,
            Mode mode,
            float pulseRadius,
            float cavePulseRadius,
            float pulseWidth,
            List<float[]> overlay) {}

    /**
     * Draws the cloud into the view rectangle (GUI coordinates). The caller has flushed the GUI
     * batch; GL state is restored afterwards.
     */
    public void render(Frame frame, LidarQuality quality) {
        ShaderInstance shader = LidarShaders.point();
        if (shader == null || frame.width() <= 0 || frame.height() <= 0) {
            return;
        }
        Window window = Minecraft.getInstance().getWindow();
        double scale = window.getGuiScale();
        int fx = (int) Math.round(frame.x() * scale);
        int fw = (int) Math.round(frame.width() * scale);
        int fh = (int) Math.round(frame.height() * scale);
        int fy = window.getHeight() - (int) Math.round((frame.y() + frame.height()) * scale);
        Matrix4f modelView = new Matrix4f(frame.view()).scale(1.0F, frame.exaggeration(), 1.0F);
        FrustumIntersection frustum =
                new FrustumIntersection(new Matrix4f(frame.projection()).mul(modelView));

        RenderSystem.viewport(fx, fy, fw, fh);
        RenderSystem.enableScissor(fx, fy, fw, fh);
        RenderSystem.disableDepthTest();
        RenderSystem.depthMask(false);
        RenderSystem.disableCull();
        RenderSystem.enableBlend();
        RenderSystem.blendFunc(
                GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        RenderSystem.setShader(() -> shader);
        shader.safeGetUniform("ViewSize").set((float) fw, (float) fh);
        shader.safeGetUniform("LidarTime").set(time());
        shader.safeGetUniform("PulseRadius").set(frame.pulseRadius());
        shader.safeGetUniform("CavePulseRadius").set(frame.cavePulseRadius());
        shader.safeGetUniform("PulseWidth").set(Math.max(2.0F, frame.pulseWidth()));

        List<Part> near = new ArrayList<>();
        List<Part> far = new ArrayList<>();
        int budget = quality.maxPoints();
        int drawn = 0;
        List<Mesh> visible = new ArrayList<>();
        for (Mesh mesh : meshes.values()) {
            if (mesh.minX <= mesh.maxX
                    && frustum.testAab(
                            mesh.minX, mesh.minY, mesh.minZ, mesh.maxX, mesh.maxY, mesh.maxZ)) {
                visible.add(mesh);
            }
        }
        Vector3f eye = frame.eye();
        visible.sort(
                (a, b) ->
                        Float.compare(
                                distanceSquared(a, eye, frame.exaggeration()),
                                distanceSquared(b, eye, frame.exaggeration())));
        for (Mesh mesh : visible) {
            for (Part part :
                    new Part[] {
                        frame.mode().ores() ? mesh.ore : null,
                        frame.mode().caves() ? mesh.cave : null
                    }) {
                if (part == null || part.full == null) {
                    continue;
                }
                if (drawn + part.points <= budget || part.reduced == null) {
                    near.add(part);
                    drawn += part.points;
                } else {
                    far.add(part);
                    drawn += part.reducedPoints;
                }
            }
        }
        float pointScale = (float) Math.max(1.0D, scale * 0.75D);
        if (quality.glow()) {
            pass(shader, modelView, frame.projection(), near, far, pointScale * 3.0F, 0.22F, 1.0F);
        }
        pass(shader, modelView, frame.projection(), near, far, pointScale, 1.0F, 0.0F);
        drawPart(shader, modelView, frame.projection(), bodies, false, pointScale, 0.9F);
        if (highlight != null) {
            RenderSystem.defaultBlendFunc();
            drawPart(shader, modelView, frame.projection(), highlight, false, pointScale, 1.0F);
            RenderSystem.blendFunc(
                    GlStateManager.SourceFactor.SRC_ALPHA, GlStateManager.DestFactor.ONE);
        }
        drawOverlay(shader, modelView, frame, pointScale);
        VertexBuffer.unbind();

        shader.safeGetUniform("PulseRadius").set(-1.0F);
        shader.safeGetUniform("CavePulseRadius").set(-1.0F);
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableBlend();
        RenderSystem.enableCull();
        RenderSystem.depthMask(true);
        RenderSystem.enableDepthTest();
        RenderSystem.disableScissor();
        RenderSystem.viewport(0, 0, window.getWidth(), window.getHeight());
    }

    private static float distanceSquared(Mesh mesh, Vector3f eye, float exaggeration) {
        float cx = (mesh.minX + mesh.maxX) * 0.5F - eye.x;
        float cy = (mesh.minY + mesh.maxY) * 0.5F * exaggeration - eye.y;
        float cz = (mesh.minZ + mesh.maxZ) * 0.5F - eye.z;
        return cx * cx + cy * cy + cz * cz;
    }

    private static void pass(
            ShaderInstance shader,
            Matrix4f modelView,
            Matrix4f projection,
            List<Part> near,
            List<Part> far,
            float pointScale,
            float brightness,
            float softness) {
        shader.safeGetUniform("PointScale").set(pointScale);
        shader.safeGetUniform("Brightness").set(brightness);
        shader.safeGetUniform("Softness").set(softness);
        for (Part part : near) {
            draw(part.full, modelView, projection, shader);
        }
        for (Part part : far) {
            draw(part.reduced, modelView, projection, shader);
        }
    }

    private static void drawPart(
            ShaderInstance shader,
            Matrix4f modelView,
            Matrix4f projection,
            Part part,
            boolean soft,
            float pointScale,
            float brightness) {
        if (part == null || part.full == null) {
            return;
        }
        shader.safeGetUniform("PointScale").set(pointScale);
        shader.safeGetUniform("Brightness").set(brightness);
        shader.safeGetUniform("Softness").set(soft ? 1.0F : 0.0F);
        draw(part.full, modelView, projection, shader);
    }

    private static void draw(
            VertexBuffer buffer, Matrix4f modelView, Matrix4f projection, ShaderInstance shader) {
        if (buffer == null || buffer.isInvalid()) {
            return;
        }
        buffer.bind();
        buffer.drawWithShader(modelView, projection, shader);
    }

    /** Markers, outlines and fronts, rebuilt every frame from {@code frame.overlay()}. */
    private void drawOverlay(ShaderInstance shader, Matrix4f modelView, Frame frame, float scale) {
        if (frame.overlay().isEmpty()) {
            return;
        }
        BufferBuilder builder =
                Tesselator.getInstance()
                        .begin(
                                VertexFormat.Mode.QUADS,
                                DefaultVertexFormat.POSITION_COLOR_TEX_LIGHTMAP);
        for (float[] p : frame.overlay()) {
            int argb = Float.floatToRawIntBits(p[3]);
            for (float[] corner : CORNERS) {
                builder.addVertex(p[0], p[1], p[2])
                        .setColor(argb)
                        .setUv(corner[0], corner[1])
                        .setUv2(-32000, (int) p[4]);
            }
        }
        MeshData data = builder.build();
        if (data == null) {
            return;
        }
        if (overlay == null) {
            overlay = new VertexBuffer(VertexBuffer.Usage.DYNAMIC);
        }
        overlay.bind();
        overlay.upload(data);
        RenderSystem.defaultBlendFunc();
        shader.safeGetUniform("PointScale").set(scale);
        shader.safeGetUniform("Brightness").set(1.0F);
        shader.safeGetUniform("Softness").set(0.0F);
        overlay.drawWithShader(modelView, frame.projection(), shader);
    }

    /** Points currently in GPU buffers, for the status line. */
    public int points() {
        int total = 0;
        for (Mesh mesh : meshes.values()) {
            total +=
                    (mesh.ore == null ? 0 : mesh.ore.points)
                            + (mesh.cave == null ? 0 : mesh.cave.points);
        }
        return total;
    }

    /** Overlay point in origin-relative display coordinates (height not yet exaggerated). */
    public static float[] overlayPoint(
            double x,
            double y,
            double z,
            double originX,
            double originY,
            double originZ,
            int argb,
            int size) {
        return new float[] {
            (float) (x - originX),
            (float) (y - originY),
            (float) (z - originZ),
            Float.intBitsToFloat(argb),
            size
        };
    }

    @Override
    public void close() {
        meshes.values().forEach(Mesh::close);
        meshes.clear();
        if (bodies != null) {
            bodies.close();
        }
        if (highlight != null) {
            highlight.close();
        }
        if (overlay != null) {
            overlay.close();
        }
    }

    public static long bucketOf(double x, double y, double z) {
        return LidarMap.bucketKey(x, y, z);
    }
}

package dev.world.geology.lidar;

import dev.world.geology.CaveAnalysis;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.ScannerUpgrade;
import dev.world.geology.SeedHash;
import dev.world.geology.SeismicForwardModel;
import dev.world.geology.SurveyInput;
import dev.world.geology.SurveyInterpretation;
import dev.world.geology.SurveyMap;
import dev.world.geology.SurveyTarget;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns a finished survey into map samples and objects. Seismic samples are the processed record
 * itself: every pixel and depth bin whose residual stands out of the noise, on a canonical cell
 * grid so that later surveys refine the same cells. Where the survey did not resolve depth a sample
 * spans the whole column. Objects are the interpreted targets and the analyser's caves, never the
 * model's true bodies.
 *
 * <p>Object references in the returned samples are 1-based indices into the returned object list;
 * the map assigns real ids when it stores them.
 */
public final class LidarExtraction {
    /** Residual-to-noise ratio from which a cell becomes a sample. */
    public static final double THRESHOLD = 3.0D;

    /** How many response-zone radii a target's samples may reach through connected cells. */
    static final double RESPONSE_REACH = 2.0D;

    private static final long DEPTH_UNKNOWN_KEY = 0x434F4C554D4EL;

    private LidarExtraction() {}

    public static int maxAnomalies(int level) {
        return 5_000 + 2_500 * ScannerUpgrade.clamp(level);
    }

    /** Horizontal cell size of a survey: the power of two at or above its pixel, 4–128 blocks. */
    public static float cellSize(double pixelSize) {
        float cell = 4.0F;
        while (cell < pixelSize && cell < 128.0F) {
            cell *= 2.0F;
        }
        return cell;
    }

    public static List<LidarMap.Keyed> anomalies(
            SurveyInput input, SeismicForwardModel.Output output, List<SurveyTarget> targets) {
        SurveyMap map = output.map();
        int resolution = map.resolution();
        int bins = output.bins();
        float[] volume = output.volume();
        double binHeight = input.binHeight();
        float cell = cellSize(input.pixelSize());
        boolean depth = ScannerUpgrade.resolvesDepth(input.mode(), input.level());
        double columnY = (input.datumY() + input.minY()) * 0.5D;
        float columnSpread = (float) ((input.datumY() - input.minY()) * 0.5D);
        int[] owners = owners(input, output, targets, depth, cell);
        Map<Long, LidarMap.Keyed> best = new HashMap<>();
        for (int row = 0; row < resolution; row++) {
            for (int column = 0; column < resolution; column++) {
                int index = column + row * resolution;
                if (!map.inside(index)) {
                    continue;
                }
                double px = input.pixelX(column);
                double pz = input.pixelZ(row);
                int ix = (int) Math.floor(px / cell);
                int iz = (int) Math.floor(pz / cell);
                float x = (ix + 0.5F) * cell;
                float z = (iz + 0.5F) * cell;
                float spreadH = (float) Math.max(cell * 0.5D, map.lateralSigma()[index] * 0.5D);
                if (depth) {
                    for (int bin = 0; bin < bins; bin++) {
                        float ratio = volume[index * bins + bin];
                        if (Math.abs(ratio) < THRESHOLD) {
                            continue;
                        }
                        double binY = input.datumY() - (bin + 0.5D) * binHeight;
                        int iy = (int) Math.floor(binY / binHeight);
                        float y = canonicalY(input, bin);
                        long key =
                                SeedHash.combine(
                                        SeedHash.combine(
                                                Float.floatToIntBits(cell), (long) binHeight),
                                        ix,
                                        iy,
                                        iz);
                        offer(
                                best,
                                key,
                                ratio,
                                cell,
                                x,
                                y,
                                z,
                                spreadH,
                                (float) (binHeight * 0.5D),
                                0,
                                Math.max(0, owners[index * bins + bin]));
                    }
                } else {
                    float strongest = strongest(volume, index, bins);
                    if (Math.abs(strongest) < THRESHOLD) {
                        continue;
                    }
                    long key =
                            SeedHash.combine(
                                    SeedHash.combine(Float.floatToIntBits(cell), DEPTH_UNKNOWN_KEY),
                                    ix,
                                    iz);
                    offer(
                            best,
                            key,
                            strongest,
                            cell,
                            x,
                            (float) columnY,
                            z,
                            spreadH,
                            columnSpread,
                            LidarSample.FLAG_DEPTH_UNKNOWN,
                            Math.max(0, owners[index]));
                }
            }
        }
        List<LidarMap.Keyed> result = new ArrayList<>(best.values());
        result.sort(
                Comparator.comparingDouble((LidarMap.Keyed keyed) -> -keyed.intensity())
                        .thenComparingLong(LidarMap.Keyed::key));
        int limit = maxAnomalies(input.level());
        return result.size() > limit ? List.copyOf(result.subList(0, limit)) : result;
    }

    /**
     * The target each sample cell belongs to, as a 1-based index; 0 for none and -1 for cells below
     * the sample threshold. Cells are indexed {@code pixel * bins + bin}, or by pixel when depth is
     * unresolved. A cell inside a target's response zone is that target's; from there ownership
     * spreads through connected cells above the threshold, up to {@link #RESPONSE_REACH} zone
     * radii. A zone's measured response reaches past its interpreted outline by the lateral
     * resolution, so without this the fringe of every anomaly, and all of a halo around a resolved
     * lens, would stay unexplained points next to their own target.
     */
    static int[] owners(
            SurveyInput input,
            SeismicForwardModel.Output output,
            List<SurveyTarget> targets,
            boolean depth,
            float cell) {
        SurveyMap map = output.map();
        int resolution = map.resolution();
        int bins = output.bins();
        float[] volume = output.volume();
        int layers = depth ? bins : 1;
        float halfHeight =
                depth
                        ? (float) (input.binHeight() * 0.5D)
                        : (float) ((input.datumY() - input.minY()) * 0.5D);
        float columnY = (float) ((input.datumY() + input.minY()) * 0.5D);
        List<SurveyTarget> zones = new ArrayList<>(targets.size());
        for (SurveyTarget target : targets) {
            zones.add(SurveyInterpretation.responseZone(input, target));
        }
        int[] owner = new int[resolution * resolution * layers];
        Arrays.fill(owner, -1);
        int[] queue = new int[owner.length];
        int tail = 0;
        for (int index = 0; index < resolution * resolution; index++) {
            if (!map.inside(index)) {
                continue;
            }
            float x = snapped(input.pixelX(index % resolution), cell);
            float z = snapped(input.pixelZ(index / resolution), cell);
            for (int layer = 0; layer < layers; layer++) {
                float ratio = depth ? volume[index * bins + layer] : strongest(volume, index, bins);
                if (Math.abs(ratio) < THRESHOLD) {
                    continue;
                }
                int node = index * layers + layer;
                float y = depth ? canonicalY(input, layer) : columnY;
                owner[node] = targetAt(zones, x, y, z, !depth, halfHeight);
                if (owner[node] > 0) {
                    queue[tail++] = node;
                }
            }
        }
        double reach = RESPONSE_REACH * RESPONSE_REACH;
        for (int head = 0; head < tail; head++) {
            int node = queue[head];
            int claim = owner[node];
            SurveyTarget zone = zones.get(claim - 1);
            int index = node / layers;
            int layer = node % layers;
            int column = index % resolution;
            int row = index / resolution;
            for (int step = 0; step < (depth ? 6 : 4); step++) {
                int c = column + (step == 0 ? 1 : step == 1 ? -1 : 0);
                int r = row + (step == 2 ? 1 : step == 3 ? -1 : 0);
                int l = layer + (step == 4 ? 1 : step == 5 ? -1 : 0);
                if (c < 0 || r < 0 || c >= resolution || r >= resolution || l < 0 || l >= layers) {
                    continue;
                }
                int next = (c + r * resolution) * layers + l;
                if (owner[next] != 0
                        || zoneDistance(
                                        zone,
                                        snapped(input.pixelX(c), cell),
                                        snapped(input.pixelZ(r), cell))
                                > reach) {
                    continue;
                }
                owner[next] = claim;
                queue[tail++] = next;
            }
        }
        return owner;
    }

    private static float snapped(double coordinate, float cell) {
        return ((int) Math.floor(coordinate / cell) + 0.5F) * cell;
    }

    /** Height of a depth bin's canonical cell, shared by surveys of the same bin height. */
    private static float canonicalY(SurveyInput input, int bin) {
        double binHeight = input.binHeight();
        double binY = input.datumY() - (bin + 0.5D) * binHeight;
        return (float) (((int) Math.floor(binY / binHeight) + 0.5D) * binHeight);
    }

    private static float strongest(float[] volume, int index, int bins) {
        float strongest = 0.0F;
        for (int bin = 0; bin < bins; bin++) {
            float ratio = volume[index * bins + bin];
            if (Math.abs(ratio) > Math.abs(strongest)) {
                strongest = ratio;
            }
        }
        return strongest;
    }

    private static void offer(
            Map<Long, LidarMap.Keyed> best,
            long key,
            float ratio,
            float cell,
            float x,
            float y,
            float z,
            float spreadH,
            float spreadV,
            int flags,
            int object) {
        LidarMap.Keyed existing = best.get(key);
        float intensity = Math.abs(ratio);
        if (existing != null && existing.intensity() >= intensity) {
            return;
        }
        LidarSample sample =
                new LidarSample(
                        LidarSample.Kind.ANOMALY,
                        x,
                        y,
                        z,
                        spreadH,
                        spreadV,
                        (float) LidarMap.confidence(intensity),
                        0,
                        object,
                        flags | (ratio < 0.0F ? LidarSample.FLAG_NEGATIVE : 0));
        best.put(key, new LidarMap.Keyed(key, sample, intensity, cell));
    }

    /** 1-based index of the target whose estimated body holds the point, or 0. */
    static int targetAt(
            List<SurveyTarget> targets,
            double x,
            double y,
            double z,
            boolean depthUnknown,
            double cellHalfHeight) {
        int chosen = 0;
        double closest = Double.POSITIVE_INFINITY;
        for (int i = 0; i < targets.size(); i++) {
            SurveyTarget target = targets.get(i);
            double distance = zoneDistance(target, x, z);
            if (distance > 1.0D) {
                continue;
            }
            if (!depthUnknown && target.depthKnown()) {
                double half =
                        (Double.isFinite(target.thickness()) ? target.thickness() * 0.5D : 0.0D)
                                + (Double.isFinite(target.depthError())
                                        ? target.depthError()
                                        : cellHalfHeight * 2.0D)
                                + cellHalfHeight;
                if (Math.abs(y - target.y()) > half) {
                    continue;
                }
            }
            if (distance < closest) {
                closest = distance;
                chosen = i + 1;
            }
        }
        return chosen;
    }

    /** Squared horizontal distance from a target's centre in units of its radius plus error. */
    private static double zoneDistance(SurveyTarget target, double x, double z) {
        double dx = x - target.x();
        double dz = z - target.z();
        double cos = Math.cos(target.angle());
        double sin = Math.sin(target.angle());
        double u = dx * cos + dz * sin;
        double v = -dx * sin + dz * cos;
        double ru = target.radiusX() + target.positionError();
        double rv = target.radiusZ() + target.positionError();
        return (u * u) / (ru * ru) + (v * v) / (rv * rv);
    }

    public static List<LidarObject> targets(List<SurveyTarget> targets, long time) {
        List<LidarObject> result = new ArrayList<>(targets.size());
        for (SurveyTarget t : targets) {
            result.add(
                    new LidarObject(
                            0,
                            LidarObject.Kind.TARGET,
                            (float) t.x(),
                            (float) t.y(),
                            (float) t.z(),
                            (float) (t.radiusX() * 2.0D),
                            Double.isFinite(t.thickness()) ? (float) t.thickness() : Float.NaN,
                            (float) (t.radiusZ() * 2.0D),
                            (float) t.angle(),
                            (float) t.positionError(),
                            Double.isFinite(t.depthError()) ? (float) t.depthError() : Float.NaN,
                            (float) t.intensity(),
                            (float) LidarMap.confidence(t.intensity()),
                            t.detail().ordinal(),
                            t.level(),
                            -1,
                            0.0F,
                            0,
                            0,
                            0,
                            1,
                            time,
                            t.candidates()));
        }
        return result;
    }

    /** Colour of a cave wall by what fills the void. */
    public static int caveColor(byte fill) {
        return switch (fill) {
            case CaveAnalysis.WATER -> 0x3A6BFF;
            case CaveAnalysis.LAVA -> 0xFF6A1F;
            default -> 0x5FD8FF;
        };
    }

    public static int caveFlags(byte fill) {
        return switch (fill) {
            case CaveAnalysis.WATER -> LidarSample.FLAG_WATER;
            case CaveAnalysis.LAVA -> LidarSample.FLAG_LAVA;
            default -> 0;
        };
    }

    /** The analyser's wall points; object references are 1-based cave indices. */
    public static List<LidarSample> caveWalls(CaveAnalysis.Result result, int level) {
        float spread = (float) (CaveAnalyzer.jitter(level) * 0.5D + 0.25D);
        List<LidarSample> samples = new ArrayList<>(result.walls().size());
        for (CaveAnalysis.Wall wall : result.walls()) {
            samples.add(
                    new LidarSample(
                            LidarSample.Kind.CAVE,
                            (float) wall.x(),
                            (float) wall.y(),
                            (float) wall.z(),
                            spread,
                            spread,
                            wall.confidence(),
                            caveColor(wall.fill()),
                            wall.cave() + 1,
                            caveFlags(wall.fill())));
        }
        return samples;
    }

    /**
     * Points along each connection: confirmed ones solid, suspected ones dashed and paler.
     *
     * @param confirmed per link whether resampling found the passage open
     */
    public static List<LidarSample> links(
            CaveAnalysis.Result result, boolean[] confirmed, int level) {
        List<LidarSample> samples = new ArrayList<>();
        float spread = (float) (CaveAnalyzer.jitter(level) * 0.4D + 0.2D);
        for (int i = 0; i < result.links().size(); i++) {
            CaveAnalysis.Link link = result.links().get(i);
            boolean open = confirmed[i];
            double length =
                    Math.sqrt(
                            square(link.bx() - link.ax())
                                    + square(link.by() - link.ay())
                                    + square(link.bz() - link.az()));
            int steps = Math.max(2, (int) Math.ceil(length));
            for (int s = 0; s <= steps; s++) {
                if (!open && s % 2 == 1) {
                    continue;
                }
                double t = s / (double) steps;
                samples.add(
                        new LidarSample(
                                open
                                        ? LidarSample.Kind.LINK_CONFIRMED
                                        : LidarSample.Kind.LINK_SUSPECTED,
                                (float) (link.ax() + (link.bx() - link.ax()) * t),
                                (float) (link.ay() + (link.by() - link.ay()) * t),
                                (float) (link.az() + (link.bz() - link.az()) * t),
                                spread,
                                spread,
                                open ? 0.9F : 0.45F,
                                open ? 0xE8FFF4 : 0xC77DFF,
                                0,
                                0));
            }
        }
        return samples;
    }

    public static List<LidarObject> caves(
            CaveAnalysis.Result result, boolean[] confirmed, int level, double spacing, long time) {
        int[] links = new int[result.caves().size()];
        int[] suspected = new int[result.caves().size()];
        for (int i = 0; i < result.links().size(); i++) {
            CaveAnalysis.Link link = result.links().get(i);
            int[] counter = confirmed[i] ? links : suspected;
            counter[link.caveA()]++;
            counter[link.caveB()]++;
        }
        float error = (float) (spacing * 0.5D + CaveAnalyzer.jitter(level));
        List<LidarObject> objects = new ArrayList<>(result.caves().size());
        for (int i = 0; i < result.caves().size(); i++) {
            CaveAnalysis.Cave cave = result.caves().get(i);
            objects.add(
                    new LidarObject(
                            0,
                            LidarObject.Kind.CAVE,
                            (float) cave.x(),
                            (float) cave.y(),
                            (float) cave.z(),
                            (float) (cave.maxX() - cave.minX()),
                            (float) (cave.maxY() - cave.minY()),
                            (float) (cave.maxZ() - cave.minZ()),
                            0.0F,
                            error,
                            error,
                            0.0F,
                            (float) cave.confidence(),
                            -1,
                            level,
                            cave.shape().ordinal(),
                            (float) cave.volume(),
                            cave.fill(),
                            links[i],
                            suspected[i],
                            1,
                            time,
                            List.of()));
        }
        return objects;
    }

    private static double square(double value) {
        return value * value;
    }
}

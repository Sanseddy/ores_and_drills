package dev.world.geology.exploration;

import dev.world.geology.CaveAnalysis;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.SeedHash;
import dev.world.geology.SurveyTarget;
import dev.world.geology.lidar.LidarCoverage;
import dev.world.geology.lidar.LidarExtraction;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarMapStorage;
import dev.world.geology.lidar.LidarObject;
import dev.world.geology.lidar.LidarSample;
import dev.world.geology.survey.CaveSnapshot;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * The exploration drill's 3D log, built block by block while it drills: the core column coloured by
 * what was logged, ore sensed beside the hole, and — with a cave analyser — the walls of voids in a
 * ring around the hole. Rings read only loaded chunks, at the analyser's step and with its
 * positioning noise. The log is the drill's own map and is replaced by the next borehole.
 */
final class BoreholeLidar {
    static final int MAXIMUM_SAMPLES = 16_000;
    private static final long JITTER_SALT = 0x52494E47L;

    /** A void gap longer than this ends one void cluster and starts the next. */
    private static final int CLUSTER_GAP = 3;

    private LidarMap map = new LidarMap(MAXIMUM_SAMPLES, 64);
    private Runnable onChange = () -> {};
    private int oreObject;
    private int voidObject;
    private int voidLastY = Integer.MIN_VALUE;
    private float voidMinX;
    private float voidMinY;
    private float voidMinZ;
    private float voidMaxX;
    private float voidMaxY;
    private float voidMaxZ;
    private int voidPoints;

    LidarMap map() {
        return map;
    }

    void onChange(Runnable listener) {
        onChange = listener;
        map.onChange(listener);
    }

    /** Restores a saved log; an unreadable one is dropped, it only repeats the core log. */
    void load(net.minecraft.nbt.CompoundTag tag) {
        try {
            map = LidarMapStorage.load(tag, MAXIMUM_SAMPLES, 64);
        } catch (RuntimeException invalid) {
            map = new LidarMap(MAXIMUM_SAMPLES, 64);
        }
        map.onChange(onChange);
        for (dev.world.geology.lidar.LidarObject object : map.objects()) {
            if (object.kind() == LidarObject.Kind.CORE_ORE) {
                oreObject = object.id();
            }
        }
    }

    void start() {
        map.clear();
        oreObject = 0;
        voidObject = 0;
        voidLastY = Integer.MIN_VALUE;
        voidPoints = 0;
    }

    /** Logs one cored block and, every analyser step, the ring of voids around it. */
    void log(
            ServerLevel level,
            BlockPos drill,
            BlockPos sample,
            BlockState core,
            BoreholeLog.Kind kind,
            ResourceLocation id,
            int analyzer,
            long time) {
        List<LidarSample> samples = new ArrayList<>();
        if (kind != BoreholeLog.Kind.OPEN) {
            boolean ore = kind == BoreholeLog.Kind.ORE;
            int color = color(level, sample, core, kind, id);
            samples.add(
                    new LidarSample(
                            LidarSample.Kind.CORE,
                            sample.getX() + 0.5F,
                            sample.getY() + 0.5F,
                            sample.getZ() + 0.5F,
                            0.3F,
                            0.45F,
                            ore ? 1.0F : 0.45F,
                            color,
                            ore ? oreObject : 0,
                            kind == BoreholeLog.Kind.FLUID ? LidarSample.FLAG_WATER : 0));
        }
        int step = CaveAnalyzer.drillStep(analyzer);
        if (CaveAnalyzer.installed(analyzer) && Math.floorMod(sample.getY(), step) == 0) {
            ring(level, sample, analyzer, samples);
        }
        if (!samples.isEmpty()) {
            map.add(samples, time);
        }
        if (Math.floorMod(sample.getY(), 8) == 0) {
            cover(drill, sample.getY(), analyzer, time);
        }
    }

    void sensed(BlockPos sample, long time) {
        map.add(
                List.of(
                        new LidarSample(
                                LidarSample.Kind.SENSED,
                                sample.getX() + 0.5F,
                                sample.getY() + 0.5F,
                                sample.getZ() + 0.5F,
                                ExplorationDrillBlockEntity.LOGGING_RADIUS * 0.8F,
                                0.6F,
                                0.4F,
                                0xFFB347,
                                0,
                                0)),
                time);
    }

    /** Creates or extends the object of the ore the hole intersects. */
    void ore(
            BlockPos drill,
            ResourceLocation ore,
            int topY,
            int bottomY,
            List<SurveyTarget.Candidate> candidates,
            long time) {
        float height = topY - bottomY + 1.0F;
        LidarObject object =
                new LidarObject(
                        oreObject,
                        LidarObject.Kind.CORE_ORE,
                        drill.getX() + 0.5F,
                        (topY + bottomY + 1) * 0.5F,
                        drill.getZ() + 0.5F,
                        1.0F,
                        height,
                        1.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        0.0F,
                        1.0F,
                        -1,
                        0,
                        -1,
                        0.0F,
                        0,
                        0,
                        0,
                        1,
                        time,
                        candidates);
        oreObject = map.putObject(object, 0L);
    }

    void finish(BlockPos drill, int bottomY, int analyzer, long time) {
        closeVoid(time);
        cover(drill, bottomY, analyzer, time);
    }

    private void cover(BlockPos drill, int bottomY, int analyzer, long time) {
        float radius =
                Math.max(
                        ExplorationDrillBlockEntity.LOGGING_RADIUS,
                        CaveAnalyzer.drillRadius(analyzer));
        map.addCoverage(
                new LidarCoverage(
                        drill.getX() + 0.5F,
                        drill.getZ() + 0.5F,
                        radius,
                        drill.getY(),
                        bottomY,
                        CaveAnalyzer.installed(analyzer),
                        analyzer,
                        time));
    }

    private void ring(ServerLevel level, BlockPos centre, int analyzer, List<LidarSample> out) {
        int radius = CaveAnalyzer.drillRadius(analyzer);
        double jitter = CaveAnalyzer.jitter(analyzer) * 0.5D;
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        int found = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius || Math.abs(dx) + Math.abs(dz) <= 1) {
                    continue;
                }
                pos.set(centre.getX() + dx, centre.getY(), centre.getZ() + dz);
                if (!level.isLoaded(pos)
                        || pos.getY()
                                >= level.getHeight(
                                        Heightmap.Types.OCEAN_FLOOR, pos.getX(), pos.getZ())) {
                    continue;
                }
                byte fill = CaveSnapshot.code(level.getBlockState(pos));
                if (!CaveAnalysis.open(fill) || !touchesRock(level, pos)) {
                    continue;
                }
                long seed = SeedHash.combine(level.getSeed() ^ JITTER_SALT, pos.asLong());
                float x = (float) (pos.getX() + 0.5D + SeedHash.gaussian(seed, 1) * jitter);
                float y = (float) (pos.getY() + 0.5D + SeedHash.gaussian(seed, 2) * jitter);
                float z = (float) (pos.getZ() + 0.5D + SeedHash.gaussian(seed, 3) * jitter);
                out.add(
                        new LidarSample(
                                LidarSample.Kind.CAVE,
                                x,
                                y,
                                z,
                                (float) jitter + 0.2F,
                                (float) jitter + 0.2F,
                                0.85F,
                                LidarExtraction.caveColor(fill),
                                0,
                                LidarExtraction.caveFlags(fill)));
                extendVoid(x, y, z);
                found++;
            }
        }
        if (found == 0
                && voidLastY != Integer.MIN_VALUE
                && voidLastY - centre.getY() > CLUSTER_GAP) {
            closeVoid(0L);
        } else if (found > 0) {
            voidLastY = centre.getY();
        }
    }

    private static boolean touchesRock(ServerLevel level, BlockPos pos) {
        for (BlockPos near :
                new BlockPos[] {
                    pos.north(), pos.south(), pos.east(), pos.west(), pos.above(), pos.below()
                }) {
            if (level.isLoaded(near)
                    && CaveSnapshot.code(level.getBlockState(near)) == CaveAnalysis.SOLID) {
                return true;
            }
        }
        return false;
    }

    private void extendVoid(float x, float y, float z) {
        if (voidPoints == 0) {
            voidMinX = voidMaxX = x;
            voidMinY = voidMaxY = y;
            voidMinZ = voidMaxZ = z;
        }
        voidMinX = Math.min(voidMinX, x);
        voidMinY = Math.min(voidMinY, y);
        voidMinZ = Math.min(voidMinZ, z);
        voidMaxX = Math.max(voidMaxX, x);
        voidMaxY = Math.max(voidMaxY, y);
        voidMaxZ = Math.max(voidMaxZ, z);
        voidPoints++;
    }

    /** Ends the current void cluster as an object, if it holds enough wall points. */
    private void closeVoid(long time) {
        if (voidPoints >= 4) {
            voidObject =
                    map.putObject(
                            new LidarObject(
                                    0,
                                    LidarObject.Kind.CORE_VOID,
                                    (voidMinX + voidMaxX) * 0.5F,
                                    (voidMinY + voidMaxY) * 0.5F,
                                    (voidMinZ + voidMaxZ) * 0.5F,
                                    voidMaxX - voidMinX + 1.0F,
                                    voidMaxY - voidMinY + 1.0F,
                                    voidMaxZ - voidMinZ + 1.0F,
                                    0.0F,
                                    1.0F,
                                    1.0F,
                                    0.0F,
                                    0.9F,
                                    -1,
                                    0,
                                    -1,
                                    0.0F,
                                    CaveAnalysis.AIR,
                                    0,
                                    0,
                                    1,
                                    time,
                                    List.of()),
                            0L);
        }
        voidPoints = 0;
        voidLastY = Integer.MIN_VALUE;
    }

    private static int color(
            ServerLevel level,
            BlockPos pos,
            BlockState core,
            BoreholeLog.Kind kind,
            ResourceLocation id) {
        BlockState shown = core;
        if (kind == BoreholeLog.Kind.ORE) {
            shown = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
        }
        int color = shown.getMapColor(level, pos).col;
        return color == 0 ? 0x808080 : color;
    }
}

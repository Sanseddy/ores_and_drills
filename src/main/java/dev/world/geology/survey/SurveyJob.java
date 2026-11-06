package dev.world.geology.survey;

import dev.OresAndDrillsMod;
import dev.config.SeismicSurveyConfig;
import dev.world.geology.CaveAnalysis;
import dev.world.geology.CaveAnalyzer;
import dev.world.geology.CavityGrid;
import dev.world.geology.CavityProbe;
import dev.world.geology.DepositBodies;
import dev.world.geology.NaturalGeology;
import dev.world.geology.RockPhysics;
import dev.world.geology.RockProperties;
import dev.world.geology.SeismicForwardModel;
import dev.world.geology.SubsurfaceBody;
import dev.world.geology.SurveyInput;
import dev.world.geology.SurveyInterpretation;
import dev.world.geology.SurveyMap;
import dev.world.geology.SurveyMode;
import dev.world.geology.SurveyTarget;
import dev.world.geology.lidar.LidarExtraction;
import dev.world.geology.lidar.LidarMap;
import dev.world.geology.lidar.LidarObject;
import dev.world.geology.lidar.LidarSample;
import dev.world.geology.ore.OreSignature;
import dev.world.geology.ore.OreSignatureTable;
import dev.world.level.levelgen.ConfirmedDeposit;
import dev.world.level.levelgen.ConfirmedDepositIndex;
import dev.world.level.levelgen.DepositCandidate;
import dev.world.level.levelgen.DepositTierLayout;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositTier;
import dev.world.level.levelgen.SeismicDepositForecast;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import net.minecraft.Util;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * One survey in progress. World state — the confirmed-deposit index, loaded chunk data, saved chunk
 * status, block tags — is read on the server thread in small slices; deposit forecasting and the
 * model itself run on worker threads over immutable snapshots. No step loads or generates a chunk.
 *
 * <p>Deposits come from two sources. Where terrain has generated, the {@link ConfirmedDepositIndex}
 * is authoritative, and loaded chunk data gives the remaining ore. Elsewhere the deterministic
 * forecast predicts what generation will place; a prediction whose source chunk already generated
 * without confirming it is discarded, exactly as {@code /locate} does.
 */
public final class SurveyJob {
    private static final int MAX_IN_FLIGHT = 2;
    private static final int CONFIRMED_PER_SLICE = 4;
    private static final int MAX_BODIES = 4096;
    private static final int MAX_SMALL_BODIES = MAX_BODIES / 2;

    /** Furthest a deposit's centre can sit outside the survey and still reach into it. */
    private static final int DEPOSIT_REACH = 96;

    enum Stage {
        CONFIRMED,
        FORECAST,
        CAVES,
        MODEL,
        LINKS,
        DONE,
        FAILED
    }

    /**
     * What the LiDAR view gets from the survey: seismic cells above the noise, the analyser's cave
     * walls and connections, and the caves as objects. Object references in samples are 1-based
     * indices into the target list and the cave list.
     */
    public record Lidar(
            List<LidarMap.Keyed> anomalies,
            float cell,
            List<LidarSample> caveSamples,
            List<LidarObject> caves,
            double caveRadius) {}

    public record Result(SurveyMap map, List<SurveyTarget> targets, int minY, Lidar lidar) {}

    private record Modelled(
            SurveyMap map,
            List<SurveyTarget> targets,
            List<LidarMap.Keyed> anomalies,
            float cell,
            CaveAnalysis.Result caves) {}

    private record Cell(OreDepositTier tier, int cellX, int cellZ) {}

    private record PendingCandidate(
            DepositCandidate candidate, CompletableFuture<Boolean> decorated) {}

    private final SurveyRequest request;
    private final ServerLevel level;
    private final ForecastCache cache;
    private final double datumY;
    private final int minY;
    private final NaturalGeology.Setting setting;
    private final long startedTick;
    private volatile boolean cancelled;
    private Stage stage = Stage.CONFIRMED;

    private List<ConfirmedDeposit> confirmed;
    private int confirmedCursor;
    private final Set<Long> knownDeposits = new HashSet<>();
    private final List<SubsurfaceBody> bodies = new ArrayList<>();
    private final Map<Long, SurveyInterpretation.DepositOre> depositOres = new HashMap<>();

    private List<Cell> cells;
    private int cellCursor;
    private List<ResourceLocation> dataDrivenOres = List.of();
    private final ArrayDeque<CompletableFuture<List<DepositCandidate>>> inFlight =
            new ArrayDeque<>();
    private final List<PendingCandidate> awaitingStatus = new ArrayList<>();
    private final Map<Long, CompletableFuture<Boolean>> decoratedChunks = new HashMap<>();
    private int forecastDeposits;

    private CaveSnapshot caveSnapshot;
    private CaveLinkCheck linkCheck;
    private Modelled modelled;
    private CompletableFuture<Modelled> model;
    private Result result;
    private Throwable failure;

    SurveyJob(ServerLevel level, SurveyRequest request, ForecastCache cache) {
        this.level = level;
        this.request = request;
        this.cache = cache;
        this.datumY = request.station().getY();
        this.minY = level.getMinBuildHeight() + 5;
        this.setting = NaturalGeology.Setting.forDimension(request.dimension().toString());
        this.startedTick = level.getGameTime();
    }

    public SurveyRequest request() {
        return request;
    }

    ServerLevel level() {
        return level;
    }

    public boolean isDone() {
        return stage == Stage.DONE || stage == Stage.FAILED;
    }

    public boolean failed() {
        return stage == Stage.FAILED;
    }

    public Result result() {
        return result;
    }

    public Throwable failure() {
        return failure;
    }

    public void cancel() {
        cancelled = true;
        if (model != null) {
            model.cancel(false);
        }
        if (!isDone()) {
            fail(new IllegalStateException("Survey cancelled"));
        }
    }

    boolean cancelled() {
        return cancelled;
    }

    long startedTick() {
        return startedTick;
    }

    /** Share of the computation done, 0–1. */
    public double progress() {
        return switch (stage) {
            case CONFIRMED ->
                    confirmed == null || confirmed.isEmpty()
                            ? 0.0D
                            : 0.1D * confirmedCursor / confirmed.size();
            case FORECAST ->
                    cells == null || cells.isEmpty()
                            ? 0.1D
                            : 0.1D + 0.4D * Math.min(1.0D, cellCursor / (double) cells.size());
            case CAVES -> 0.5D + 0.1D * (caveSnapshot == null ? 1.0D : caveSnapshot.progress());
            case MODEL -> 0.7D;
            case LINKS -> 0.85D + 0.15D * (linkCheck == null ? 1.0D : linkCheck.progress());
            case DONE, FAILED -> 1.0D;
        };
    }

    /** Works until {@code deadline}; returns true once finished. */
    boolean advance(long deadline) {
        try {
            while (!isDone() && System.nanoTime() < deadline) {
                if (cancelled) {
                    fail(new IllegalStateException("Survey cancelled"));
                    break;
                }
                boolean progressed =
                        switch (stage) {
                            case CONFIRMED -> advanceConfirmed();
                            case FORECAST -> advanceForecast();
                            case CAVES -> advanceCaves(deadline);
                            case MODEL -> advanceModel();
                            case LINKS -> advanceLinks(deadline);
                            case DONE, FAILED -> false;
                        };
                if (!progressed) {
                    break;
                }
            }
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.warn("Seismic survey failed", exception);
            fail(exception);
        }
        return isDone();
    }

    private boolean advanceConfirmed() {
        if (confirmed == null) {
            boolean detailed = request.mode() == SurveyMode.DETAILED;
            List<ConfirmedDeposit> records = new ArrayList<>();
            for (ConfirmedDeposit deposit :
                    ConfirmedDepositIndex.get(level)
                            .recordsWithin(
                                    request.station(), request.radius() + DEPOSIT_REACH, null)) {
                // TINY/SMALL are as common as ordinary veins; a regional map would drown in them.
                if (detailed || deposit.tier().index() >= OreDepositTier.MEDIUM.index()) {
                    records.add(deposit);
                }
            }
            // The body budget is filled in this order: industrial deposits first, then the nearest
            // ones, so thousands of small veins can neither crowd out a large deposit nor the
            // ones under the station. The id only breaks ties, keeping the order deterministic.
            BlockPos station = request.station();
            records.sort(
                    Comparator.comparingInt((ConfirmedDeposit deposit) -> -deposit.tier().index())
                            .thenComparingLong(
                                    deposit -> horizontalDistanceSqr(station, deposit.center()))
                            .thenComparingLong(ConfirmedDeposit::depositId));
            confirmed = records;
            return true;
        }
        int end = Math.min(confirmed.size(), confirmedCursor + CONFIRMED_PER_SLICE);
        for (; confirmedCursor < end; confirmedCursor++) {
            ConfirmedDeposit deposit = confirmed.get(confirmedCursor);
            knownDeposits.add(deposit.depositId());
            addConfirmed(deposit);
        }
        if (confirmedCursor >= confirmed.size()) {
            stage = Stage.FORECAST;
        }
        return true;
    }

    private static long horizontalDistanceSqr(BlockPos a, BlockPos b) {
        long dx = (long) a.getX() - b.getX();
        long dz = (long) a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    private void addConfirmed(ConfirmedDeposit deposit) {
        // Small veins leave room for the forecast deposits of unexplored terrain that follow.
        int limit =
                deposit.tier().index() >= OreDepositTier.MEDIUM.index()
                        ? MAX_BODIES
                        : MAX_SMALL_BODIES;
        if (bodies.size() < limit) {
            bodies.addAll(confirmedBodies(level, deposit, setting));
            rememberOre(deposit.depositId(), deposit.oreId());
        }
    }

    private void rememberOre(long key, ResourceLocation oreId) {
        depositOres.put(
                key,
                new SurveyInterpretation.DepositOre(oreId.toString(), RockPhysics.forOre(oreId)));
    }

    /**
     * Model bodies of a generated deposit. Its shape and remaining ore come from loaded chunk data
     * where available; elsewhere the record's own block count stands in for the shape and the
     * reserve is taken as untouched. Reads only chunks that are already loaded.
     */
    public static List<SubsurfaceBody> confirmedBodies(
            ServerLevel level, ConfirmedDeposit deposit, NaturalGeology.Setting setting) {
        int tier = deposit.tier().index();
        double remaining = 1.0D;
        double radius = defaultRadius(tier, deposit.placedBlocks());
        double thickness;
        if (tier >= OreDepositTier.MEDIUM.index()) {
            OreDepositData.DepositMapSnapshot snapshot = OreDepositData.mapSnapshot(level, deposit);
            if (snapshot.initialOre() > 0) {
                remaining = snapshot.remainingOre() / (double) snapshot.initialOre();
                int columns = Math.max(1, snapshot.cells().size());
                radius = Math.max(2.0D, Math.sqrt(columns / Math.PI));
                thickness =
                        Math.max(1.0D, Math.min(12.0D, deposit.placedBlocks() / (double) columns));
            } else {
                thickness = thicknessFor(deposit.placedBlocks(), radius, radius, 8.0D);
            }
        } else {
            thickness = 2.0D;
        }
        double y = deposit.center().getY() + 0.5D;
        return DepositBodies.bodies(
                deposit.depositId(),
                tier,
                deposit.center().getX() + 0.5D,
                y,
                deposit.center().getZ() + 0.5D,
                radius,
                radius,
                thickness,
                RockPhysics.forOre(deposit.oreId()),
                NaturalGeology.host(setting, y),
                remaining);
    }

    private static double defaultRadius(int tier, int placedBlocks) {
        return switch (tier) {
            case 0, 1 -> Math.max(1.0D, Math.sqrt(placedBlocks / (Math.PI * 2.0D)));
            case 2 -> 12.5D;
            default -> 25.0D;
        };
    }

    private static double thicknessFor(int blocks, double radiusX, double radiusZ, double maximum) {
        return Math.max(1.0D, Math.min(maximum, blocks / (Math.PI * radiusX * radiusZ)));
    }

    private void addDeposit(
            long key,
            int tier,
            double x,
            double y,
            double z,
            double radiusX,
            double radiusZ,
            double thickness,
            ResourceLocation oreId,
            double remaining) {
        if (bodies.size() >= MAX_BODIES) {
            return;
        }
        RockProperties ore = RockPhysics.forOre(oreId);
        RockProperties host = NaturalGeology.host(setting, y);
        rememberOre(key, oreId);
        bodies.addAll(
                DepositBodies.bodies(
                        key, tier, x, y, z, radiusX, radiusZ, thickness, ore, host, remaining));
    }

    private boolean advanceForecast() {
        if (cells == null) {
            dataDrivenOres = SeismicDepositForecast.dataDrivenOres(level);
            cells = cellsToForecast();
            return true;
        }
        boolean progressed = false;
        Iterator<CompletableFuture<List<DepositCandidate>>> running = inFlight.iterator();
        while (running.hasNext()) {
            CompletableFuture<List<DepositCandidate>> future = running.next();
            if (!future.isDone()) {
                continue;
            }
            running.remove();
            progressed = true;
            for (DepositCandidate candidate : future.join()) {
                considerCandidate(candidate);
            }
        }
        while (inFlight.size() < MAX_IN_FLIGHT && cellCursor < cells.size()) {
            Cell cell = cells.get(cellCursor++);
            boolean exact = request.mode() == SurveyMode.DETAILED;
            inFlight.add(
                    cache.get(
                            level,
                            new ForecastCache.Key(cell.tier(), cell.cellX(), cell.cellZ(), exact),
                            // Other surveys may share this forecast, so it always completes even
                            // if this survey is cancelled meanwhile.
                            () ->
                                    SeismicDepositForecast.predict(
                                            level,
                                            cell.tier(),
                                            cell.cellX(),
                                            cell.cellZ(),
                                            dataDrivenOres,
                                            exact)));
            progressed = true;
        }
        Iterator<PendingCandidate> waiting = awaitingStatus.iterator();
        while (waiting.hasNext()) {
            PendingCandidate pending = waiting.next();
            if (!pending.decorated().isDone()) {
                continue;
            }
            waiting.remove();
            progressed = true;
            if (!pending.decorated().join()) {
                DepositCandidate c = pending.candidate();
                addDeposit(
                        c.depositId(),
                        c.tier().index(),
                        c.center().getX() + 0.5D,
                        c.center().getY() + 0.5D,
                        c.center().getZ() + 0.5D,
                        c.radiusX(),
                        c.radiusZ(),
                        thicknessFor(
                                c.expectedBlocks(),
                                c.radiusX(),
                                c.radiusZ(),
                                Math.max(2.0D, c.verticalRadius() * 2.0D)),
                        c.oreId(),
                        1.0D);
                forecastDeposits++;
            }
        }
        if (cellCursor >= cells.size() && inFlight.isEmpty() && awaitingStatus.isEmpty()) {
            caveSnapshot = CaveSnapshot.create(level, request, minY);
            if (caveSnapshot == null) {
                startModel();
            } else {
                stage = Stage.CAVES;
            }
            return true;
        }
        return progressed;
    }

    private List<Cell> cellsToForecast() {
        List<Cell> result = new ArrayList<>();
        int sx = request.station().getX();
        int sz = request.station().getZ();
        long reach = request.radius() + DEPOSIT_REACH;
        for (OreDepositTier tier : SeismicDepositForecast.TIERS) {
            DepositTierLayout layout = SeismicDepositForecast.layout(tier);
            int size = layout.cellSizeBlocks();
            int minCellX = Math.floorDiv((int) (sx - reach), size);
            int maxCellX = Math.floorDiv((int) (sx + reach), size);
            int minCellZ = Math.floorDiv((int) (sz - reach), size);
            int maxCellZ = Math.floorDiv((int) (sz + reach), size);
            for (int cellX = minCellX; cellX <= maxCellX; cellX++) {
                for (int cellZ = minCellZ; cellZ <= maxCellZ; cellZ++) {
                    // Nearest point of the cell to the station decides whether it can matter.
                    long nx =
                            Math.max((long) cellX * size, Math.min(sx, (long) cellX * size + size));
                    long nz =
                            Math.max((long) cellZ * size, Math.min(sz, (long) cellZ * size + size));
                    long dx = nx - sx;
                    long dz = nz - sz;
                    if (dx * dx + dz * dz <= reach * reach) {
                        result.add(new Cell(tier, cellX, cellZ));
                    }
                }
            }
        }
        result.sort(
                Comparator.comparingLong(
                                (Cell cell) -> {
                                    int size =
                                            SeismicDepositForecast.layout(cell.tier())
                                                    .cellSizeBlocks();
                                    long dx = (long) cell.cellX() * size + size / 2 - sx;
                                    long dz = (long) cell.cellZ() * size + size / 2 - sz;
                                    return dx * dx + dz * dz;
                                })
                        .thenComparing(cell -> cell.tier().ordinal())
                        .thenComparingInt(Cell::cellX)
                        .thenComparingInt(Cell::cellZ));
        return result;
    }

    private void considerCandidate(DepositCandidate candidate) {
        if (!knownDeposits.add(candidate.depositId())) {
            return;
        }
        double dx = candidate.center().getX() - request.station().getX();
        double dz = candidate.center().getZ() - request.station().getZ();
        double reach = request.radius() + DEPOSIT_REACH;
        if (dx * dx + dz * dz > reach * reach) {
            return;
        }
        // A live source chunk has generated (or is generating): its deposit is either in the
        // confirmed index already or was never placed.
        if (level.getChunkSource().getChunkNow(candidate.sourceChunkX(), candidate.sourceChunkZ())
                != null) {
            return;
        }
        long chunk = ChunkPos.asLong(candidate.sourceChunkX(), candidate.sourceChunkZ());
        CompletableFuture<Boolean> decorated =
                decoratedChunks.computeIfAbsent(
                        chunk,
                        ignored ->
                                inspectDecorated(
                                        candidate.sourceChunkX(), candidate.sourceChunkZ()));
        awaitingStatus.add(new PendingCandidate(candidate, decorated));
    }

    /**
     * Reads only the saved {@code Status} field of an unloaded chunk through the asynchronous chunk
     * scanner; the chunk itself is neither loaded nor generated.
     */
    private CompletableFuture<Boolean> inspectDecorated(int chunkX, int chunkZ) {
        CollectFields visitor = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
        return level.getChunkSource()
                .chunkScanner()
                .scanChunk(new ChunkPos(chunkX, chunkZ), visitor)
                .handle(
                        (ignored, error) -> {
                            if (error != null
                                    || !(visitor.getResult() instanceof CompoundTag root)) {
                                return false;
                            }
                            ChunkStatus status = ChunkStatus.byName(root.getString("Status"));
                            return status != null && status.isOrAfter(ChunkStatus.FEATURES);
                        });
    }

    private boolean advanceCaves(long deadline) {
        if (caveSnapshot.advance(deadline)) {
            startModel();
            return true;
        }
        return false;
    }

    private void startModel() {
        stage = Stage.MODEL;
        CaveSnapshot snapshot = caveSnapshot;
        CaveAnalysis.Terrain terrain = TerrainCavityProbe.caveTerrain(level);
        int analyzer = CaveAnalyzer.clamp(request.analyzer());
        long caveSeed = request.caveSeed();
        List<SubsurfaceBody> depositBodies = new ArrayList<>(bodies);
        depositBodies.sort(BODY_ORDER);
        CavityProbe probe = TerrainCavityProbe.of(level);
        long worldSeed = request.worldSeed();
        int sampleBudget = SeismicSurveyConfig.CAVITY_SAMPLE_BUDGET.get();
        SurveyMode mode = request.mode();
        double radius = request.radius();
        double cx = request.station().getX() + 0.5D;
        double cz = request.station().getZ() + 0.5D;
        double[] gx = new double[request.geophones().size()];
        double[] gz = new double[gx.length];
        for (int i = 0; i < gx.length; i++) {
            gx[i] = request.geophones().get(i).getX() + 0.5D;
            gz[i] = request.geophones().get(i).getZ() + 0.5D;
        }
        int bins = SurveyInput.bins(mode, datumY, minY);
        double binHeight = SurveyInput.binHeight(mode);
        int level = request.level();
        double pixel = radius * 2.0D / dev.world.geology.ScannerUpgrade.resolution(mode, level);
        List<SubsurfaceBody> natural =
                NaturalGeology.bodies(
                        worldSeed,
                        setting,
                        cx - radius,
                        cz - radius,
                        cx + radius,
                        cz + radius,
                        minY,
                        datumY - 16.0D);
        List<OreSignature> table = OreSignatureTable.forAnalysis(request.dimension());
        Map<Long, SurveyInterpretation.DepositOre> ores = Map.copyOf(depositOres);
        model =
                CompletableFuture.supplyAsync(
                        () -> {
                            List<SubsurfaceBody> all = new ArrayList<>(depositBodies);
                            all.addAll(natural);
                            CavityGrid grid =
                                    CavityGrid.sample(
                                            probe,
                                            worldSeed,
                                            cx - radius,
                                            cz - radius,
                                            cx + radius,
                                            cz + radius,
                                            Math.max(16.0D, pixel * 0.5D),
                                            datumY,
                                            binHeight,
                                            bins,
                                            sampleBudget,
                                            () -> cancelled);
                            if (grid == null) {
                                return null;
                            }
                            SurveyInput input =
                                    new SurveyInput(
                                            mode,
                                            cx,
                                            datumY,
                                            cz,
                                            radius,
                                            minY,
                                            setting,
                                            gx,
                                            gz,
                                            request.environment(),
                                            request.wellTie(),
                                            all,
                                            NaturalGeology.horizons(setting, minY - 5),
                                            grid,
                                            request.noiseSeed(),
                                            level);
                            SeismicForwardModel.Output output =
                                    SeismicForwardModel.run(input, () -> cancelled);
                            if (output == null) {
                                return null;
                            }
                            List<SurveyTarget> targets =
                                    SurveyInterpretation.interpret(input, output, ores, table);
                            CaveAnalysis.Result caves = null;
                            if (snapshot != null) {
                                CaveAnalysis.resolveUnknown(
                                        snapshot.grid(), terrain, () -> cancelled);
                                caves =
                                        CaveAnalysis.analyse(
                                                snapshot.grid(),
                                                CaveAnalysis.Settings.of(analyzer),
                                                caveSeed,
                                                () -> cancelled);
                                if (caves == null) {
                                    return null;
                                }
                            }
                            return new Modelled(
                                    output.map(),
                                    targets,
                                    LidarExtraction.anomalies(input, output, targets),
                                    LidarExtraction.cellSize(input.pixelSize()),
                                    caves);
                        },
                        Util.backgroundExecutor());
    }

    private boolean advanceModel() {
        if (!model.isDone()) {
            return false;
        }
        try {
            modelled = model.join();
        } catch (RuntimeException exception) {
            fail(exception);
            return true;
        }
        if (modelled == null) {
            fail(new IllegalStateException("Survey cancelled"));
            return true;
        }
        if (modelled.caves() == null) {
            complete(null);
            return true;
        }
        linkCheck =
                new CaveLinkCheck(
                        level,
                        TerrainCavityProbe.caveTerrain(level),
                        modelled.caves().links(),
                        request.analyzer(),
                        caveSnapshot.grid().spacing());
        stage = Stage.LINKS;
        return true;
    }

    private boolean advanceLinks(long deadline) {
        if (!linkCheck.advance(deadline)) {
            return false;
        }
        complete(linkCheck.confirmed());
        return true;
    }

    private void complete(boolean[] confirmed) {
        Lidar lidar;
        if (modelled.caves() == null) {
            lidar = new Lidar(modelled.anomalies(), modelled.cell(), List.of(), List.of(), 0.0D);
        } else {
            int analyzer = CaveAnalyzer.clamp(request.analyzer());
            CaveAnalysis.Result caves = modelled.caves();
            List<LidarSample> samples = new ArrayList<>(LidarExtraction.caveWalls(caves, analyzer));
            samples.addAll(LidarExtraction.links(caves, confirmed, analyzer));
            lidar =
                    new Lidar(
                            modelled.anomalies(),
                            modelled.cell(),
                            samples,
                            LidarExtraction.caves(
                                    caves,
                                    confirmed,
                                    analyzer,
                                    caveSnapshot.grid().spacing(),
                                    level.getGameTime()),
                            caveSnapshot.radius());
        }
        result = new Result(modelled.map(), modelled.targets(), minY, lidar);
        modelled = null;
        caveSnapshot = null;
        linkCheck = null;
        stage = Stage.DONE;
    }

    private void fail(Throwable throwable) {
        failure = throwable;
        stage = Stage.FAILED;
        for (CompletableFuture<List<DepositCandidate>> future : inFlight) {
            future.cancel(false);
        }
        inFlight.clear();
        awaitingStatus.clear();
    }

    int forecastDeposits() {
        return forecastDeposits;
    }

    private static final Comparator<SubsurfaceBody> BODY_ORDER =
            Comparator.comparingInt((SubsurfaceBody body) -> body.kind().ordinal())
                    .thenComparingLong(SubsurfaceBody::key)
                    .thenComparingDouble(SubsurfaceBody::x)
                    .thenComparingDouble(SubsurfaceBody::z)
                    .thenComparingDouble(SubsurfaceBody::y);
}

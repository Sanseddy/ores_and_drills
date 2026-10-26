package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashSet;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/**
 * Resumable form of {@link DepositLocator}. All world and generator access remains on the server thread;
 * only the chunk-status metadata read uses Minecraft's existing asynchronous chunk scanner.
 */
public final class IncrementalDepositSearch {
    private final DepositTerrainValidator terrainValidator;
    private final ServerLevel level;
    private final BlockPos origin;
    private final ResourceLocation requestedOre;
    private final OreDepositTier requestedTier;
    private final int searchRadius;
    private final long searchRadiusSquared;
    private final Set<Long> confirmedIds = new HashSet<>();
    private final Map<Long, CompletableFuture<Boolean>> decoratedChunks =
            new LinkedHashMap<>(64, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, CompletableFuture<Boolean>> eldest) {
                    return size() > 2_048 && eldest.getValue().isDone();
                }
            };

    private boolean initialized;
    private boolean done;
    private ResourceLocation effectiveOre;
    private boolean dataDriven;
    /** Automatic TINY/SMALL: confirmed from loaded chunk data, otherwise predicted from the attempt stream. */
    private boolean sourceAttempts;
    private double[] predictedCoverage = new double[0];
    private TierDepositResolver resolver;
    private DepositTierLayout layout;
    private int cellSizeBlocks;
    private int searchCenterCellX;
    private int searchCenterCellZ;
    private Set<Long> dataSearchCells = Set.of();
    private DataDrivenOreDepositBiomeModifier.AllowedBiomeSearch biomeSearch;
    private int cellSearchRadius;
    private int ring;
    private int cursor;
    private List<DataDrivenOreDepositBiomeModifier.PlannedDeposit> dataPlans = List.of();
    private List<DepositCandidate> candidates = List.of();
    private int candidateIndex;
    private boolean cellActive;
    private CandidateWork candidateWork;
    private PendingCandidate pending;
    private LocatedDeposit best;
    private long cellsChecked;
    private long candidatesChecked;

    public IncrementalDepositSearch(
            DepositTerrainValidator terrainValidator,
            ServerLevel level,
            BlockPos origin,
            ResourceLocation requestedOre,
            OreDepositTier requestedTier,
            int searchRadius
    ) {
        this.terrainValidator = terrainValidator;
        this.level = level;
        this.origin = origin.immutable();
        this.requestedOre = requestedOre;
        this.requestedTier = requestedTier;
        this.searchRadius = searchRadius;
        this.searchRadiusSquared = (long) searchRadius * searchRadius;
    }

    /** Advances until either the time/operation budget is spent or this tier is complete. */
    public void advance(long deadlineNanos, int maximumOperations) {
        if (done || maximumOperations < 1) return;
        int operations = 0;
        if (!initialized) {
            initialize();
            operations++;
        }
        if (!done && biomeSearch != null) {
            biomeSearch.advance(deadlineNanos, maximumOperations * 4);
            if (!biomeSearch.isDone()) return;
            finishDataDrivenInitialization(biomeSearch.result());
            biomeSearch = null;
            operations++;
        }
        while (!done && operations < maximumOperations && System.nanoTime() < deadlineNanos) {
            if (pending != null) {
                if (!pending.decorated().isDone()) return;
                finishPending();
                operations++;
                continue;
            }
            if (candidateWork != null) {
                int slice = Math.min(8, maximumOperations - operations);
                advanceCandidateWork(deadlineNanos, slice);
                operations += slice;
                continue;
            }
            if (cellActive && candidateIndex < candidates.size()) {
                processCandidate(candidateIndex++);
                candidatesChecked++;
                operations++;
                continue;
            }
            if (cellActive) {
                finishCell();
                continue;
            }
            if (ring > cellSearchRadius) {
                done = true;
                continue;
            }
            prepareCell();
            cellsChecked++;
            operations++;
        }
    }

    public boolean isDone() {
        return done;
    }

    public Optional<LocatedDeposit> result() {
        if (!done) throw new IllegalStateException("Locate search has not completed");
        return Optional.ofNullable(best);
    }

    /** Best fully validated result encountered so far, for first-match navigator searches. */
    public Optional<LocatedDeposit> currentResult() {
        return Optional.ofNullable(best);
    }

    public long cellsChecked() {
        return cellsChecked;
    }

    public long candidatesChecked() {
        return candidatesChecked;
    }

    private void initialize() {
        initialized = true;
        Block requestedBlock = BuiltInRegistries.BLOCK.get(requestedOre);
        Block unifiedBlock = requestedBlock == null
                ? null
                : OreUnifier.canonicalMaterialBlockFor(requestedBlock).orElse(requestedBlock);
        effectiveOre = unifiedBlock == null ? requestedOre : BuiltInRegistries.BLOCK.getKey(unifiedBlock);
        for (ConfirmedDeposit deposit : ConfirmedDepositIndex.get(level).recordsWithin(
                origin, searchRadius, effectiveOre, requestedTier
        )) {
            confirmedIds.add(deposit.depositId());
            long distanceSquared = DepositLocator.horizontalDistanceSquared(origin, deposit.center());
            if (distanceSquared <= searchRadiusSquared) {
                best = DepositLocator.nearer(origin, best, new LocatedDeposit(
                        DepositLocator.confirmedCandidate(deposit), DepositLocateStatus.CONFIRMED,
                        DepositLocator.floorDistance(distanceSquared), 1.0D
                ));
            }
        }

        dataDriven = requestedBlock != null
                && DataDrivenOreDepositBiomeModifier.explicitlyConfigured(level, requestedBlock);
        sourceAttempts = SmallDepositLocate.usesSourceAttempts(requestedTier, dataDriven);

        resolver = DepositResolvers.forTier(requestedTier);
        layout = resolver.layout();
        cellSizeBlocks = layout.cellSizeBlocks();
        searchCenterCellX = Math.floorDiv(origin.getX(), cellSizeBlocks);
        searchCenterCellZ = Math.floorDiv(origin.getZ(), cellSizeBlocks);
        if (dataDriven) {
            biomeSearch = DataDrivenOreDepositBiomeModifier.beginClosestAllowedBiomeSearch(
                    level, origin, requestedBlock, requestedTier, searchRadius
            );
            return;
        }
        cellSearchRadius = Mth.ceil(
                (searchRadius + layout.maximumRadius()) / (double) cellSizeBlocks
        );
    }

    private void finishDataDrivenInitialization(BlockPos nearestAllowedBiome) {
        if (nearestAllowedBiome == null) {
            done = true;
            return;
        }
        int biomeCellX = Math.floorDiv(nearestAllowedBiome.getX(), cellSizeBlocks);
        int biomeCellZ = Math.floorDiv(nearestAllowedBiome.getZ(), cellSizeBlocks);
        searchCenterCellX = biomeCellX;
        searchCenterCellZ = biomeCellZ;
        Set<Long> nearbyCells = new HashSet<>();
        for (int offsetX = -2; offsetX <= 2; offsetX++) {
            for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                nearbyCells.add(ChunkPos.asLong(biomeCellX + offsetX, biomeCellZ + offsetZ));
            }
        }
        dataSearchCells = Set.copyOf(nearbyCells);
        cellSearchRadius = 2;
    }

    private void prepareCell() {
        int perimeter = ring == 0 ? 1 : ring * 8;
        if (cursor >= perimeter) {
            ring++;
            cursor = 0;
            if (ring > cellSearchRadius || best != null && DepositLocator.nextRingCannotBeat(
                    ring, cellSizeBlocks, layout.maximumRadius(), origin, best.candidate().center()
            )) {
                done = true;
                return;
            }
        }
        long offset = DepositLocator.ringOffset(ring, cursor);
        int cellX = searchCenterCellX + (int) (offset >> 32);
        int cellZ = searchCenterCellZ + (int) offset;
        if (sourceAttempts) {
            prepareSourceAttemptCell(cellX, cellZ);
        } else if (dataDriven && !dataSearchCells.contains(ChunkPos.asLong(cellX, cellZ))) {
            dataPlans = List.of();
            candidates = List.of();
        } else {
            dataPlans = dataDriven
                    ? DataDrivenOreDepositBiomeModifier.plansForCell(
                            level, cellX, cellZ, requestedOre, requestedTier
                    )
                    : List.of();
            candidates = dataDriven
                    ? dataPlans.stream().map(DataDrivenOreDepositBiomeModifier.PlannedDeposit::candidate).toList()
                    : resolver.getCandidatesForCell(level, cellX, cellZ, effectiveOre);
        }
        candidateIndex = 0;
        cellActive = true;
    }

    private void prepareSourceAttemptCell(int cellX, int cellZ) {
        dataPlans = List.of();
        List<DepositCandidate> predicted = new java.util.ArrayList<>();
        List<Double> coverage = new java.util.ArrayList<>();
        int size = layout.cellSizeChunks();
        for (int dx = 0; dx < size; dx++) {
            for (int dz = 0; dz < size; dz++) {
                int chunkX = cellX * size + dx;
                int chunkZ = cellZ * size + dz;
                if (SmallDepositLocate.isLoaded(level, chunkX, chunkZ)) {
                    LocatedDeposit confirmed = SmallDepositLocate.confirmedInLoadedChunk(
                            level, chunkX, chunkZ, effectiveOre, requestedTier, origin);
                    if (confirmed != null && DepositLocator.horizontalDistanceSquared(
                            origin, confirmed.candidate().center()) <= searchRadiusSquared) {
                        best = DepositLocator.nearer(origin, best, confirmed);
                    }
                } else {
                    for (SmallDepositAttemptPlanner.Prediction prediction : SmallDepositLocate.predictions(
                            level, chunkX, chunkZ, effectiveOre, requestedTier)) {
                        predicted.add(prediction.candidate());
                        coverage.add(prediction.hostCoverage());
                    }
                }
            }
        }
        candidates = List.copyOf(predicted);
        predictedCoverage = coverage.stream().mapToDouble(Double::doubleValue).toArray();
    }

    /** A predicted candidate is reported only while its source chunk has not been decorated yet. */
    private void processSourceAttemptPrediction(int index) {
        DepositCandidate candidate = candidates.get(index);
        long distanceSquared = DepositLocator.horizontalDistanceSquared(origin, candidate.center());
        if (distanceSquared > searchRadiusSquared || best != null && distanceSquared
                >= DepositLocator.horizontalDistanceSquared(origin, best.candidate().center())) {
            return;
        }
        long chunkKey = ChunkPos.asLong(candidate.sourceChunkX(), candidate.sourceChunkZ());
        CompletableFuture<Boolean> decorated = decoratedChunks.computeIfAbsent(
                chunkKey, ignored -> inspectDecoratedChunk(candidate));
        pending = new PendingCandidate(candidate, SmallDepositLocate.predictedValidation(predictedCoverage[index]),
                distanceSquared, decorated);
        if (decorated.isDone()) finishPending();
    }

    private void finishCell() {
        cellActive = false;
        candidates = List.of();
        dataPlans = List.of();
        cursor++;
    }

    private void processCandidate(int index) {
        if (sourceAttempts) {
            processSourceAttemptPrediction(index);
            return;
        }
        DepositCandidate candidate = candidates.get(index);
        DataDrivenOreDepositBiomeModifier.PlannedDeposit dataPlan = dataDriven ? dataPlans.get(index) : null;
        if (confirmedIds.contains(candidate.depositId())
                || !candidate.oreId().equals(effectiveOre)
                || candidate.tier() != requestedTier
                || level.getChunkSource().getChunkNow(candidate.sourceChunkX(), candidate.sourceChunkZ()) != null
                || dataPlan != null && dataPlan.placement() == OreDepositFeature.PlacementMode.CAVE_WALL) {
            return;
        }

        Predicate<BlockPos> dataWriteBounds = dataPlan == null
                ? ignored -> true : DepositLocator.dataDrivenWriteBounds(candidate);
        BiPredicate<BlockPos, net.minecraft.world.level.block.state.BlockState> predictedReplacement =
                dataPlan == null ? (ignoredPos, ignoredState) -> false : dataPlan.predictedReplacement(level);
        DepositTerrainValidator.RelocationSearch relocation = dataPlan != null
                ? terrainValidator.beginRelocation(level, candidate, predictedReplacement, dataWriteBounds)
                : terrainValidator.beginUndergroundFallback(level, candidate);
        candidateWork = new CandidateWork(
                candidate, relocation, predictedReplacement, dataWriteBounds, dataPlan != null
        );
    }

    private void advanceCandidateWork(long deadlineNanos, int maximumOperations) {
        CandidateWork work = candidateWork;
        if (!work.relocation.isDone()) {
            work.relocation.advance(deadlineNanos, maximumOperations);
            if (!work.relocation.isDone() || System.nanoTime() >= deadlineNanos) return;
        }
        if (work.validation == null) {
            work.locatedCandidate = work.relocation.result();
            if (work.locatedCandidate == null) {
                candidateWork = null;
                return;
            }
            work.distanceSquared = DepositLocator.horizontalDistanceSquared(
                    origin, work.locatedCandidate.center()
            );
            if (work.distanceSquared > searchRadiusSquared || best != null && work.distanceSquared >=
                    DepositLocator.horizontalDistanceSquared(origin, best.candidate().center())) {
                candidateWork = null;
                return;
            }
            work.validation = work.dataDriven
                    ? terrainValidator.beginValidationMatching(
                            level, work.locatedCandidate, layout,
                            work.predictedReplacement, work.writeBounds
                    )
                    : terrainValidator.beginValidation(level, work.locatedCandidate, layout);
        }
        work.validation.advance(deadlineNanos, maximumOperations);
        if (!work.validation.isDone()) return;

        candidateWork = null;
        ValidationResult validation = work.validation.result();
        if (!validation.viable()) return;

        DepositCandidate sourceCandidate = work.sourceCandidate;
        long chunkKey = ChunkPos.asLong(sourceCandidate.sourceChunkX(), sourceCandidate.sourceChunkZ());
        CompletableFuture<Boolean> decorated = decoratedChunks.computeIfAbsent(
                chunkKey, ignored -> inspectDecoratedChunk(sourceCandidate)
        );
        pending = new PendingCandidate(
                work.locatedCandidate, validation, work.distanceSquared, decorated
        );
        if (decorated.isDone()) finishPending();
    }

    private CompletableFuture<Boolean> inspectDecoratedChunk(DepositCandidate candidate) {
        CollectFields visitor = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
        return level.getChunkSource().chunkScanner()
                .scanChunk(new ChunkPos(candidate.sourceChunkX(), candidate.sourceChunkZ()), visitor)
                .handle((ignored, failure) -> {
                    if (failure != null) {
                        OresAndDrillsMod.LOGGER.debug(
                                "Could not inspect saved status of ore-deposit source chunk [{}, {}] in {}",
                                candidate.sourceChunkX(), candidate.sourceChunkZ(),
                                level.dimension().location(), failure
                        );
                        return false;
                    }
                    if (!(visitor.getResult() instanceof CompoundTag root)) return false;
                    ChunkStatus status = ChunkStatus.byName(root.getString("Status"));
                    return status != null && status.isOrAfter(ChunkStatus.FEATURES);
                });
    }

    private void finishPending() {
        PendingCandidate completed = pending;
        pending = null;
        if (completed.decorated().join()) return;
        // Last, as it builds the worldgen plan: a sampled pass can still accept a lens that worldgen rejects.
        if (!sourceAttempts && !dataDriven && !OreDepositFeature.compactCandidateGenerates(level, completed.candidate())) return;
        LocatedDeposit located = new LocatedDeposit(
                completed.candidate(), DepositLocateStatus.SEED_PREDICTED,
                DepositLocator.floorDistance(completed.distanceSquared()),
                completed.validation().stoneCoverage()
        );
        best = DepositLocator.nearer(origin, best, located);
    }

    private record PendingCandidate(
            DepositCandidate candidate,
            ValidationResult validation,
            long distanceSquared,
            CompletableFuture<Boolean> decorated
    ) {
    }

    private static final class CandidateWork {
        private final DepositCandidate sourceCandidate;
        private final DepositTerrainValidator.RelocationSearch relocation;
        private final BiPredicate<BlockPos, net.minecraft.world.level.block.state.BlockState> predictedReplacement;
        private final Predicate<BlockPos> writeBounds;
        private final boolean dataDriven;
        private DepositCandidate locatedCandidate;
        private DepositTerrainValidator.ValidationSearch validation;
        private long distanceSquared;

        private CandidateWork(
                DepositCandidate sourceCandidate,
                DepositTerrainValidator.RelocationSearch relocation,
                BiPredicate<BlockPos, net.minecraft.world.level.block.state.BlockState> predictedReplacement,
                Predicate<BlockPos> writeBounds,
                boolean dataDriven
        ) {
            this.sourceCandidate = sourceCandidate;
            this.relocation = relocation;
            this.predictedReplacement = predictedReplacement;
            this.writeBounds = writeBounds;
            this.dataDriven = dataDriven;
        }
    }
}

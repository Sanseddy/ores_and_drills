package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.config.OreDepositConfig;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.visitors.CollectFields;
import net.minecraft.nbt.visitors.FieldSelector;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Ring-based nearest search over confirmed records and deterministic generation cells. */
public final class DepositLocator {
    public static final int SEARCH_RADIUS = 20_000;
    public static final long SEARCH_RADIUS_SQUARED = (long) SEARCH_RADIUS * SEARCH_RADIUS;

    private final DepositTerrainValidator terrainValidator;

    public DepositLocator(DepositTerrainValidator terrainValidator) {
        this.terrainValidator = terrainValidator;
    }

    public Optional<LocatedDeposit> findNearest(
            ServerLevel level,
            BlockPos origin,
            ResourceLocation requestedOre,
            OreDepositTier requestedTier,
            int searchRadius
    ) {
        long searchRadiusSquared = (long) searchRadius * searchRadius;
        Block requestedBlock = BuiltInRegistries.BLOCK.get(requestedOre);
        Block unifiedBlock = requestedBlock == null
                ? null
                : OreUnifier.canonicalMaterialBlockFor(requestedBlock).orElse(requestedBlock);
        ResourceLocation effectiveOre = unifiedBlock == null
                ? requestedOre
                : BuiltInRegistries.BLOCK.getKey(unifiedBlock);
        LocatedDeposit best = null;
        Set<Long> confirmedIds = new HashSet<>();
        Map<Long, Boolean> decoratedChunks = new HashMap<>();
        List<ConfirmedDeposit> confirmed = ConfirmedDepositIndex.get(level).recordsWithin(
                origin, searchRadius, effectiveOre, requestedTier
        );
        for (ConfirmedDeposit deposit : confirmed) {
            confirmedIds.add(deposit.depositId());
            long distanceSquared = horizontalDistanceSquared(origin, deposit.center());
            if (distanceSquared > searchRadiusSquared) {
                continue;
            }
            DepositCandidate candidate = confirmedCandidate(deposit);
            best = nearer(origin, best, new LocatedDeposit(
                    candidate, DepositLocateStatus.CONFIRMED, floorDistance(distanceSquared), 1.0D
            ));
        }

        boolean dataDriven = requestedBlock != null
                && DataDrivenOreDepositBiomeModifier.explicitlyConfigured(level, requestedBlock);
        if (!dataDriven && requestedTier.prefersCaveWall() && OreDepositConfig.STRICT_WALL_DEPOSIT_LOCATE.get()) {
            return Optional.ofNullable(best);
        }

        TierDepositResolver resolver = DepositResolvers.forTier(requestedTier);
        DepositTierLayout layout = resolver.layout();
        int cellSizeBlocks = layout.cellSizeBlocks();
        Set<Long> dataSearchCells = Set.of();
        if (dataDriven) {
            BlockPos nearestAllowedBiome = DataDrivenOreDepositBiomeModifier.findClosestAllowedBiome(
                    level, origin, requestedBlock, requestedTier, searchRadius
            );
            if (nearestAllowedBiome == null) {
                return Optional.ofNullable(best);
            }
            int biomeCellX = Math.floorDiv(nearestAllowedBiome.getX(), cellSizeBlocks);
            int biomeCellZ = Math.floorDiv(nearestAllowedBiome.getZ(), cellSizeBlocks);
            Set<Long> nearbyCells = new HashSet<>();
            // The closest allowed point can sit on a cell edge while the immutable slot selects a source
            // in the neighbouring cell. Evaluate a small fixed neighbourhood, then let the ordinary
            // candidate/terrain checks decide which plans truly exist.
            for (int offsetX = -2; offsetX <= 2; offsetX++) {
                for (int offsetZ = -2; offsetZ <= 2; offsetZ++) {
                    nearbyCells.add(ChunkPos.asLong(biomeCellX + offsetX, biomeCellZ + offsetZ));
                }
            }
            dataSearchCells = Set.copyOf(nearbyCells);
        }
        int cellSearchRadius = Mth.ceil(
                (searchRadius + layout.maximumRadius()) / (double) cellSizeBlocks
        );
        int originCellX = Math.floorDiv(origin.getX(), cellSizeBlocks);
        int originCellZ = Math.floorDiv(origin.getZ(), cellSizeBlocks);
        OresAndDrillsMod.LOGGER.debug(
                "Ore locate pipeline: tier={}, resolver={}, cellSizeBlocks={}, cellSearchRadius={}, "
                        + "tierSalt={}, sampleCount={}..{}, maximumRadius={}, validation=coverage>={}, sections>={}",
                requestedTier.serializedName(),
                resolver.getClass().getSimpleName(),
                cellSizeBlocks,
                cellSearchRadius,
                Long.toUnsignedString(resolver.tierSalt()),
                layout.minimumSamples(),
                layout.maximumSamples(),
                layout.maximumRadius(),
                layout.requiredStoneCoverage(),
                TieredDepositSampler.minimumValidSections(requestedTier)
        );

        for (int ring = 0; ring <= cellSearchRadius; ring++) {
            int perimeter = ring == 0 ? 1 : ring * 8;
            for (int cursor = 0; cursor < perimeter; cursor++) {
                long offset = ringOffset(ring, cursor);
                int cellX = originCellX + (int) (offset >> 32);
                int cellZ = originCellZ + (int) offset;
                if (dataDriven && !dataSearchCells.contains(ChunkPos.asLong(cellX, cellZ))) {
                    continue;
                }
                List<DataDrivenOreDepositBiomeModifier.PlannedDeposit> dataPlans = dataDriven
                        ? DataDrivenOreDepositBiomeModifier.plansForCell(
                                level, cellX, cellZ, requestedOre, requestedTier
                        )
                        : List.of();
                List<DepositCandidate> candidates = dataDriven
                        ? dataPlans.stream().map(DataDrivenOreDepositBiomeModifier.PlannedDeposit::candidate).toList()
                        : resolver.getCandidatesForCell(level, cellX, cellZ, effectiveOre);
                for (int candidateIndex = 0; candidateIndex < candidates.size(); candidateIndex++) {
                    DepositCandidate candidate = candidates.get(candidateIndex);
                    DataDrivenOreDepositBiomeModifier.PlannedDeposit dataPlan = dataDriven
                            ? dataPlans.get(candidateIndex)
                            : null;
                    if (confirmedIds.contains(candidate.depositId())
                            || !candidate.oreId().equals(effectiveOre)
                            || candidate.tier() != requestedTier) {
                        continue;
                    }
                    // A loaded FULL chunk has already had its features applied. If its candidate is not
                    // in the authoritative confirmed index, base-terrain prediction must not resurrect it.
                    // Snapshot the candidate's real source chunk. Data-driven frequency slots may use a
                    // different source than the tier's automatic anchor.
                    boolean sourceChunkIsLoaded = level.getChunkSource().getChunkNow(
                            candidate.sourceChunkX(), candidate.sourceChunkZ()
                    ) != null;
                    if (sourceChunkIsLoaded) {
                        continue;
                    }
                    if (dataPlan != null
                            && dataPlan.placement() == OreDepositFeature.PlacementMode.CAVE_WALL) {
                        // Remote base terrain has no carvers, so a strict wall-only rule can only be
                        // returned from the authoritative confirmed index after its chunk generates.
                        continue;
                    }
                    java.util.function.Predicate<BlockPos> dataWriteBounds = dataPlan == null
                            ? ignored -> true
                            : dataDrivenWriteBounds(candidate);
                    java.util.function.BiPredicate<BlockPos, net.minecraft.world.level.block.state.BlockState>
                            predictedReplacement = dataPlan == null
                            ? (ignoredPos, ignoredState) -> false
                            : dataPlan.predictedReplacement(level);
                    DepositCandidate locatedCandidate = dataPlan != null
                            ? terrainValidator.relocateToMatchingHost(
                                    level, candidate, predictedReplacement, dataWriteBounds
                            )
                            : requestedTier.prefersCaveWall()
                                    ? terrainValidator.undergroundFallbackCandidate(level, candidate)
                                    : candidate;
                    if (locatedCandidate == null) {
                        continue;
                    }
                    long distanceSquared = horizontalDistanceSquared(origin, locatedCandidate.center());
                    if (distanceSquared > searchRadiusSquared
                            || best != null && distanceSquared >= horizontalDistanceSquared(
                                    origin, best.candidate().center()
                            )) {
                        continue;
                    }
                    ValidationResult validation = dataPlan != null
                            ? terrainValidator.validateMatching(
                                    level, locatedCandidate, layout, predictedReplacement, dataWriteBounds
                            )
                            : terrainValidator.validate(level, locatedCandidate, layout);
                    if (!validation.viable()) {
                        continue;
                    }
                    // getChunkNow only covers the live cache. An explored chunk can already be saved on
                    // disk and later unloaded; predicting its rejected candidate again was the remaining
                    // source of stale /locate results. Read only the saved Status field and reject chunks
                    // whose FEATURES step has already run, without loading or generating the chunk.
                    if (sourceChunkAlreadyDecorated(level, candidate, decoratedChunks)) {
                        continue;
                    }
                    // Unexplored carvers cannot be reproduced by getBaseColumn. Validate the guaranteed
                    // underground fallback without loading the remote chunk; a real cave-wall placement
                    // replaces this prediction with its authoritative confirmed record after generation.
                    best = nearer(origin, best, new LocatedDeposit(
                            locatedCandidate,
                            DepositLocateStatus.SEED_PREDICTED,
                            floorDistance(distanceSquared),
                            validation.stoneCoverage()
                    ));
                }
            }

            if (best != null && nextRingCannotBeat(
                    ring + 1,
                    cellSizeBlocks,
                    layout.maximumRadius(),
                    origin,
                    best.candidate().center()
            )) {
                break;
            }
        }
        return Optional.ofNullable(best);
    }

    private static boolean sourceChunkAlreadyDecorated(
            ServerLevel level,
            DepositCandidate candidate,
            Map<Long, Boolean> cache
    ) {
        long chunkKey = ChunkPos.asLong(candidate.sourceChunkX(), candidate.sourceChunkZ());
        return cache.computeIfAbsent(chunkKey, ignored -> {
            CollectFields visitor = new CollectFields(new FieldSelector(StringTag.TYPE, "Status"));
            try {
                level.getChunkSource().chunkScanner()
                        .scanChunk(new ChunkPos(candidate.sourceChunkX(), candidate.sourceChunkZ()), visitor)
                        .join();
                if (!(visitor.getResult() instanceof CompoundTag root)) {
                    return false;
                }
                ChunkStatus status = ChunkStatus.byName(root.getString("Status"));
                return status != null && status.isOrAfter(ChunkStatus.FEATURES);
            } catch (RuntimeException exception) {
                OresAndDrillsMod.LOGGER.warn(
                        "Could not inspect saved status of ore-deposit source chunk [{}, {}] in {}",
                        candidate.sourceChunkX(), candidate.sourceChunkZ(), level.dimension().location(), exception
                );
                // A failed metadata read is not proof that the chunk was generated. Keep the deterministic
                // prediction available instead of turning a transient I/O failure into a false not-found.
                return false;
            }
        });
    }

    private static java.util.function.Predicate<BlockPos> dataDrivenWriteBounds(DepositCandidate candidate) {
        int radius = OreDepositFeature.FEATURE_WRITE_RADIUS_CHUNKS;
        int minimumX = (candidate.sourceChunkX() - radius) << 4;
        int maximumX = ((candidate.sourceChunkX() + radius + 1) << 4) - 1;
        int minimumZ = (candidate.sourceChunkZ() - radius) << 4;
        int maximumZ = ((candidate.sourceChunkZ() + radius + 1) << 4) - 1;
        return pos -> pos.getX() >= minimumX && pos.getX() <= maximumX
                && pos.getZ() >= minimumZ && pos.getZ() <= maximumZ;
    }

    private static DepositCandidate confirmedCandidate(ConfirmedDeposit deposit) {
        return new DepositCandidate(
                deposit.depositId(),
                deposit.oreId(),
                deposit.tier(),
                deposit.center(),
                1,
                1,
                1,
                deposit.depositId(),
                deposit.wallDirection(),
                deposit.placedBlocks(),
                deposit.center().getX() >> 4,
                deposit.center().getZ() >> 4
        );
    }

    private static boolean nextRingCannotBeat(
            int nextRing,
            int cellSizeBlocks,
            int maximumRadius,
            BlockPos origin,
            BlockPos currentBest
    ) {
        long guaranteedDistance = Math.max(
                0L,
                (long) (nextRing - 1) * cellSizeBlocks - maximumRadius
        );
        long bestDistanceSquared = horizontalDistanceSquared(origin, currentBest);
        return guaranteedDistance * guaranteedDistance >= bestDistanceSquared;
    }

    private static LocatedDeposit nearer(
            BlockPos origin,
            LocatedDeposit current,
            LocatedDeposit candidate
    ) {
        if (current == null) {
            return candidate;
        }
        long currentDistance = horizontalDistanceSquared(origin, current.candidate().center());
        long candidateDistance = horizontalDistanceSquared(origin, candidate.candidate().center());
        if (candidateDistance < currentDistance) {
            return candidate;
        }
        if (candidateDistance == currentDistance && candidate.confirmed() && !current.confirmed()) {
            return candidate;
        }
        return current;
    }

    private static int floorDistance(long distanceSquared) {
        return (int) Math.sqrt(distanceSquared);
    }

    public static long horizontalDistanceSquared(BlockPos first, BlockPos second) {
        long dx = (long) first.getX() - second.getX();
        long dz = (long) first.getZ() - second.getZ();
        return dx * dx + dz * dz;
    }

    /** Packs signed X/Z offsets; each perimeter position is visited once. */
    static long ringOffset(int ring, int cursor) {
        if (ring == 0) {
            return 0L;
        }
        int sideLength = ring * 2;
        int side = cursor / sideLength;
        int position = cursor % sideLength;
        int x;
        int z;
        switch (side) {
            case 0 -> {
                x = -ring + position;
                z = -ring;
            }
            case 1 -> {
                x = ring;
                z = -ring + position;
            }
            case 2 -> {
                x = ring - position;
                z = ring;
            }
            default -> {
                x = -ring;
                z = ring - position;
            }
        }
        return ((long) x << 32) ^ (z & 0xFFFFFFFFL);
    }
}

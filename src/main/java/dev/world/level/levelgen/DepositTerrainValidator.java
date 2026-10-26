package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.command.OreDepositLocateService;
import dev.registry.ModBlockTags;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/** Validates unexplored candidates from base terrain without loading or generating chunks. */
public final class DepositTerrainValidator {
    private final DepositSampler sampler;

    public DepositTerrainValidator() {
        this(new TieredDepositSampler());
    }

    public DepositTerrainValidator(DepositSampler sampler) {
        this.sampler = sampler;
    }

    public boolean isViable(ServerLevel level, DepositCandidate candidate) {
        return validate(level, candidate, DepositLayouts.forTier(candidate.tier())).viable();
    }

    /**
     * Resolves the same nearest-host fallback used by real generation, but reads base columns so
     * remote chunks remain unloaded. Carved cave walls are intentionally left to authoritative
     * generated records.
     */
    @Nullable
    public DepositCandidate undergroundFallbackCandidate(
            ServerLevel level, DepositCandidate candidate) {
        if (!candidate.tier().prefersCaveWall()) {
            return candidate;
        }
        return relocateToMatchingHost(
                level,
                candidate,
                state -> state.is(ModBlockTags.STONES) && state.getFluidState().isEmpty());
    }

    /** Mirrors data-driven generation's strict replaceable-host relocation using base terrain. */
    @Nullable
    public DepositCandidate relocateToMatchingHost(
            ServerLevel level, DepositCandidate candidate, Predicate<BlockState> hostPredicate) {
        return relocateToMatchingHost(level, candidate, hostPredicate, ignored -> true);
    }

    @Nullable
    public DepositCandidate relocateToMatchingHost(
            ServerLevel level,
            DepositCandidate candidate,
            Predicate<BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed) {
        return relocateToMatchingHost(
                level,
                candidate,
                (ignoredPos, state) -> hostPredicate.test(state),
                positionAllowed);
    }

    @Nullable
    public DepositCandidate relocateToMatchingHost(
            ServerLevel level,
            DepositCandidate candidate,
            BiPredicate<BlockPos, BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed) {
        RelocationSearch search = beginRelocation(level, candidate, hostPredicate, positionAllowed);
        while (!search.isDone()) search.advance(Long.MAX_VALUE, Integer.MAX_VALUE);
        return search.result();
    }

    RelocationSearch beginUndergroundFallback(ServerLevel level, DepositCandidate candidate) {
        if (!candidate.tier().prefersCaveWall()) return RelocationSearch.completed(candidate);
        return beginRelocation(
                level,
                candidate,
                (ignoredPos, state) ->
                        state.is(ModBlockTags.STONES) && state.getFluidState().isEmpty(),
                ignored -> true);
    }

    RelocationSearch beginRelocation(
            ServerLevel level,
            DepositCandidate candidate,
            BiPredicate<BlockPos, BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed) {
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        Map<Long, NoiseColumn> columns = new HashMap<>();
        TaggedStoneRelocator.Search search =
                new TaggedStoneRelocator.Search(
                        candidate.center(),
                        OreDepositFeature.taggedStoneHorizontalRadius(
                                candidate.radiusX(), candidate.radiusZ()),
                        OreDepositFeature.taggedStoneVerticalRadius(candidate.verticalRadius()),
                        OreDepositFeature.MAX_TAGGED_STONE_SEARCH_CHECKS,
                        level.getMinBuildHeight() + OreDepositFeature.BEDROCK_CLEARANCE,
                        level.getMaxBuildHeight(),
                        positionAllowed,
                        pos -> {
                            long columnKey = ((long) pos.getX() << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
                            NoiseColumn column =
                                    columns.computeIfAbsent(
                                            columnKey,
                                            ignored ->
                                                    generator.getBaseColumn(
                                                            pos.getX(),
                                                            pos.getZ(),
                                                            level,
                                                            randomState));
                            return hostPredicate.test(pos, column.getBlock(pos.getY()));
                        });
        return new RelocationSearch(level, candidate, search);
    }

    static final class RelocationSearch {
        private final ServerLevel level;
        private final DepositCandidate candidate;
        private final TaggedStoneRelocator.Search search;
        private boolean done;
        private DepositCandidate result;

        private RelocationSearch(
                ServerLevel level, DepositCandidate candidate, TaggedStoneRelocator.Search search) {
            this.level = level;
            this.candidate = candidate;
            this.search = search;
        }

        private RelocationSearch(DepositCandidate result) {
            this.level = null;
            this.candidate = result;
            this.search = null;
            this.result = result;
            this.done = true;
        }

        static RelocationSearch completed(DepositCandidate result) {
            return new RelocationSearch(result);
        }

        void advance(long deadlineNanos, int maximumOperations) {
            if (done) return;
            try {
                search.advance(deadlineNanos, maximumOperations);
                if (search.isDone()) {
                    BlockPos center = search.result();
                    result = center == null ? null : candidate.withUndergroundCenter(center);
                    done = true;
                }
            } catch (RuntimeException exception) {
                OresAndDrillsMod.LOGGER.debug(
                        "Could not resolve underground fallback for ore-deposit candidate {} in {}",
                        candidate.depositId(),
                        level.dimension().location(),
                        exception);
                done = true;
            }
        }

        boolean isDone() {
            return done;
        }

        DepositCandidate result() {
            if (!done) throw new IllegalStateException("Deposit relocation has not completed");
            return result;
        }
    }

    /** Strict data-driven validation; intentionally separate from the #c:stones cache. */
    public ValidationResult validateMatching(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            Predicate<BlockState> hostPredicate) {
        return validateMatching(level, candidate, layout, hostPredicate, ignored -> true);
    }

    public ValidationResult validateMatching(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            Predicate<BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed) {
        return validateMatching(
                level,
                candidate,
                layout,
                (ignoredPos, state) -> hostPredicate.test(state),
                positionAllowed);
    }

    public ValidationResult validateMatching(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            BiPredicate<BlockPos, BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed) {
        ValidationSearch search =
                beginValidationMatching(level, candidate, layout, hostPredicate, positionAllowed);
        while (!search.isDone()) search.advance(Long.MAX_VALUE, Integer.MAX_VALUE);
        return search.result();
    }

    ValidationSearch beginValidationMatching(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            BiPredicate<BlockPos, BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed) {
        boolean standardLayout = DepositLayouts.forTier(candidate.tier()).equals(layout);
        if (!standardLayout) {
            throw new IllegalArgumentException("Candidate tier and validation layout do not match");
        }
        return new ValidationSearch(level, candidate, layout, hostPredicate, positionAllowed, null);
    }

    public ValidationResult validate(
            ServerLevel level, DepositCandidate candidate, DepositTierLayout layout) {
        ValidationSearch search = beginValidation(level, candidate, layout);
        while (!search.isDone()) search.advance(Long.MAX_VALUE, Integer.MAX_VALUE);
        return search.result();
    }

    ValidationSearch beginValidation(
            ServerLevel level, DepositCandidate candidate, DepositTierLayout layout) {
        boolean standardLayout = DepositLayouts.forTier(candidate.tier()).equals(layout);
        if (!standardLayout) {
            throw new IllegalArgumentException("Candidate tier and validation layout do not match");
        }
        DepositValidationKey key =
                new DepositValidationKey(
                        level.dimension(),
                        level.getSeed(),
                        candidate.tier(),
                        candidate.depositId());
        ValidationResult cached = DepositValidationCache.get(key);
        if (cached != null) {
            return new ValidationSearch(cached);
        }
        return new ValidationSearch(
                level,
                candidate,
                layout,
                (ignoredPos, state) ->
                        state.is(ModBlockTags.STONES) && state.getFluidState().isEmpty(),
                ignored -> true,
                key);
    }

    /**
     * Server tags and datapacks have changed; all tag-derived catalogs and geology results are
     * stale.
     */
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        clearCache();
        OreDepositLocateService.invalidateAll();
        OreTags.clearCache();
        OreUnifier.clearCache();
        OreDepositOrePalette.clearAvailableCache();
        OreDepositStonePalette.clearAvailableCache();
    }

    public static void clearCache() {
        DepositValidationCache.clear();
        BiomeNoiseCache.clear();
    }

    /** Resumable terrain validation; each operation performs at most one sample lookup. */
    final class ValidationSearch {
        private final ServerLevel level;
        private final DepositCandidate candidate;
        private final DepositTierLayout layout;
        private final BiPredicate<BlockPos, BlockState> hostPredicate;
        private final Predicate<BlockPos> positionAllowed;
        private final DepositValidationKey cacheKey;
        private final ChunkGenerator generator;
        private final RandomState randomState;
        private final List<SamplePoint> samples;
        private final boolean[] verticalCoverage = new boolean[3];
        private final boolean[] radialCoverage = new boolean[4];
        private final boolean[] sideCoverage = new boolean[4];
        private final Set<Integer> totalSections = new HashSet<>();
        private final Set<Integer> validSections = new HashSet<>();
        private final Map<Long, NoiseColumn> columns = new HashMap<>();
        private int sampleIndex;
        private int stones;
        private int checked;
        private boolean centerStone;
        private boolean done;
        private ValidationResult result;

        private ValidationSearch(
                ServerLevel level,
                DepositCandidate candidate,
                DepositTierLayout layout,
                BiPredicate<BlockPos, BlockState> hostPredicate,
                Predicate<BlockPos> positionAllowed,
                DepositValidationKey cacheKey) {
            this.level = level;
            this.candidate = candidate;
            this.layout = layout;
            this.hostPredicate = hostPredicate;
            this.positionAllowed = positionAllowed;
            this.cacheKey = cacheKey;
            ServerChunkCache chunkSource = level.getChunkSource();
            this.generator = chunkSource.getGenerator();
            this.randomState = chunkSource.randomState();
            this.samples = sampler.createSamples(candidate, layout);
        }

        private ValidationSearch(ValidationResult result) {
            this.level = null;
            this.candidate = null;
            this.layout = null;
            this.hostPredicate = null;
            this.positionAllowed = null;
            this.cacheKey = null;
            this.generator = null;
            this.randomState = null;
            this.samples = List.of();
            this.result = result;
            this.done = true;
        }

        void advance(long deadlineNanos, int maximumOperations) {
            int operations = 0;
            try {
                while (!done
                        && operations++ < maximumOperations
                        && System.nanoTime() < deadlineNanos) {
                    if (sampleIndex >= samples.size()) {
                        finish();
                        return;
                    }
                    SamplePoint samplePoint = samples.get(sampleIndex++);
                    BlockPos sample = samplePoint.pos();
                    totalSections.add(samplePoint.section());
                    checked++;
                    if (sample.getY() < level.getMinBuildHeight()
                            || sample.getY() >= level.getMaxBuildHeight()
                            || !positionAllowed.test(sample)) continue;
                    long columnKey = ((long) sample.getX() << 32) ^ (sample.getZ() & 0xFFFFFFFFL);
                    NoiseColumn column =
                            columns.computeIfAbsent(
                                    columnKey,
                                    ignored ->
                                            generator.getBaseColumn(
                                                    sample.getX(),
                                                    sample.getZ(),
                                                    level,
                                                    randomState));
                    if (!hostPredicate.test(sample, column.getBlock(sample.getY()))) continue;
                    stones++;
                    validSections.add(samplePoint.section());
                    centerStone |= sample.equals(candidate.center());
                    verticalCoverage[verticalBand(candidate, sample)] = true;
                    radialCoverage[radialBand(candidate, sample)] = true;
                    sideCoverage[side(candidate, sample)] = true;
                }
                if (!done && sampleIndex >= samples.size()) finish();
            } catch (RuntimeException exception) {
                OresAndDrillsMod.LOGGER.debug(
                        "Could not predict terrain for ore-deposit candidate {} in {}",
                        candidate.depositId(),
                        level.dimension().location(),
                        exception);
                double coverage = checked == 0 ? 0.0D : stones / (double) checked;
                complete(
                        new ValidationResult(
                                false,
                                stones,
                                checked,
                                coverage,
                                validSections.size(),
                                totalSections.size()));
            }
        }

        boolean isDone() {
            return done;
        }

        ValidationResult result() {
            if (!done) throw new IllegalStateException("Deposit validation has not completed");
            return result;
        }

        private void finish() {
            double stoneFraction = checked == 0 ? 0.0D : (double) stones / checked;
            int minimumSections = TieredDepositSampler.minimumValidSections(candidate.tier());
            boolean viable =
                    stoneFraction >= layout.requiredStoneCoverage()
                            && validSections.size() >= minimumSections
                            && switch (candidate.tier()) {
                                case TINY -> centerStone && coveredBands(radialCoverage) >= 2;
                                case SMALL ->
                                        coveredBands(verticalCoverage) >= 2
                                                && coveredBands(radialCoverage) >= 2
                                                && coveredBands(sideCoverage) >= 2;
                                case MEDIUM ->
                                        coveredBands(verticalCoverage) >= 2
                                                && coveredBands(radialCoverage) >= 3
                                                && coveredBands(sideCoverage) >= 3;
                                case LARGE ->
                                        centerStone
                                                && coveredBands(verticalCoverage) >= 3
                                                && coveredBands(radialCoverage) >= 3
                                                && coveredBands(sideCoverage) >= 3;
                            };
            complete(
                    new ValidationResult(
                            viable,
                            stones,
                            checked,
                            stoneFraction,
                            validSections.size(),
                            totalSections.size()));
        }

        private void complete(ValidationResult completed) {
            result = completed;
            done = true;
            if (cacheKey != null) DepositValidationCache.put(cacheKey, completed);
        }
    }

    private static int verticalBand(DepositCandidate candidate, BlockPos sample) {
        double normalized =
                (double) (candidate.center().getY() - sample.getY())
                        / Math.max(1, candidate.verticalRadius());
        if (normalized < 0.34D) {
            return 0;
        }
        return normalized < 0.68D ? 1 : 2;
    }

    private static int radialBand(DepositCandidate candidate, BlockPos sample) {
        double x = (double) (sample.getX() - candidate.center().getX()) / candidate.radiusX();
        double z = (double) (sample.getZ() - candidate.center().getZ()) / candidate.radiusZ();
        double radius = Math.sqrt(x * x + z * z);
        if (radius < 0.25D) {
            return 0;
        }
        if (radius < 0.55D) {
            return 1;
        }
        return radius < 0.80D ? 2 : 3;
    }

    private static int side(DepositCandidate candidate, BlockPos sample) {
        long dx = (long) sample.getX() - candidate.center().getX();
        long dz = (long) sample.getZ() - candidate.center().getZ();
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0L ? 0 : 2;
        }
        return dz >= 0L ? 1 : 3;
    }

    private static int coveredBands(boolean[] bands) {
        int count = 0;
        for (boolean covered : bands) {
            if (covered) {
                count++;
            }
        }
        return count;
    }
}

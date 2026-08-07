package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModBlockTags;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.List;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

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
     * Resolves the same nearest-host fallback used by real generation, but reads base columns so remote
     * chunks remain unloaded. Carved cave walls are intentionally left to authoritative generated records.
     */
    @Nullable
    public DepositCandidate undergroundFallbackCandidate(ServerLevel level, DepositCandidate candidate) {
        if (!candidate.tier().prefersCaveWall()) {
            return candidate;
        }
        return relocateToMatchingHost(
                level,
                candidate,
                state -> state.is(ModBlockTags.STONES) && state.getFluidState().isEmpty()
        );
    }

    /** Mirrors data-driven generation's strict replaceable-host relocation using base terrain. */
    @Nullable
    public DepositCandidate relocateToMatchingHost(
            ServerLevel level,
            DepositCandidate candidate,
            Predicate<BlockState> hostPredicate
    ) {
        return relocateToMatchingHost(level, candidate, hostPredicate, ignored -> true);
    }

    @Nullable
    public DepositCandidate relocateToMatchingHost(
            ServerLevel level,
            DepositCandidate candidate,
            Predicate<BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed
    ) {
        return relocateToMatchingHost(
                level, candidate, (ignoredPos, state) -> hostPredicate.test(state), positionAllowed
        );
    }

    @Nullable
    public DepositCandidate relocateToMatchingHost(
            ServerLevel level,
            DepositCandidate candidate,
            BiPredicate<BlockPos, BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed
    ) {
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        Map<Long, NoiseColumn> columns = new HashMap<>();
        try {
            BlockPos center = TaggedStoneRelocator.findNearest(
                    candidate.center(),
                    OreDepositFeature.taggedStoneHorizontalRadius(candidate.radiusX(), candidate.radiusZ()),
                    OreDepositFeature.taggedStoneVerticalRadius(candidate.verticalRadius()),
                    OreDepositFeature.MAX_TAGGED_STONE_SEARCH_CHECKS,
                    level.getMinBuildHeight() + OreDepositFeature.BEDROCK_CLEARANCE,
                    level.getMaxBuildHeight(),
                    positionAllowed,
                    pos -> {
                        long columnKey = ((long) pos.getX() << 32) ^ (pos.getZ() & 0xFFFFFFFFL);
                        NoiseColumn column = columns.computeIfAbsent(
                                columnKey,
                                ignored -> generator.getBaseColumn(pos.getX(), pos.getZ(), level, randomState)
                        );
                        return hostPredicate.test(pos, column.getBlock(pos.getY()));
                    }
            );
            return center == null ? null : candidate.withUndergroundCenter(center);
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Could not resolve underground fallback for ore-deposit candidate {} in {}",
                    candidate.depositId(), level.dimension().location(), exception
            );
            return null;
        }
    }

    /** Strict data-driven validation; intentionally separate from the #c:stones cache. */
    public ValidationResult validateMatching(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            Predicate<BlockState> hostPredicate
    ) {
        return validateMatching(level, candidate, layout, hostPredicate, ignored -> true);
    }

    public ValidationResult validateMatching(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            Predicate<BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed
    ) {
        return validateMatching(
                level, candidate, layout,
                (ignoredPos, state) -> hostPredicate.test(state), positionAllowed
        );
    }

    public ValidationResult validateMatching(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            BiPredicate<BlockPos, BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed
    ) {
        boolean standardLayout = DepositLayouts.forTier(candidate.tier()).equals(layout);
        if (!standardLayout) {
            throw new IllegalArgumentException("Candidate tier and validation layout do not match");
        }
        return evaluate(level, candidate, layout, hostPredicate, positionAllowed);
    }

    public ValidationResult validate(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout
    ) {
        boolean standardLayout = DepositLayouts.forTier(candidate.tier()).equals(layout);
        if (!standardLayout) {
            throw new IllegalArgumentException("Candidate tier and validation layout do not match");
        }
        DepositValidationKey key = new DepositValidationKey(
                level.dimension(), candidate.tier(), candidate.depositId()
        );
        ValidationResult cached = DepositValidationCache.get(key);
        if (cached != null) {
            return cached;
        }
        ValidationResult result = evaluate(
                level, candidate, layout,
                (ignoredPos, state) -> state.is(ModBlockTags.STONES) && state.getFluidState().isEmpty(),
                ignored -> true
        );
        DepositValidationCache.put(key, result);
        return result;
    }

    /** Server tags and datapacks have changed; all tag-derived catalogs and geology results are stale. */
    public static void onTagsUpdated(TagsUpdatedEvent event) {
        clearCache();
        OreTags.clearCache();
        OreUnifier.clearCache();
        OreDepositOrePalette.clearAvailableCache();
        OreDepositStonePalette.clearAvailableCache();
    }

    public static void clearCache() {
        DepositValidationCache.clear();
    }

    private ValidationResult evaluate(
            ServerLevel level,
            DepositCandidate candidate,
            DepositTierLayout layout,
            BiPredicate<BlockPos, BlockState> hostPredicate,
            Predicate<BlockPos> positionAllowed
    ) {
        ServerChunkCache chunkSource = level.getChunkSource();
        ChunkGenerator generator = chunkSource.getGenerator();
        RandomState randomState = chunkSource.randomState();
        List<SamplePoint> samples = sampler.createSamples(candidate, layout);
        int stones = 0;
        int checked = 0;
        boolean[] verticalCoverage = new boolean[3];
        boolean[] radialCoverage = new boolean[4];
        boolean[] sideCoverage = new boolean[4];
        Set<Integer> totalSections = new HashSet<>();
        Set<Integer> validSections = new HashSet<>();
        boolean centerStone = false;

        try {
            for (SamplePoint samplePoint : samples) {
                BlockPos sample = samplePoint.pos();
                totalSections.add(samplePoint.section());
                checked++;
                if (sample.getY() < level.getMinBuildHeight() || sample.getY() >= level.getMaxBuildHeight()) {
                    continue;
                }
                if (!positionAllowed.test(sample)) {
                    continue;
                }
                NoiseColumn column = generator.getBaseColumn(sample.getX(), sample.getZ(), level, randomState);
                if (!hostPredicate.test(sample, column.getBlock(sample.getY()))) {
                    continue;
                }
                stones++;
                validSections.add(samplePoint.section());
                centerStone |= sample.equals(candidate.center());
                verticalCoverage[verticalBand(candidate, sample)] = true;
                radialCoverage[radialBand(candidate, sample)] = true;
                sideCoverage[side(candidate, sample)] = true;
            }
        } catch (RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Could not predict terrain for ore-deposit candidate {} in {}",
                    candidate.depositId(), level.dimension().location(), exception
            );
            double coverage = checked == 0 ? 0.0D : stones / (double) checked;
            return new ValidationResult(
                    false, stones, checked, coverage, validSections.size(), totalSections.size()
            );
        }

        double stoneFraction = checked == 0 ? 0.0D : (double) stones / checked;
        int minimumSections = TieredDepositSampler.minimumValidSections(candidate.tier());
        boolean viable = stoneFraction >= layout.requiredStoneCoverage()
                && validSections.size() >= minimumSections
                && switch (candidate.tier()) {
                    case TINY -> centerStone && coveredBands(radialCoverage) >= 2;
                    case SMALL -> coveredBands(verticalCoverage) >= 2
                            && coveredBands(radialCoverage) >= 2
                            && coveredBands(sideCoverage) >= 2;
                    case MEDIUM -> coveredBands(verticalCoverage) >= 2
                            && coveredBands(radialCoverage) >= 3
                            && coveredBands(sideCoverage) >= 3;
                    case LARGE -> centerStone
                            && coveredBands(verticalCoverage) >= 3
                            && coveredBands(radialCoverage) >= 3
                            && coveredBands(sideCoverage) >= 3;
                };
        return new ValidationResult(
                viable,
                stones,
                checked,
                stoneFraction,
                validSections.size(),
                totalSections.size()
        );
    }

    private static int verticalBand(DepositCandidate candidate, BlockPos sample) {
        double normalized = (double) (candidate.center().getY() - sample.getY())
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

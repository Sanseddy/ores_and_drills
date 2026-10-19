package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Builds TINY/SMALL deposit veins with Minecraft's own {@link Feature#ORE}. The vanilla feature runs
 * unchanged (segment, spheres, overlap pruning, {@code RuleTest} targets, air-exposure discard, terrain
 * early-out and write bounds); {@code OreFeatureMixin} only diverts its single section write into
 * {@link #capture}, so the caller can turn exactly those positions into deposit blocks.
 */
public final class VanillaOreVein {
    private static final ThreadLocal<List<Captured>> CAPTURE = new ThreadLocal<>();

    private VanillaOreVein() {
    }

    /**
     * Runs one vanilla ore attempt at {@code origin} and returns the positions it would have filled, in
     * vanilla order, each with the target that accepted it. Nothing is written to the world.
     *
     * <p>The attempt is refused (empty result, {@code random} untouched) when the vein's footprint leaves the
     * decoration region's generated area. Vanilla only calls the feature with an origin in the decorated chunk,
     * so its footprint never does; mods hooking {@code OreFeature.place} (Cataclysm's structure exclusion asks
     * the origin chunk for its structure references) rely on that, and the feature itself would read empty
     * heightmaps and air from chunks whose terrain does not exist yet.
     */
    static List<OreDepositFeature.DepositPosition> collect(
            WorldGenLevel level,
            ChunkGenerator generator,
            RandomSource random,
            BlockPos origin,
            OreConfiguration config
    ) {
        if (!fitsGeneratedArea(level, origin, config.size)) {
            return List.of();
        }
        List<Captured> captured = new ArrayList<>();
        List<Captured> previous = CAPTURE.get();
        CAPTURE.set(captured);
        try {
            Feature.ORE.place(new FeaturePlaceContext<>(Optional.empty(), level, generator, random, origin, config));
        } finally {
            if (previous == null) {
                CAPTURE.remove();
            } else {
                CAPTURE.set(previous);
            }
        }

        List<OreDepositFeature.DepositPosition> positions = new ArrayList<>(captured.size());
        for (Captured entry : captured) {
            for (OreConfiguration.TargetBlockState target : config.targetStates) {
                if (target.state == entry.state()) {
                    positions.add(new OreDepositFeature.DepositPosition(entry.pos(), target));
                    break;
                }
            }
        }
        return positions;
    }

    /** Whether a vein of {@code size} at {@code origin} reads and writes only generated terrain. */
    static boolean fitsGeneratedArea(WorldGenLevel level, BlockPos origin, int size) {
        GenerationWriteBounds area = WorldgenReadArea.of(level);
        return area == null
                || area.containsSquare(origin.getX(), origin.getZ(), OreFeatureFootprint.horizontalReach(size));
    }

    /**
     * Called by {@code OreFeatureMixin} in place of {@code LevelChunkSection.setBlockState}.
     *
     * @return {@code true} when the write was captured and must not reach the chunk section
     */
    public static boolean capture(BlockPos pos, BlockState state) {
        List<Captured> captured = CAPTURE.get();
        if (captured == null) {
            return false;
        }
        captured.add(new Captured(pos.immutable(), state));
        return true;
    }

    private record Captured(BlockPos pos, BlockState state) {
    }
}

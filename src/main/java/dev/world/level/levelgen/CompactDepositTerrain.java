package dev.world.level.levelgen;

import dev.mixin.NoiseBasedChunkGeneratorAccessor;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.levelgen.Aquifer;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.blending.Blender;

/** Base-column semantics, batched by chunk/noise cell. Never loads or writes terrain chunks. */
final class CompactDepositTerrain {
    // The engine's no-structure marker is protected; obtain the singleton through its public unit
    // codec.
    private static final DensityFunctions.BeardifierOrMarker NO_STRUCTURES =
            (DensityFunctions.BeardifierOrMarker)
                    DensityFunctions.BeardifierOrMarker.CODEC
                            .codec()
                            .codec()
                            .parse(
                                    com.mojang.serialization.JsonOps.INSTANCE,
                                    new com.google.gson.JsonObject())
                            .getOrThrow();

    private CompactDepositTerrain() {}

    static BlockState[] sample(
            ChunkGenerator generator,
            RandomState random,
            LevelHeightAccessor height,
            BlockPos origin,
            List<CompactDepositGeometry.Cell> cells) {
        BlockState[] result = new BlockState[cells.size()];
        // A custom generator may override getBaseColumn: preserve its contract.
        if (generator.getClass() != NoiseBasedChunkGenerator.class) {
            return sampleColumns(generator, random, height, origin, cells);
        }
        NoiseBasedChunkGenerator noiseGenerator = (NoiseBasedChunkGenerator) generator;
        NoiseGeneratorSettings settings = noiseGenerator.generatorSettings().value();
        NoiseSettings full = settings.noiseSettings().clampToHeightAccessor(height);
        int cw = full.getCellWidth(), ch = full.getCellHeight();
        if (16 % cw != 0 || full.minY() % ch != 0)
            return sampleColumns(generator, random, height, origin, cells);
        int firstY = Math.floorDiv(full.minY(), ch), countY = Math.floorDiv(full.height(), ch);
        Map<Long, List<Integer>> chunks = new HashMap<>();
        java.util.Arrays.fill(result, Blocks.AIR.defaultBlockState());
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            int yCell = Math.floorDiv(origin.getY() + cell.y(), ch);
            if (yCell < firstY || yCell >= firstY + countY) continue;
            long key =
                    ChunkPos.asLong(
                            Math.floorDiv(origin.getX() + cell.x(), 16),
                            Math.floorDiv(origin.getZ() + cell.z(), 16));
            chunks.computeIfAbsent(key, ignored -> new ArrayList<>()).add(i);
        }
        Aquifer.FluidPicker fluids =
                ((NoiseBasedChunkGeneratorAccessor) noiseGenerator)
                        .oresAndDrills$globalFluidPicker()
                        .get();
        for (var entry : chunks.entrySet()) {
            List<Integer> indices = entry.getValue();
            int minCellY = Integer.MAX_VALUE, maxCellY = Integer.MIN_VALUE;
            for (int i : indices) {
                int cy = Math.floorDiv(origin.getY() + cells.get(i).y(), ch);
                minCellY = Math.min(minCellY, cy);
                maxCellY = Math.max(maxCellY, cy);
            }
            // Only interpolation samples covering the deposit are needed, not the world's full
            // height.
            NoiseSettings window =
                    new NoiseSettings(
                            minCellY * ch,
                            (maxCellY - minCellY + 1) * ch,
                            full.noiseSizeHorizontal(),
                            full.noiseSizeVertical());
            ChunkPos chunk = new ChunkPos(entry.getKey());
            int startX = chunk.getMinBlockX(), startZ = chunk.getMinBlockZ(), side = 16 / cw;
            Map<Integer, List<Integer>> buckets = new HashMap<>();
            for (int i : indices) {
                var cell = cells.get(i);
                int cx = (origin.getX() + cell.x() - startX) / cw;
                int cz = (origin.getZ() + cell.z() - startZ) / cw;
                int cy = Math.floorDiv(origin.getY() + cell.y(), ch) - minCellY;
                buckets.computeIfAbsent(
                                (cx * side + cz) * (maxCellY - minCellY + 1) + cy,
                                ignored -> new ArrayList<>())
                        .add(i);
            }
            Sampler sampler =
                    new Sampler(side, random, startX, startZ, window, full, settings, fluids);
            sampler.initializeForFirstCellX();
            try {
                for (int cx = 0; cx < side; cx++) {
                    sampler.advanceCellX(cx);
                    for (int cz = 0; cz < side; cz++)
                        for (int cy = maxCellY - minCellY; cy >= 0; cy--) {
                            List<Integer> bucket =
                                    buckets.get((cx * side + cz) * (maxCellY - minCellY + 1) + cy);
                            if (bucket == null) continue;
                            sampler.selectCellYZ(cy, cz);
                            for (int i : bucket) {
                                var cell = cells.get(i);
                                int x = origin.getX() + cell.x(),
                                        y = origin.getY() + cell.y(),
                                        z = origin.getZ() + cell.z();
                                sampler.updateForY(y, (double) Math.floorMod(y, ch) / ch);
                                sampler.updateForX(x, (double) Math.floorMod(x, cw) / cw);
                                sampler.updateForZ(z, (double) Math.floorMod(z, cw) / cw);
                                BlockState state = sampler.state();
                                result[i] = state == null ? settings.defaultBlock() : state;
                            }
                        }
                    sampler.swapSlices();
                }
            } finally {
                sampler.stopInterpolation();
            }
        }
        return result;
    }

    private static BlockState[] sampleColumns(
            ChunkGenerator generator,
            RandomState random,
            LevelHeightAccessor height,
            BlockPos origin,
            List<CompactDepositGeometry.Cell> cells) {
        BlockState[] result = new BlockState[cells.size()];
        Map<Long, NoiseColumn> columns = new HashMap<>();
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            int x = origin.getX() + cell.x(), z = origin.getZ() + cell.z();
            result[i] =
                    columns.computeIfAbsent(
                                    ChunkPos.asLong(x, z),
                                    key -> generator.getBaseColumn(x, z, height, random))
                            .getBlock(origin.getY() + cell.y());
        }
        return result;
    }

    private static final class Sampler extends NoiseChunk {
        private final java.util.function.Supplier<NoiseChunk> surface;

        Sampler(
                int side,
                RandomState random,
                int x,
                int z,
                NoiseSettings window,
                NoiseSettings full,
                NoiseGeneratorSettings settings,
                Aquifer.FluidPicker fluids) {
            super(side, random, x, z, window, NO_STRUCTURES, settings, fluids, Blender.empty());
            // Aquifers must search for the surface over the ORIGINAL world height, not the ore
            // window.
            surface =
                    com.google.common.base.Suppliers.memoize(
                            () ->
                                    new NoiseChunk(
                                            side,
                                            random,
                                            x,
                                            z,
                                            full,
                                            NO_STRUCTURES,
                                            settings,
                                            fluids,
                                            Blender.empty()));
        }

        @Override
        public int preliminarySurfaceLevel(int x, int z) {
            return surface.get().preliminarySurfaceLevel(x, z);
        }

        BlockState state() {
            return getInterpolatedState();
        }
    }
}

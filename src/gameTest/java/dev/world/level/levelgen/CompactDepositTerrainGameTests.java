package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.NoiseColumn;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.NoiseSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class CompactDepositTerrainGameTests {
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void batchedTerrainMatchesBaseColumnsIncludingAquifers(GameTestHelper helper) throws Exception {
        var registries = helper.getLevel().registryAccess();
        var biomeSource = helper.getLevel().getServer().getLevel(Level.NETHER).getChunkSource().getGenerator().getBiomeSource();
        int compared = 0, fluids = 0, air = 0;
        for (var key : List.of(NoiseGeneratorSettings.OVERWORLD, NoiseGeneratorSettings.NETHER, NoiseGeneratorSettings.END)) {
            var holder = registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(key);
            var generator = new NoiseBasedChunkGenerator(biomeSource, holder);
            var settings = holder.value().noiseSettings();
            var height = LevelHeightAccessor.create(settings.minY(), settings.height());
            for (long seed : new long[]{8696984497248679853L, -427L}) {
                var random = RandomState.create(holder.value(), registries.lookupOrThrow(Registries.NOISE), seed);
                List<CompactDepositGeometry.Cell> cells = new ArrayList<>();
                for (int x = -9; x <= 9; x++) for (int z = -9; z <= 9; z++) {
                    for (int y : new int[]{settings.minY() - 1, settings.minY(), Math.max(settings.minY(), -50),
                            10, 32, 63, 90, settings.minY() + settings.height() - 1, settings.minY() + settings.height()}) {
                        cells.add(new CompactDepositGeometry.Cell(x, y, z, 1));
                    }
                }
                // Covers negative chunks, zero and noise/chunk boundaries without requesting live chunks.
                BlockPos origin = new BlockPos(-1, 0, 15);
                BlockState[] actual = CompactDepositTerrain.sample(generator, random, height, origin, cells);
                BlockState[] expected = columns(generator, random, height, origin, cells);
                for (int i = 0; i < cells.size(); i++) {
                    helper.assertTrue(actual[i].equals(expected[i]), "Batched terrain changed " + key.location()
                            + " seed=" + seed + " at " + origin.offset(cells.get(i).x(), cells.get(i).y(), cells.get(i).z())
                            + ": " + expected[i] + " -> " + actual[i]);
                    compared++;
                    if (!actual[i].getFluidState().isEmpty()) fluids++;
                    if (actual[i].isAir()) air++;
                }
                var reversed = cells.reversed();
                var reverseStates = CompactDepositTerrain.sample(generator, random, height, origin, reversed);
                for (int i = 0; i < cells.size(); i++) helper.assertTrue(actual[i].equals(reverseStates[cells.size() - 1 - i]),
                        "Terrain sampling depends on cell order");
                // Exercise narrow Y windows, especially aquifer surface searches far above the window.
                for (int plane = 0; plane < 9; plane++) {
                    var planar = new ArrayList<CompactDepositGeometry.Cell>();
                    for (int i = plane; i < cells.size(); i += 9) planar.add(cells.get(i));
                    var states = CompactDepositTerrain.sample(generator, random, height, origin, planar);
                    for (int i = 0; i < states.length; i++) helper.assertTrue(states[i].equals(expected[i * 9 + plane]),
                            "Narrow terrain window changed " + key.location() + " plane=" + plane);
                    compared += states.length;
                }
            }
        }

        var holder = registries.registryOrThrow(Registries.NOISE_SETTINGS).getHolderOrThrow(NoiseGeneratorSettings.OVERWORLD);
        // Flooded settings guarantee liquid samples; also cover nonstandard 12-block noise cells (fallback).
        for (int width : new int[]{1, 3}) for (boolean aquifers : new boolean[]{true, false}) {
            var base = holder.value();
            var ns = base.noiseSettings();
            var flooded = new NoiseGeneratorSettings(new NoiseSettings(ns.minY(), ns.height(), width, ns.noiseSizeVertical()),
                    base.defaultBlock(), net.minecraft.world.level.block.Blocks.WATER.defaultBlockState(), base.noiseRouter(),
                    base.surfaceRule(), base.spawnTarget(), ns.minY() + ns.height(), false, aquifers, false, base.useLegacyRandomSource());
            var testGenerator = new NoiseBasedChunkGenerator(biomeSource, Holder.direct(flooded));
            var testRandom = RandomState.create(flooded, registries.lookupOrThrow(Registries.NOISE), -427);
            var testHeight = LevelHeightAccessor.create(-32, 160);
            var fixture = new ArrayList<CompactDepositGeometry.Cell>();
            for (int x = -4; x <= 4; x++) for (int z = -4; z <= 4; z++) for (int y : new int[]{-32, 40, 63, 90, 127, 128})
                fixture.add(new CompactDepositGeometry.Cell(x, y, z, 1));
            var actual = CompactDepositTerrain.sample(testGenerator, testRandom, testHeight, BlockPos.ZERO, fixture);
            var expected = columns(testGenerator, testRandom, testHeight, BlockPos.ZERO, fixture);
            helper.assertTrue(java.util.Arrays.equals(actual, expected), "Clamped/flooded terrain changed; width=" + width + " aquifers=" + aquifers);
            compared += actual.length;
            for (var state : actual) if (!state.getFluidState().isEmpty()) fluids++;
            for (int plane = 0; plane < 6; plane++) {
                var planar = new ArrayList<CompactDepositGeometry.Cell>();
                for (int i = plane; i < fixture.size(); i += 6) planar.add(fixture.get(i));
                var states = CompactDepositTerrain.sample(testGenerator, testRandom, testHeight, BlockPos.ZERO, planar);
                for (int i = 0; i < states.length; i++) helper.assertTrue(states[i].equals(expected[i * 6 + plane]),
                        "Narrow flooded window changed; width=" + width + " aquifers=" + aquifers + " plane=" + plane);
                compared += states.length;
            }
        }
        helper.assertTrue(fluids > 0 && air > 0, "Equivalence fixture missed liquids or air: " + fluids + "/" + air);
        var generator = new NoiseBasedChunkGenerator(biomeSource, holder);
        var random = RandomState.create(holder.value(), registries.lookupOrThrow(Registries.NOISE), 8696984497248679853L);
        var height = LevelHeightAccessor.create(holder.value().noiseSettings().minY(), holder.value().noiseSettings().height());
        var cells = new CompactDepositGeometry(14267, true, 1).cells();
        var origin = new BlockPos(-1031, 24, -2033);
        var bean = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory.getThreadMXBean();
        bean.setThreadAllocatedMemoryEnabled(true);
        long tid = Thread.currentThread().threadId();
        // Warm both paths; verify a full LARGE in addition to the sparse equivalence fixtures.
        var warm = CompactDepositTerrain.sample(generator, random, height, origin, cells);
        var expected = columns(generator, random, height, origin, cells);
        helper.assertTrue(java.util.Arrays.equals(warm, expected), "Full LARGE changed host terrain");
        StringBuilder csv = new StringBuilder("iteration,blocks,old_ns,new_ns,old_allocated_bytes,new_allocated_bytes\n");
        for (int repeat = 0; repeat < 3; repeat++) {
            long allocated = bean.getThreadAllocatedBytes(tid), start = System.nanoTime();
            expected = columns(generator, random, height, origin, cells);
            long oldNanos = System.nanoTime() - start, oldBytes = bean.getThreadAllocatedBytes(tid) - allocated;
            allocated = bean.getThreadAllocatedBytes(tid); start = System.nanoTime();
            var actual = CompactDepositTerrain.sample(generator, random, height, origin, cells);
            long newNanos = System.nanoTime() - start, newBytes = bean.getThreadAllocatedBytes(tid) - allocated;
            helper.assertTrue(java.util.Arrays.equals(actual, expected), "Benchmark changed terrain");
            csv.append(repeat).append(',').append(cells.size()).append(',').append(oldNanos).append(',').append(newNanos)
                    .append(',').append(oldBytes).append(',').append(newBytes).append('\n');
        }
        var report = java.nio.file.Path.of("../reports/factorio-deposits/terrain-batch-profile.csv");
        java.nio.file.Files.createDirectories(report.getParent());
        java.nio.file.Files.writeString(report, csv);
        System.out.printf("Batched terrain: %d exact matches, %d liquid and %d air samples%n%s", compared, fluids, air, csv);
        helper.succeed();
    }

    private static BlockState[] columns(NoiseBasedChunkGenerator generator, RandomState random, LevelHeightAccessor height,
                                       BlockPos origin, List<CompactDepositGeometry.Cell> cells) {
        var columns = new HashMap<Long, NoiseColumn>();
        BlockState[] result = new BlockState[cells.size()];
        for (int i = 0; i < cells.size(); i++) {
            var cell = cells.get(i);
            int x = origin.getX() + cell.x(), z = origin.getZ() + cell.z();
            result[i] = columns.computeIfAbsent(ChunkPos.asLong(x, z),
                    ignored -> generator.getBaseColumn(x, z, height, random)).getBlock(origin.getY() + cell.y());
        }
        return result;
    }
}

package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.FeaturePlaceContext;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class VanillaOreVeinGameTests {
    private static final int HALF_EXTENT = 8;

    @GameTest(template = "empty", timeoutTicks = 200)
    public static void depositVeinIsExactlyTheVanillaOreFeatureVein(GameTestHelper helper) {
        var level = helper.getLevel();
        var generator = level.getChunkSource().getGenerator();
        BlockPos origin = helper.absolutePos(new BlockPos(24, 12, 24));
        for (int size : new int[] {4, 9, 17}) {
            OreConfiguration config =
                    new OreConfiguration(
                            List.of(
                                    OreConfiguration.target(
                                            new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
                                            Blocks.IRON_ORE.defaultBlockState())),
                            size);
            for (long seed = 1; seed <= 24; seed++) {
                fillStone(level, origin);
                var captured =
                        VanillaOreVein.collect(
                                level, generator, RandomSource.create(seed), origin, config);
                Set<BlockPos> capturedPositions = new HashSet<>();
                for (var position : captured) {
                    helper.assertTrue(
                            capturedPositions.add(position.pos()), "Captured a position twice");
                    helper.assertTrue(
                            level.getBlockState(position.pos()).is(Blocks.STONE),
                            "Capturing a deposit vein wrote to the world at " + position.pos());
                }

                Feature.ORE.place(
                        new FeaturePlaceContext<>(
                                Optional.empty(),
                                level,
                                generator,
                                RandomSource.create(seed),
                                origin,
                                config));
                Set<BlockPos> vanillaPositions = new HashSet<>();
                BlockPos.betweenClosed(
                                origin.offset(-HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT),
                                origin.offset(HALF_EXTENT, HALF_EXTENT, HALF_EXTENT))
                        .forEach(
                                pos -> {
                                    if (level.getBlockState(pos).is(Blocks.IRON_ORE)) {
                                        vanillaPositions.add(pos.immutable());
                                    }
                                });
                helper.assertTrue(
                        vanillaPositions.equals(capturedPositions),
                        "Size "
                                + size
                                + " seed "
                                + seed
                                + ": deposit vein "
                                + capturedPositions.size()
                                + " blocks differs from vanilla "
                                + vanillaPositions.size());
            }
        }
        fillStone(level, origin);
        helper.succeed();
    }

    /**
     * The generated-area guard of {@link VanillaOreVein#collect} trusts {@link
     * OreFeatureFootprint}: every position the real feature fills, and the neighbour its air check
     * reads, must lie within that reach. Up to size 32 one block past the claimed reach is still
     * inside the stone fill, so an overrun is seen.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void footprintReachCoversEveryVanillaVeinPosition(GameTestHelper helper) {
        var level = helper.getLevel();
        var generator = level.getChunkSource().getGenerator();
        BlockPos origin = helper.absolutePos(new BlockPos(24, 12, 24));
        fillStone(level, origin);
        for (int size = 1; size <= 32; size++) {
            OreConfiguration config =
                    new OreConfiguration(
                            List.of(
                                    OreConfiguration.target(
                                            new TagMatchTest(BlockTags.STONE_ORE_REPLACEABLES),
                                            Blocks.IRON_ORE.defaultBlockState())),
                            size);
            int filledReach = OreFeatureFootprint.horizontalReach(size) - 1;
            int captured = 0;
            for (long seed = 1; seed <= 16; seed++) {
                for (var position :
                        VanillaOreVein.collect(
                                level, generator, RandomSource.create(seed), origin, config)) {
                    int dx = Math.abs(position.pos().getX() - origin.getX());
                    int dz = Math.abs(position.pos().getZ() - origin.getZ());
                    helper.assertTrue(
                            dx <= filledReach && dz <= filledReach,
                            "Size "
                                    + size
                                    + " seed "
                                    + seed
                                    + " filled "
                                    + position.pos()
                                    + " beyond the footprint reach "
                                    + filledReach);
                    captured++;
                }
            }
            // Veins of one to three blocks often fill nothing at all; larger ones must give the
            // check something.
            helper.assertTrue(
                    size < 4 || captured > 0, "Size " + size + " captured no vein to check");
        }
        helper.succeed();
    }

    @GameTest(template = "empty", timeoutTicks = 100)
    public static void depositBlocksTakeTheirHostRocksOreVariant(GameTestHelper helper) {
        var level = helper.getLevel();
        var palette = OreDepositPaletteData.get(level);
        int ironOre = palette.oreIndex(Blocks.IRON_ORE);
        int deepslateIronOre = palette.oreIndex(Blocks.DEEPSLATE_IRON_ORE);
        int stone = palette.baseIndex(Blocks.STONE);
        int deepslate = palette.baseIndex(Blocks.DEEPSLATE);
        helper.assertTrue(
                ironOre >= 0 && deepslateIronOre >= 0 && stone >= 0 && deepslate >= 0,
                "Vanilla iron ores or host rocks are missing from the deposit palette");
        BlockPos inDeepslate = helper.absolutePos(new BlockPos(1, 2, 1));
        BlockPos inStone = helper.absolutePos(new BlockPos(2, 2, 1));
        BlockPos deepslateOreInStone = helper.absolutePos(new BlockPos(3, 2, 1));
        OreDepositData.setGeneratedBatch(
                level,
                List.of(
                        new OreDepositData.GeneratedDeposit(
                                inDeepslate, deepslate, ironOre, 3, OreDepositFeature.TIER_TINY),
                        new OreDepositData.GeneratedDeposit(
                                inStone, stone, ironOre, 3, OreDepositFeature.TIER_TINY),
                        new OreDepositData.GeneratedDeposit(
                                deepslateOreInStone,
                                stone,
                                deepslateIronOre,
                                3,
                                OreDepositFeature.TIER_TINY)));
        helper.assertTrue(
                Blocks.DEEPSLATE_IRON_ORE == OreDepositData.oreBlockAt(level, inDeepslate),
                "Iron ore in deepslate did not become deepslate iron ore");
        helper.assertTrue(
                Blocks.IRON_ORE == OreDepositData.oreBlockAt(level, inStone),
                "Iron ore in stone changed its variant");
        helper.assertTrue(
                Blocks.IRON_ORE == OreDepositData.oreBlockAt(level, deepslateOreInStone),
                "Deepslate iron ore in stone did not become iron ore");
        helper.succeed();
    }

    private static void fillStone(net.minecraft.server.level.ServerLevel level, BlockPos origin) {
        BlockPos.betweenClosed(
                        origin.offset(-HALF_EXTENT, -HALF_EXTENT, -HALF_EXTENT),
                        origin.offset(HALF_EXTENT, HALF_EXTENT, HALF_EXTENT))
                .forEach(
                        pos ->
                                level.setBlock(
                                        pos,
                                        Blocks.STONE.defaultBlockState(),
                                        Block.UPDATE_CLIENTS));
    }
}

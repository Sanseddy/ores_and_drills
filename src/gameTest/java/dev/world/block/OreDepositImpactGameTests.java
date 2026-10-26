package dev.world.block;

import dev.OresAndDrillsMod;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositData;
import dev.world.level.levelgen.OreDepositOrePalette;
import dev.world.level.levelgen.OreDepositStonePalette;
import dev.world.level.levelgen.OreDepositTier;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * The game tests have no Sable, so each test stands in for a sub-level by collecting the veins in
 * the chunks of its own area and keeping only its own ores: deposits of parallel tests in the same
 * chunks must not take the hit.
 */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OreDepositImpactGameTests {
    private static final Block VEIN_ORE = Blocks.DEEPSLATE_COPPER_ORE;
    private static final Block OTHER_ORE = Blocks.DEEPSLATE_LAPIS_ORE;
    private static final Block SECOND_VEIN_ORE = Blocks.DEEPSLATE_REDSTONE_ORE;
    private static final Block CUBE_ORE = Blocks.DEEPSLATE_EMERALD_ORE;
    private static final Vec3 EAST = new Vec3(1.0D, 0.0D, 0.0D);
    private static final Vec3 DOWN = new Vec3(0.0D, -1.0D, 0.0D);

    /**
     * One hit is shared by all blocks of a physical vein, with the point of impact as epicentre:
     * a block there loses 8% of its own ore to a heavy hit, farther ones less. Another ore in the
     * same body is a vein of its own. Every tier included.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void impactsWearDownTheWholePhysicalVeinOfEveryTier(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        for (OreDepositTier tier : OreDepositTier.values()) {
            int z = 1 + tier.index();
            BlockPos first = deposit(helper, new BlockPos(1, 1, z), VEIN_ORE, 50, tier);
            BlockPos second = deposit(helper, new BlockPos(2, 1, z), VEIN_ORE, 100, tier);
            BlockPos third = deposit(helper, new BlockPos(3, 1, z), VEIN_ORE, 50, tier);
            BlockPos other = deposit(helper, new BlockPos(4, 1, z), OTHER_ORE, 50, tier);
            var body = body(level, tier, List.of(VEIN_ORE, OTHER_ORE), first, second, third, other);

            // Heading towards +x: the vein's third block is where it hits. Weights 1, 0.9 and
            // 0.69 make 4, 7.2 and 2.77 units, the fractions lost by chance.
            OreDepositImpactDamage.onBodyImpact(
                    level, bodyId(tier), OreDepositImpactMath.HEAVY_IMPACT_SPEED, EAST, body);
            int firstLost = 50 - OreDepositData.remainingOreAt(level, first);
            int secondLost = 100 - OreDepositData.remainingOreAt(level, second);
            int thirdLost = 50 - OreDepositData.remainingOreAt(level, third);
            helper.assertTrue(
                    thirdLost == 4
                            && (secondLost == 7 || secondLost == 8)
                            && (firstLost == 2 || firstLost == 3),
                    "The " + tier + " vein did not share the hit around its epicentre");
            helper.assertTrue(
                    OreDepositData.remainingOreAt(level, other) == 46,
                    "Another ore in the " + tier + " body did not take the hit as its own vein");

            int left = veinLeft(level, first, second, third);
            OreDepositImpactDamage.onBodyImpact(
                    level, bodyId(tier), OreDepositImpactMath.HEAVY_IMPACT_SPEED, EAST, body);
            helper.assertTrue(
                    veinLeft(level, first, second, third) == left,
                    "A " + tier + " vein lost ore again to the same impact");
        }
        helper.succeed();
    }

    /**
     * Seen from above, a vein wears down from its outline in x/z inwards, every layer of a column
     * alike, and its centre column is left alone. An exhausted column stays part of the outline
     * and keeps covering the centre behind it.
     */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void veinErodesFromItsOutlineAndKeepsItsCentre(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        OreDepositTier tier = OreDepositTier.MEDIUM;
        List<BlockPos> cube = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int y = 1; y <= 3; y++) {
                for (int z = 1; z <= 3; z++) {
                    cube.add(deposit(helper, new BlockPos(x, y, z), CUBE_ORE, 1_000, tier));
                }
            }
        }
        List<BlockPos> exhaustedColumn = new ArrayList<>();
        List<BlockPos> centreColumn = new ArrayList<>();
        for (int y = 1; y <= 3; y++) {
            exhaustedColumn.add(helper.absolutePos(new BlockPos(2, y, 1)));
            centreColumn.add(helper.absolutePos(new BlockPos(2, y, 2)));
        }
        exhaustedColumn.forEach(pos -> OreDepositData.depleteByImpact(level, pos, 1_000));
        var body = body(level, tier, List.of(CUBE_ORE), cube.toArray(BlockPos[]::new));

        OreDepositImpactDamage.onBodyImpact(
                level, 11L, OreDepositImpactMath.HEAVY_IMPACT_SPEED, DOWN, body);
        helper.assertTrue(
                exhaustedColumn.stream()
                        .allMatch(
                                pos ->
                                        level.getBlockState(pos)
                                                .is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get())),
                "The exhausted column did not stay in the vein");
        helper.assertTrue(
                veinLeft(level, centreColumn.toArray(BlockPos[]::new)) == 3_000,
                "The centre column lost ore while the outline still held some");
        // However much the vein holds, no block loses more than 8% of its own ore to one hit.
        helper.assertTrue(
                cube.stream()
                        .filter(pos -> !exhaustedColumn.contains(pos))
                        .mapToInt(pos -> OreDepositData.remainingOreAt(level, pos))
                        .allMatch(left -> left >= 920),
                "A block lost more than 8% of its ore to one hit");
        // Falling onto its underside, the bottom of the outline is nearer the impact than its top.
        helper.assertTrue(
                veinLeft(level, outlineLayer(helper, 1)) < veinLeft(level, outlineLayer(helper, 3)),
                "The underside did not take more of a fall than the top");
        helper.succeed();
    }

    /** The outline blocks with ore in one layer of the cube above, seen from the test's corner. */
    private static BlockPos[] outlineLayer(GameTestHelper helper, int y) {
        List<BlockPos> layer = new ArrayList<>();
        for (int x = 1; x <= 3; x++) {
            for (int z = 1; z <= 3; z++) {
                boolean centre = x == 2 && z == 2;
                boolean exhausted = x == 2 && z == 1;
                if (!centre && !exhausted) {
                    layer.add(helper.absolutePos(new BlockPos(x, y, z)));
                }
            }
        }
        return layer.toArray(BlockPos[]::new);
    }

    /** A crash can take a few ticks to stop: a stronger reading only adds the difference. */
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void strongerReadingOfTheSameImpactAddsOnlyTheDifference(GameTestHelper helper) {
        ServerLevel level = helper.getLevel();
        OreDepositTier tier = OreDepositTier.LARGE;
        // A single block is its own epicentre, so the numbers are exact.
        BlockPos first = deposit(helper, new BlockPos(1, 1, 1), SECOND_VEIN_ORE, 5_000, tier);
        var body = body(level, tier, List.of(SECOND_VEIN_ORE), first);

        OreDepositImpactDamage.onBodyImpact(
                level, 7L, OreDepositImpactMath.LIGHT_IMPACT_SPEED, DOWN, body);
        helper.assertTrue(veinLeft(level, first) == 4_950, "A light hit did not take 1%");
        OreDepositImpactDamage.onBodyImpact(
                level, 7L, OreDepositImpactMath.HEAVY_IMPACT_SPEED, DOWN, body);
        helper.assertTrue(
                veinLeft(level, first) == 4_600,
                "A heavier reading of the same impact did not bring the loss up to 8%");

        helper.runAfterDelay(
                OreDepositImpactDamage.IMMUNITY_TICKS,
                () -> {
                    OreDepositImpactDamage.onBodyImpact(
                            level, 7L, OreDepositImpactMath.HEAVY_IMPACT_SPEED, DOWN, body);
                    helper.assertTrue(
                            veinLeft(level, first) == 4_200,
                            "A new impact did not cost another 8% of the block's ore");

                    helper.assertTrue(
                            OreDepositData.depleteByImpact(level, first, 10_000)
                                    && level.getBlockState(first)
                                            .is(ModBlocks.EXHAUSTED_ORE_DEPOSIT.get()),
                            "A deposit emptied by impacts did not become exhausted");
                    helper.succeed();
                });
    }

    private static BlockPos deposit(
            GameTestHelper helper, BlockPos relative, Block ore, int amount, OreDepositTier tier) {
        ServerLevel level = helper.getLevel();
        BlockPos pos = helper.absolutePos(relative);
        level.setBlock(pos, ModBlocks.ORE_DEPOSIT.get().defaultBlockState(), Block.UPDATE_ALL);
        OreDepositData.set(
                level,
                pos,
                OreDepositStonePalette.indexOf(level, Blocks.STONE),
                OreDepositOrePalette.indexOf(level, ore),
                amount,
                0,
                0,
                tier.index());
        return pos;
    }

    /** This test's veins in the chunks around its deposits, freshly read at every impact. */
    private static Supplier<List<OreDepositData.PhysicalVein>> body(
            ServerLevel level, OreDepositTier tier, List<Block> ores, BlockPos... positions) {
        Set<ChunkAccess> chunks = new LinkedHashSet<>();
        for (BlockPos pos : positions) {
            chunks.add(level.getChunkAt(pos));
        }
        Set<OreDepositData.VeinIdentity> own = new HashSet<>();
        for (Block ore : ores) {
            own.add(
                    new OreDepositData.VeinIdentity(
                            OreDepositOrePalette.indexOf(level, ore), tier.index()));
        }
        return () ->
                OreDepositData.physicalVeins(level, chunks).stream()
                        .filter(vein -> own.contains(vein.identity()))
                        .toList();
    }

    private static int veinLeft(ServerLevel level, BlockPos... positions) {
        int left = 0;
        for (BlockPos pos : positions) {
            left += OreDepositData.remainingOreAt(level, pos);
        }
        return left;
    }

    private static long bodyId(OreDepositTier tier) {
        return 100L + tier.index();
    }
}

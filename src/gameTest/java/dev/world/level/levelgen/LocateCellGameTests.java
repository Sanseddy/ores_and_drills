package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LocateCellGameTests {
    /**
     * /locate walks cells around the player; a cell must yield its own candidates. Swapped anchor
     * axes once returned the cell mirrored across x = z, so far from that diagonal every
     * MEDIUM/LARGE search came up empty. Each candidate must also be validated the way worldgen
     * will place it.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void locateCandidatesBelongToTheirCell(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        int checked = 0;
        for (OreDepositTier tier :
                new OreDepositTier[] {OreDepositTier.MEDIUM, OreDepositTier.LARGE}) {
            var resolver = DepositResolvers.forTier(tier);
            int cellChunks = resolver.layout().cellSizeChunks();
            for (String material : new String[] {"quartz", "gold"}) {
                ResourceLocation requested =
                        DepositCandidateResolver.oreForTag(
                                        ResourceLocation.parse("c:ores/" + material))
                                .orElseThrow();
                var block = BuiltInRegistries.BLOCK.get(requested);
                ResourceLocation ore =
                        BuiltInRegistries.BLOCK.getKey(
                                OreUnifier.canonicalMaterialBlockFor(block).orElse(block));
                // Near the origin, and far from the diagonal where a mirrored cell is hundreds of
                // thousands of blocks away.
                for (int[] base :
                        new int[][] {
                            {-6, -6}, {300_000 / (cellChunks * 16), 100_000 / (cellChunks * 16)}
                        }) {
                    for (int cellX = base[0]; cellX < base[0] + 12; cellX++) {
                        for (int cellZ = base[1]; cellZ < base[1] + 12; cellZ++) {
                            for (DepositCandidate candidate :
                                    resolver.getCandidatesForCell(nether, cellX, cellZ, ore)) {
                                checked++;
                                helper.assertTrue(
                                        Math.floorDiv(candidate.sourceChunkX(), cellChunks) == cellX
                                                && Math.floorDiv(
                                                                candidate.sourceChunkZ(),
                                                                cellChunks)
                                                        == cellZ,
                                        tier
                                                + " "
                                                + material
                                                + " cell "
                                                + cellX
                                                + ","
                                                + cellZ
                                                + " returned source chunk "
                                                + candidate.sourceChunkX()
                                                + ","
                                                + candidate.sourceChunkZ());
                                // LARGE validation requires stone at the exact center, which must
                                // therefore be sampled.
                                helper.assertTrue(
                                        new TieredDepositSampler()
                                                        .createSamples(candidate, resolver.layout())
                                                        .stream()
                                                        .anyMatch(
                                                                sample ->
                                                                        sample.pos()
                                                                                .equals(
                                                                                        candidate
                                                                                                .center())),
                                        tier
                                                + " "
                                                + material
                                                + " validation does not sample the center "
                                                + candidate.center());
                                // Worldgen fits the lens height before planning; locate must
                                // already report that center.
                                var fitted =
                                        OreDepositFeature.fitCompactCandidate(
                                                nether, tier.index(), null, candidate);
                                helper.assertTrue(
                                        fitted != null
                                                && fitted.center().equals(candidate.center()),
                                        tier
                                                + " "
                                                + material
                                                + " candidate "
                                                + candidate.center()
                                                + " is not height-fitted like worldgen");
                            }
                        }
                    }
                }
            }
        }
        helper.assertTrue(checked > 0, "No MEDIUM/LARGE candidates to check");
        helper.succeed();
    }
}

package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import java.util.List;
import java.util.Optional;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** The seismic forecast must predict exactly what worldgen's own prediction does. */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SeismicForecastGameTests {
    private static final int REMOTE = 3_200_000;

    /**
     * One lottery draw per anchor chunk must select the very material for which {@code
     * predictsMaterialForChunk} — the check worldgen and /locate share — answers yes, and no other.
     */
    @GameTest(template = "empty", timeoutTicks = 400)
    public static void forecastSelectsTheMaterialWorldgenPredicts(GameTestHelper helper) {
        ServerLevel level = helper.getLevel().getServer().getLevel(Level.NETHER);
        helper.assertTrue(level != null, "Missing the noise-generated test dimension");
        ResourceLocation dimension = level.dimension().location();
        int checked = 0;
        int selected = 0;
        int compared = 0;
        for (OreDepositTier tier : SeismicDepositForecast.TIERS) {
            int cellBlocks = DepositLayouts.forTier(tier).cellSizeBlocks();
            int baseX = Math.floorDiv(REMOTE, cellBlocks);
            int baseZ = Math.floorDiv(-REMOTE, cellBlocks);
            for (int cellX = baseX; cellX < baseX + 14; cellX++) {
                for (int cellZ = baseZ; cellZ < baseZ + 14; cellZ++) {
                    long anchor = DepositLayouts.anchorChunkKey(tier, cellX, cellZ);
                    int chunkX = DepositLayouts.chunkX(anchor);
                    int chunkZ = DepositLayouts.chunkZ(anchor);
                    Optional<OreDepositFeature.SelectedMaterial> choice =
                            OreDepositFeature.predictedCompactMaterial(
                                    level, chunkX, chunkZ, tier.index());
                    ResourceLocation biome =
                            OreDepositFeature.automaticSourceBiomeId(level, chunkX, chunkZ);
                    for (OreGenerationWeights.OreGenerationData entry :
                            OreGenerationWeights.eligibleEntries(dimension, biome)) {
                        boolean worldgen =
                                OreDepositFeature.predictsMaterialForChunk(
                                        level,
                                        chunkX,
                                        chunkZ,
                                        tier.index(),
                                        entry.materialKey(),
                                        biome);
                        boolean forecast =
                                choice.isPresent()
                                        && choice.get().materialKey().equals(entry.materialKey());
                        helper.assertTrue(
                                worldgen == forecast,
                                tier
                                        + " cell "
                                        + cellX
                                        + ","
                                        + cellZ
                                        + ": forecast and worldgen disagree on "
                                        + entry.materialKey());
                        checked++;
                    }
                    if (choice.isEmpty()) {
                        continue;
                    }
                    selected++;
                    // The coarse forecast is the exact plan before it is moved into its source
                    // biome: same deposit identity source, same ore and tier, nearby centre.
                    List<DepositCandidate> coarse =
                            SeismicDepositForecast.predict(
                                    level, tier, cellX, cellZ, List.of(), false);
                    List<DepositCandidate> exact =
                            DepositResolvers.forTier(tier)
                                    .getCandidatesForCell(
                                            level, cellX, cellZ, choice.get().oreId());
                    if (!coarse.isEmpty() && !exact.isEmpty() && compared < 12) {
                        DepositCandidate a = coarse.getFirst();
                        DepositCandidate b = exact.getFirst();
                        helper.assertTrue(
                                a.oreId().equals(b.oreId())
                                        && a.tier() == b.tier()
                                        && a.sourceChunkX() == b.sourceChunkX()
                                        && a.sourceChunkZ() == b.sourceChunkZ()
                                        && Math.abs(a.center().getX() - b.center().getX())
                                                <= OreDepositFeature
                                                        .MAX_TAGGED_STONE_RELOCATION_RADIUS
                                        && Math.abs(a.center().getZ() - b.center().getZ())
                                                <= OreDepositFeature
                                                        .MAX_TAGGED_STONE_RELOCATION_RADIUS,
                                "Coarse forecast drifted from the exact plan");
                        compared++;
                    }
                }
            }
        }
        System.out.printf(
                "Seismic forecast: %d material checks, %d cells with a deposit, %d compared%n",
                checked, selected, compared);
        helper.assertTrue(checked > 0 && selected > 0, "No Nether cell exercised the lottery");
        helper.succeed();
    }
}

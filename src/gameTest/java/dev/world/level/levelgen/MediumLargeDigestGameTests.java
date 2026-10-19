package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Regression fingerprint of MEDIUM/LARGE planning. It only uses APIs that the TINY/SMALL rework does not
 * touch, so the same test runs before and after that change and the two reports must be identical.
 */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class MediumLargeDigestGameTests {
    private static final int CELL_RADIUS = 12;
    private static final int PLAN_RADIUS = 2;

    @GameTest(template = "empty", timeoutTicks = 2400)
    public static void mediumLargePlansFingerprint(GameTestHelper helper) throws Exception {
        List<String> lines = new ArrayList<>();
        for (var dimension : List.of(Level.OVERWORLD, Level.NETHER)) {
            ServerLevel level = helper.getLevel().getServer().getLevel(dimension);
            helper.assertTrue(level != null, "Missing dimension " + dimension.location());
            lines.add("dimension=" + dimension.location() + " seed=" + level.getSeed());
            List<OreDepositFeature.Configuration> configs = new ArrayList<>(
                    ReplaceOreFeaturesBiomeModifier.compactConfigurations());
            configs.sort(Comparator.comparing(MediumLargeDigestGameTests::configKey));
            for (OreDepositFeature.Configuration config : configs) {
                lines.add("config " + configKey(config));
            }
            for (int tier = OreDepositFeature.TIER_MEDIUM; tier <= OreDepositFeature.TIER_LARGE; tier++) {
                OreDepositTier publicTier = OreDepositTier.fromIndex(tier).orElseThrow();
                List<String> materials = configs.stream()
                        .filter(config -> config.sizeTier() == publicTier.index())
                        .map(config -> OreUnifier.materialKeyFor(config.targets().getFirst().state.getBlock()))
                        .distinct().sorted().toList();
                long hash = 0x9E3779B97F4A7C15L;
                int predicted = 0;
                for (int cx = -CELL_RADIUS; cx <= CELL_RADIUS; cx++) {
                    for (int cz = -CELL_RADIUS; cz <= CELL_RADIUS; cz++) {
                        long anchor = DepositLayouts.anchorChunkKey(publicTier, cx, cz);
                        int ax = DepositLayouts.chunkX(anchor), az = DepositLayouts.chunkZ(anchor);
                        for (String material : materials) {
                            if (!OreDepositFeature.predictsMaterialForChunk(level, ax, az, tier, material)) continue;
                            predicted++;
                            var ore = configs.stream()
                                    .filter(config -> config.sizeTier() == publicTier.index())
                                    .map(config -> config.targets().getFirst().state.getBlock())
                                    .filter(block -> OreUnifier.materialKeyFor(block).equals(material))
                                    .findFirst().orElseThrow();
                            var candidate = OreDepositFeature.createPlannedCandidate(level, ax, az, tier,
                                    BuiltInRegistries.BLOCK.getKey(ore), material, 1.0F);
                            hash = SeedMixer.mix(hash ^ candidate.depositId() ^ candidate.center().asLong()
                                    ^ ((long) candidate.radiusX() << 40) ^ ((long) candidate.radiusZ() << 20)
                                    ^ candidate.expectedBlocks());
                        }
                    }
                }
                lines.add("tier=" + publicTier.serializedName() + " predicted=" + predicted
                        + " hash=" + Long.toUnsignedString(hash));
            }
            if (dimension == Level.NETHER) {
                // Physical plans read real base terrain; keep the sample small but cover both tiers.
                for (OreDepositFeature.Configuration config : configs) {
                    long hash = 0;
                    int plans = 0;
                    for (int cx = -PLAN_RADIUS; cx <= PLAN_RADIUS; cx++) {
                        for (int cz = -PLAN_RADIUS; cz <= PLAN_RADIUS; cz++) {
                            var plan = OreDepositFeature.cachedCompactPlan(level, config, cx, cz);
                            if (plan.isEmpty()) continue;
                            plans++;
                            hash = SeedMixer.mix(hash ^ plan.get().candidate().depositId()
                                    ^ plan.get().candidate().center().asLong()
                                    ^ ((long) plan.get().cells().size() << 32) ^ plan.get().totalOre());
                        }
                    }
                    if (plans > 0) {
                        lines.add("plans " + configKey(config) + " count=" + plans + " hash=" + Long.toUnsignedString(hash));
                    }
                }
            }
        }
        Path report = Path.of("medium-large-digest.txt");
        Files.write(report, lines, StandardCharsets.UTF_8);
        OresAndDrillsMod.LOGGER.info("MEDIUM/LARGE digest written to {} ({} lines)", report.toAbsolutePath(), lines.size());
        helper.succeed();
    }

    private static String configKey(OreDepositFeature.Configuration config) {
        return "tier=" + config.sizeTier()
                + " biome=" + config.sourceBiome().map(Object::toString).orElse("-")
                + " targets=" + config.targets().stream()
                        .map(target -> String.valueOf(BuiltInRegistries.BLOCK.getKey(target.state.getBlock())))
                        .sorted().toList()
                + " freq=" + config.frequencyMultiplier() + " size=" + config.sizeMultiplier()
                + " rich=" + config.richnessMultiplier();
    }
}

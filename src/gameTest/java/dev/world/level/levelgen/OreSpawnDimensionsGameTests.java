package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.Feature;
import net.minecraft.world.level.levelgen.feature.LakeFeature;
import net.minecraft.world.level.levelgen.feature.stateproviders.BlockStateProvider;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OreSpawnDimensionsGameTests {
    @SuppressWarnings("deprecation")
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void nonOreFeatureRevealsTheOreItPlaces(GameTestHelper helper) {
        var access = helper.getLevel().registryAccess();
        var scanner = new OreSpawnDimensions.PlacedOreScanner(access);

        // A lake lined with an ore: no ore configuration, the ore only appears in a state provider.
        ConfiguredFeature<?, ?> lake =
                new ConfiguredFeature<>(
                        Feature.LAKE,
                        new LakeFeature.Configuration(
                                BlockStateProvider.simple(Blocks.WATER),
                                BlockStateProvider.simple(Blocks.IRON_ORE)));
        var lakeOres = scanner.oreBlocks(lake);
        helper.assertTrue(
                lakeOres.contains(Blocks.IRON_ORE), "Ore lining a lake was not found: " + lakeOres);
        helper.assertFalse(
                lakeOres.contains(Blocks.WATER), "A non-ore block was reported as an ore");

        // A real data-driven feature resolved from the registry, through RegistryOps.
        var configured = access.registryOrThrow(Registries.CONFIGURED_FEATURE);
        var ironOre = configured.get(ResourceLocation.withDefaultNamespace("ore_iron"));
        helper.assertTrue(
                ironOre != null && scanner.oreBlocks(ironOre).contains(Blocks.IRON_ORE),
                "minecraft:ore_iron does not report iron ore");

        // Eternal Starlight, when present: thermal springstone only lines its hot springs.
        if (ModList.get().isLoaded("eternal_starlight")) {
            var hotSpring =
                    configured.get(
                            ResourceLocation.fromNamespaceAndPath(
                                    "eternal_starlight", "hot_spring"));
            helper.assertTrue(hotSpring != null, "eternal_starlight:hot_spring is not registered");
            var thermal =
                    BuiltInRegistries.BLOCK.get(
                            ResourceLocation.fromNamespaceAndPath(
                                    "eternal_starlight", "thermal_springstone"));
            helper.assertTrue(
                    scanner.oreBlocks(hotSpring).contains(thermal),
                    "eternal_starlight:hot_spring does not report thermal springstone");
        }
        helper.succeed();
    }
}

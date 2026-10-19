package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class OreTagsGameTests {
    @GameTest(template = "empty", timeoutTicks = 100)
    public static void oresTaggedOnlyThroughTheirItemAreOres(GameTestHelper helper) {
        helper.assertTrue(OreTags.isOre(Blocks.IRON_ORE.defaultBlockState()), "Iron ore is not an ore");
        helper.assertTrue(OreTags.oreBlocks().contains(Blocks.IRON_ORE), "Iron ore is missing from the ore catalog");
        helper.assertTrue("iron".equals(OreUnifier.materialKeyFor(Blocks.IRON_ORE)), "Iron ore is not iron");
        helper.assertFalse(OreTags.isOre(Blocks.STONE.defaultBlockState()), "Stone was taken for an ore");

        // Create: Crowns tags its uranium ores only as items (c:ores/uranium), never as blocks.
        if (ModList.get().isLoaded("crowns")) {
            for (String path : new String[] {"uranium_ore", "deepslate_uranium_ore"}) {
                var block = BuiltInRegistries.BLOCK.get(ResourceLocation.fromNamespaceAndPath("crowns", path));
                helper.assertTrue(OreTags.isOre(block.defaultBlockState()), "crowns:" + path + " is not an ore");
                helper.assertTrue(OreTags.oreBlocks().contains(block), "crowns:" + path + " is missing from the catalog");
                helper.assertTrue("uranium".equals(OreUnifier.materialKeyFor(block)),
                        "crowns:" + path + " has material " + OreUnifier.materialKeyFor(block));
            }
        }
        helper.succeed();
    }
}

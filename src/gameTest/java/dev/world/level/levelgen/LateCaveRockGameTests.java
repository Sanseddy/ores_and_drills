package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/** Enable with -PalexCavesGameTests; uses the installed mod's real blocks, rules and region lookup. */
@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class LateCaveRockGameTests {
    @GameTest(template="empty",timeoutTicks=400)
    public static void uraniumPlansAcceptPrecursorButPlacementRequiresRadrock(GameTestHelper helper) {
        ResourceLocation hostId=ResourceLocation.parse("alexscaves:radrock");
        if(!BuiltInRegistries.BLOCK.containsKey(hostId)) {
            System.out.println("Alex's Caves late-rock integration fixture skipped: optional mod is absent");
            helper.succeed();return;
        }
        var biome=ResourceLocation.parse("alexscaves:toxic_caves");
        var rules=helper.getLevel().registryAccess().registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS);
        var actualRule=rules.get(ResourceLocation.parse("ores_and_drills:alexscaves_toxic_caves_uranium"));
        helper.assertTrue(actualRule instanceof DataDrivenOreDepositBiomeModifier,"Actual uranium JSON was not loaded");
        var configured=(DataDrivenOreDepositBiomeModifier)actualRule;
        helper.assertTrue(configured.sizes().get("large").forced() && configured.sizes().get("medium").forced(),"Forced configuration changed");
        // Stable test-only rule preserves the radrock regression when the user edits their real config.
        var rule=(DataDrivenOreDepositBiomeModifier)rules.get(ResourceLocation.parse("ores_and_drills:test_late_radrock"));
        helper.assertTrue(rule!=null,"Missing controlled radrock regression fixture");
        Block host=BuiltInRegistries.BLOCK.get(hostId);
        Block ore=OreUnifier.canonicalMaterialBlockFor(BuiltInRegistries.BLOCK.get(ResourceLocation.parse("alexscaves:radrock_uranium_ore")))
                .orElseThrow();
        var terrain=helper.getLevel().getServer().getLevel(Level.NETHER);
        BlockPos region=OptionalBiomeRegionHints.findClosest(terrain,new BlockPos(0,112,0),16384,Set.of(biome));
        helper.assertTrue(region!=null,"Could not find a real Toxic Caves seed region");
        for(boolean large:new boolean[]{false,true}) {
            int tier=large?OreDepositFeature.TIER_LARGE:OreDepositFeature.TIER_MEDIUM;
            long seed=large?14267:19;
            var geometry=new CompactDepositGeometry(seed,large,1);
            BlockPos origin=region.offset(large?0:96,0,0);
            var settings=new OreDepositFeature.DataDrivenSettings(List.of("minecraft:overworld"),-64,48,
                    OreDepositFeature.PlacementMode.UNDERGROUND,true,1,0,rule.stableSignature(),1,1,1);
            var target=OreConfiguration.target(new BlockMatchTest(host),ore.defaultBlockState());
            var config=new OreDepositFeature.Configuration(List.of(target),tier,1,1,1,1,Optional.of(biome),Optional.of(settings));
            helper.assertTrue(DataDrivenOreDepositBiomeModifier.lateReplacementTargets(terrain,config).size()==1,
                    "Loaded rule did not expose its late radrock target");
            var candidate=new DepositCandidate(SeedMixer.mix(helper.absolutePos(BlockPos.ZERO).asLong()^seed),
                    BuiltInRegistries.BLOCK.getKey(ore),large?OreDepositTier.LARGE:OreDepositTier.MEDIUM,origin,
                    geometry.radiusX(),geometry.radiusZ(),geometry.radiusY(),seed,null,geometry.cells().size(),origin.getX()>>4,origin.getZ()>>4);
            // Nether roof supplies vanilla noise stone; the fixture then simulates the cave mod's radrock conversion.
            var plan=OreDepositFeature.buildCompactPlanAt(terrain,config,candidate);
            helper.assertTrue(plan!=null,"Radrock-only plan rejected the precursor noise stone");
            origin=plan.candidate().center();
            var level=helper.getLevel();
            for(var cell:plan.cells())level.setBlock(origin.offset(cell.x(),cell.y(),cell.z()),Blocks.STONE.defaultBlockState(),Block.UPDATE_NONE);
            var chunks=plan.slices().keySet().stream().map(ChunkPos::new).sorted(java.util.Comparator.comparingLong(ChunkPos::toLong)).toList();
            for(var chunk:chunks)OreDepositFeature.placeCompactSlice(level,chunk,plan,false);
            for(var cell:plan.cells())helper.assertTrue(level.getBlockState(origin.offset(cell.x(),cell.y(),cell.z())).is(Blocks.STONE),
                    "Planning fallback incorrectly permitted stone placement");
            for(var cell:plan.cells())level.setBlock(origin.offset(cell.x(),cell.y(),cell.z()),host.defaultBlockState(),Block.UPDATE_NONE);
            for(var chunk:chunks.reversed())OreDepositFeature.placeCompactSlice(level,chunk,plan,false);
            int total=0;
            for(var cell:plan.cells()) {
                BlockPos pos=origin.offset(cell.x(),cell.y(),cell.z());
                helper.assertTrue(level.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get()),"Radrock slice was not placed at "+pos);
                var entry=level.getChunkAt(pos).getData(ModAttachments.ORE_DEPOSITS).get(pos);
                helper.assertTrue(entry!=null,"Missing uranium reserve");total+=entry.remainingOre();
            }
            helper.assertTrue(total==plan.totalOre(),"Late rock conversion lost or duplicated resources");
            System.out.printf("Alex's Caves uranium %s: %d blocks, %d units, %d chunks; precursor allowed only for planning%n",
                    large?"LARGE":"MEDIUM",plan.cells().size(),total,chunks.size());
        }
        helper.succeed();
    }
}

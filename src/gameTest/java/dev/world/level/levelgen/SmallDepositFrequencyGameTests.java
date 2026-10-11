package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

import java.util.List;

@GameTestHolder(OresAndDrillsMod.MOD_ID)
@PrefixGameTestTemplate(false)
public final class SmallDepositFrequencyGameTests {
    @GameTest(template = "empty", timeoutTicks = 200)
    public static void frequencyRulesThinSmallTiersDeterministicallyAndKeepForcedCounts(GameTestHelper helper) {
        var level=helper.getLevel();
        var biome=ResourceLocation.withDefaultNamespace("plains");
        var ore=ResourceLocation.withDefaultNamespace("iron_ore");
        var normal=new OreDepositFeature.DataDrivenSettings(List.of(level.dimension().location().toString()),-64,80,
                OreDepositFeature.PlacementMode.UNDERGROUND,false,1,0,0x52415245534D414CL,1,1,1);
        var forced=new OreDepositFeature.DataDrivenSettings(List.of(level.dimension().location().toString()),-64,80,
                OreDepositFeature.PlacementMode.UNDERGROUND,true,1,0,0x52415245534D414CL,1,1,1);
        for(int tier=OreDepositFeature.TIER_TINY;tier<OreDepositFeature.TIER_COUNT;tier++) {
            int accepted=0;
            for(int i=0;i<4096;i++) {
                int x=i%64-32,z=i/64-32;
                boolean first=OreDepositFeature.passesDataDrivenFrequency(level,x,z,biome,ore,tier,normal);
                helper.assertTrue(first==OreDepositFeature.passesDataDrivenFrequency(level,x,z,biome,ore,tier,normal),
                        "Frequency changed on repeated evaluation");
                if(first)accepted++;
                helper.assertTrue(OreDepositFeature.passesDataDrivenFrequency(level,x,z,biome,ore,tier,forced),
                        "Final spawn retention changed a forced rule");
            }
            if(tier<OreDepositFeature.TIER_MEDIUM)
                helper.assertTrue(accepted>1800 && accepted<2300,"Small tier was not approximately halved: "+accepted);
            else helper.assertTrue(accepted==4096,"Small-tier retention changed MEDIUM/LARGE");
        }
        helper.succeed();
    }
}

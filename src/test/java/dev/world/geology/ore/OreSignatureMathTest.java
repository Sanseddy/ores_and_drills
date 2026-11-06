package dev.world.geology.ore;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.world.geology.RockProperties;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class OreSignatureMathTest {
    @Test
    void rarityIsPrevalenceNotDensity() {
        OreTraits common = VanillaOres.ore("a", OreTraits.Category.METALLIC, 1, -32, 32, 12.0D);
        OreTraits rare = VanillaOres.ore("b", OreTraits.Category.METALLIC, 1, -32, 32, 3.0D);
        List<OreSignature> table = OreSignatureMath.build(List.of(common, rare), -64, 320);
        assertEquals(table.get(0).mineral(), table.get(1).mineral());
        assertEquals(4.0D, table.get(0).prevalence() / table.get(1).prevalence(), 1.0E-9D);
        assertEquals(1.0D, table.get(0).prevalence() + table.get(1).prevalence(), 1.0E-9D);
    }

    @Test
    void oresWithoutGenerationDataAreRareButPossible() {
        List<OreTraits> ores = new ArrayList<>(VanillaOres.overworld());
        ores.add(
                new OreTraits(
                        "mystery",
                        "mymod:mystery_ore",
                        OreTraits.Category.OTHER,
                        1,
                        3.0D,
                        0,
                        -1,
                        Double.NaN));
        List<OreSignature> table = OreSignatureMath.build(ores, -64, 320);
        OreSignature mystery = table.getLast();
        double rarest = Double.POSITIVE_INFINITY;
        double total = 0.0D;
        for (OreSignature signature : table) {
            total += signature.prevalence();
            if (signature.generationKnown()) {
                rarest = Math.min(rarest, signature.prevalence());
            }
        }
        assertEquals(1.0D, total, 1.0E-9D);
        assertTrue(mystery.prevalence() > 0.0D);
        assertEquals(
                rarest * OreSignatureMath.UNKNOWN_PREVALENCE_FACTOR,
                mystery.prevalence(),
                1.0E-12D);
    }

    @Test
    void propertiesFollowTraitsNotNames() {
        OreTraits original =
                VanillaOres.ore("iron", OreTraits.Category.METALLIC, 1, -64, 72, 20.0D);
        OreTraits renamed =
                new OreTraits(
                        "ferrite",
                        "mymod:ferrite_ore",
                        original.category(),
                        1,
                        3.0D,
                        -64,
                        72,
                        20.0D);
        RockProperties a = OreSignatureMath.build(List.of(original), -64, 320).getFirst().mineral();
        RockProperties b = OreSignatureMath.build(List.of(renamed), -64, 320).getFirst().mineral();
        assertEquals(a, b);
    }

    @Test
    void classesAndDepthOrderTheMinerals() {
        RockProperties metal = OreSignatureMath.mineral(OreTraits.Category.METALLIC, 0.5D, 0.5D);
        RockProperties gem = OreSignatureMath.mineral(OreTraits.Category.GEM, 0.5D, 0.5D);
        RockProperties soft = OreSignatureMath.mineral(OreTraits.Category.DUST, 0.5D, 0.5D);
        RockProperties other = OreSignatureMath.mineral(OreTraits.Category.OTHER, 0.5D, 0.5D);
        assertTrue(metal.density() > soft.density());
        assertTrue(soft.density() > gem.density());
        assertTrue(gem.density() > other.density());
        assertTrue(gem.velocity() > soft.velocity());
        RockProperties shallow = OreSignatureMath.mineral(OreTraits.Category.METALLIC, 0.5D, 0.1D);
        RockProperties deep = OreSignatureMath.mineral(OreTraits.Category.METALLIC, 0.5D, 0.9D);
        assertTrue(deep.density() > shallow.density());
        assertTrue(deep.velocity() > shallow.velocity());
    }

    @Test
    void hardnessRanksShareTies() {
        double[] ranks = OreSignatureMath.rank(VanillaOres.overworld());
        // Coal is the only ore any pickaxe mines; the tier-2 ores share the top rank.
        assertEquals(0.0D, ranks[0], 1.0E-12D);
        assertEquals(ranks[3], ranks[6], 1.0E-12D);
        assertTrue(ranks[3] > ranks[2]);
    }

    @Test
    void depthIndexRunsFromTopToFloor() {
        assertEquals(0.0D, OreSignatureMath.depthIndex(320, -64, 320), 1.0E-12D);
        assertEquals(1.0D, OreSignatureMath.depthIndex(-64, -64, 320), 1.0E-12D);
        assertEquals(
                OreSignatureMath.UNKNOWN_DEPTH, OreSignatureMath.depthIndex(0, 10, 10), 1.0E-12D);
    }
}

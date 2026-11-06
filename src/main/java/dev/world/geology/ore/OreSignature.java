package dev.world.geology.ore;

import dev.world.geology.RockProperties;

/**
 * Conditional geophysical character of one ore, derived from its {@link OreTraits}.
 *
 * @param mineral elastic properties of the ore mineral the survey model mixes into host rock
 * @param prevalence share of this ore among the ores generated in the dimension: how common it is,
 *     used as a prior probability — never as a physical property
 */
public record OreSignature(OreTraits traits, RockProperties mineral, double prevalence) {
    public String oreId() {
        return traits.oreId();
    }

    public boolean generationKnown() {
        return traits.generationKnown();
    }
}

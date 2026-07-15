package dev.world.level.levelgen;

import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;

/** Tier is explicit even though current deposit ids also include it. */
public record DepositValidationKey(
        ResourceKey<Level> dimension,
        OreDepositTier tier,
        long depositId
) {
}

package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TierPipelineTest {
    @Test
    void everyTierSelectsItsOwnResolverAndSeedSpace() {
        Set<Class<?>> resolverTypes = Arrays.stream(OreDepositTier.values())
                .map(DepositResolvers::forTier)
                .map(Object::getClass)
                .collect(Collectors.toSet());
        Set<Long> salts = Arrays.stream(OreDepositTier.values())
                .map(DepositResolvers::forTier)
                .map(TierDepositResolver::tierSalt)
                .collect(Collectors.toSet());

        assertEquals(OreDepositTier.values().length, resolverTypes.size());
        assertEquals(OreDepositTier.values().length, salts.size());
        for (OreDepositTier tier : OreDepositTier.values()) {
            assertEquals(tier, DepositResolvers.forTier(tier).tier());
        }
    }

    @Test
    void layoutsIncreaseGridShapeAndValidationCostWithTier() {
        DepositTierLayout tiny = DepositLayouts.forTier(OreDepositTier.TINY);
        DepositTierLayout small = DepositLayouts.forTier(OreDepositTier.SMALL);
        DepositTierLayout medium = DepositLayouts.forTier(OreDepositTier.MEDIUM);
        DepositTierLayout large = DepositLayouts.forTier(OreDepositTier.LARGE);

        assertTrue(tiny.cellSizeBlocks() < small.cellSizeBlocks());
        assertTrue(small.cellSizeBlocks() < medium.cellSizeBlocks());
        assertTrue(medium.cellSizeBlocks() < large.cellSizeBlocks());
        assertTrue(tiny.maximumRadius() < small.maximumRadius());
        assertTrue(small.maximumRadius() < medium.maximumRadius());
        assertTrue(medium.maximumRadius() < large.maximumRadius());
        assertTrue(tiny.maximumSamples() < small.maximumSamples());
        assertTrue(small.maximumSamples() < medium.maximumSamples());
        assertTrue(medium.maximumSamples() < large.maximumSamples());
        assertTrue(tiny.requiredStoneCoverage() < large.requiredStoneCoverage());
    }

    @Test
    void logicalCellCoordinatesMapToDifferentPhysicalAnchors() {
        long tiny = DepositResolvers.forTier(OreDepositTier.TINY).sourceChunkKey(4, 7);
        long small = DepositResolvers.forTier(OreDepositTier.SMALL).sourceChunkKey(4, 7);
        long medium = DepositResolvers.forTier(OreDepositTier.MEDIUM).sourceChunkKey(4, 7);
        long large = DepositResolvers.forTier(OreDepositTier.LARGE).sourceChunkKey(4, 7);

        assertEquals(4, Set.of(tiny, small, medium, large).size());
    }

}

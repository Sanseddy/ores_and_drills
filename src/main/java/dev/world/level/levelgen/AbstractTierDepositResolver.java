package dev.world.level.levelgen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.ArrayList;

abstract class AbstractTierDepositResolver implements TierDepositResolver {
    private final OreDepositTier tier;

    AbstractTierDepositResolver(OreDepositTier tier) {
        this.tier = tier;
    }

    @Override
    public final OreDepositTier tier() {
        return tier;
    }

    @Override
    public DepositTierLayout layout() {
        return DepositLayouts.forTier(tier);
    }

    @Override
    public final long tierSalt() {
        return DepositLayouts.tierSalt(tier);
    }

    @Override
    public long sourceChunkKey(int cellX, int cellZ) {
        return DepositLayouts.anchorChunkKey(tier, cellX, cellZ);
    }

    @Override
    public List<DepositCandidate> getCandidatesForCell(
            ServerLevel level,
            int cellX,
            int cellZ,
            ResourceLocation requestedOre
    ) {
        if (!DepositCandidateResolver.isTaggedOre(requestedOre)) {
            return List.of();
        }
        Block ore = BuiltInRegistries.BLOCK.get(requestedOre);
        String materialKey = OreUnifier.materialKeyFor(ore);
        if (materialKey.isEmpty()) {
            return List.of();
        }
        long sourceChunk = sourceChunkKey(cellX, cellZ);
        int chunkX = ChunkPos.getX(sourceChunk);
        int chunkZ = ChunkPos.getZ(sourceChunk);
        List<OreGenerationWeights.SourceContext> contexts = OreGenerationWeights.sourceContexts(
                level.dimension().location(), materialKey
        );
        if (contexts.isEmpty()) {
            if (!OreDepositFeature.predictsMaterialForChunk(
                    level, chunkX, chunkZ, tier.index(), materialKey
            )) {
                return List.of();
            }
            return List.of(createCandidateForSourceChunk(
                    level,
                    chunkX,
                    chunkZ,
                    requestedOre,
                    materialKey,
                    OreDepositFeature.liveSizeMultiplier(ore)
            ));
        }

        List<DepositCandidate> candidates = new ArrayList<>();
        float sizeMultiplier = OreDepositFeature.liveSizeMultiplier(ore);
        for (OreGenerationWeights.SourceContext context : contexts) {
            if (!OreDepositFeature.matchesAutomaticSourceBiome(
                    level, chunkX, chunkZ, context.biomeId()
            )) {
                continue;
            }
            if (!OreDepositFeature.predictsMaterialForChunk(
                    level, chunkX, chunkZ, tier.index(), materialKey, context.biomeId()
            )) {
                continue;
            }
            DepositCandidate candidate = OreDepositFeature.createPlannedCandidate(
                    level, chunkX, chunkZ, tier.index(), requestedOre,
                    materialKey, sizeMultiplier, context.biomeId()
            );
            if (tier.index() < OreDepositFeature.TIER_MEDIUM) {
                var actualBiome = level.getUncachedNoiseBiome(
                        net.minecraft.core.QuartPos.fromBlock(candidate.center().getX()),
                        net.minecraft.core.QuartPos.fromBlock(candidate.center().getY()),
                        net.minecraft.core.QuartPos.fromBlock(candidate.center().getZ())
                );
                if (!actualBiome.is(net.minecraft.resources.ResourceKey.create(
                        net.minecraft.core.registries.Registries.BIOME, context.biomeId()
                ))) {
                    continue;
                }
            }
            if (tier.index() >= OreDepositFeature.TIER_MEDIUM) {
                candidate = OreDepositFeature.relocatePredictedCandidateToSourceBiome(
                        level, candidate, context.biomeId(), context.targets()
                );
                if (candidate == null) {
                    continue;
                }
            }
            candidates.add(candidate);
        }
        return List.copyOf(candidates);
    }

    @Override
    public DepositCandidate createCandidateForSourceChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            ResourceLocation oreId,
            String materialKey,
            float sizeMultiplier
    ) {
        if (!DepositLayouts.isAnchorChunk(tier, chunkX, chunkZ)) {
            throw new IllegalArgumentException(
                    "Source chunk does not belong to the " + tier.serializedName() + " grid"
            );
        }
        return OreDepositFeature.createPlannedCandidate(
                level, chunkX, chunkZ, tier.index(), oreId, materialKey, sizeMultiplier
        );
    }
}

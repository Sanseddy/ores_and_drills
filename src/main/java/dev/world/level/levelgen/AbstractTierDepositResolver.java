package dev.world.level.levelgen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
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
        if (!DepositCandidateResolver.isTaggedOre(requestedOre) || tier.index() < OreDepositFeature.TIER_MEDIUM) {
            // Automatic TINY/SMALL have no cell plan: SmallDepositLocate reads and predicts their attempt stream.
            return List.of();
        }
        Block ore = BuiltInRegistries.BLOCK.get(requestedOre);
        String materialKey = OreUnifier.materialKeyFor(ore);
        if (materialKey.isEmpty()) {
            return List.of();
        }
        // Layout keys keep X in the high half, unlike ChunkPos keys: ChunkPos.getX/getZ would swap the axes
        // and search the cell mirrored across x = z, which only looks right near the origin and the diagonal.
        long sourceChunk = sourceChunkKey(cellX, cellZ);
        int chunkX = DepositLayouts.chunkX(sourceChunk);
        int chunkZ = DepositLayouts.chunkZ(sourceChunk);
        List<OreGenerationWeights.SourceContext> contexts = OreGenerationWeights.sourceContexts(
                level.dimension().location(), materialKey
        );
        if (contexts.isEmpty()) {
            if (!OreDepositFeature.predictsMaterialForChunk(
                    level, chunkX, chunkZ, tier.index(), materialKey
            )) {
                return List.of();
            }
            DepositCandidate candidate = OreDepositFeature.fitCompactCandidate(level, tier.index(), null,
                    createCandidateForSourceChunk(level, chunkX, chunkZ, requestedOre, materialKey,
                            OreDepositFeature.liveSizeMultiplier(ore)));
            return candidate == null ? List.of() : List.of(candidate);
        }

        List<DepositCandidate> candidates = new ArrayList<>();
        float sizeMultiplier = OreDepositFeature.liveSizeMultiplier(ore);
        // Every context below describes the same immutable source chunk. Resolving its noise biome
        // once avoids one MultiNoiseBiomeSource traversal per registered biome for every locate cell.
        ResourceLocation sourceBiomeId = OreDepositFeature.automaticSourceBiomeId(level, chunkX, chunkZ);
        for (OreGenerationWeights.SourceContext context : contexts) {
            if (!java.util.Objects.equals(sourceBiomeId, context.biomeId())) {
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
            candidate = OreDepositFeature.relocatePredictedCandidateToSourceBiome(
                    level, candidate, context.biomeId(), context.targets()
            );
            // Worldgen lifts a lens off the bedrock floor before planning; validating the unfitted center
            // sampled below the world and rejected real deep LARGE deposits.
            if (candidate != null) {
                candidate = OreDepositFeature.fitCompactCandidate(level, tier.index(), context.biomeId(), candidate);
            }
            if (candidate == null) {
                continue;
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

package dev.world.level.levelgen;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/** Resolves candidates from exactly one tier's generation grid and seed space. */
public interface TierDepositResolver {
    OreDepositTier tier();

    DepositTierLayout layout();

    long tierSalt();

    List<DepositCandidate> getCandidatesForCell(
            ServerLevel level,
            int cellX,
            int cellZ,
            ResourceLocation requestedOre
    );

    DepositCandidate createCandidateForSourceChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            ResourceLocation oreId,
            String materialKey,
            float sizeMultiplier
    );

    long sourceChunkKey(int cellX, int cellZ);
}

package dev.world.level.levelgen;

import java.util.List;

/** Creates deterministic, tier-specific terrain probes for a planned deposit. */
public interface DepositSampler {
    List<SamplePoint> createSamples(DepositCandidate candidate, DepositTierLayout layout);
}

package dev.world.level.levelgen;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;

/**
 * Read-only forecast of the MEDIUM/LARGE deposits an area will hold once it generates, for the
 * seismic survey. It asks the same deterministic plans worldgen and {@code /locate} use — the
 * per-cell material lottery, the tier resolvers and the datapack rule slots — and never loads,
 * generates or writes chunks. Like the locate service's cell resolution it may run on a worker
 * thread: it reads only the seed, the generator's noise and immutable rule data.
 *
 * <p>TINY/SMALL deposits are not forecast: they follow the original ore features' attempt stream,
 * are as common as ordinary veins and would only clutter a regional map.
 */
public final class SeismicDepositForecast {
    public static final List<OreDepositTier> TIERS =
            List.of(OreDepositTier.MEDIUM, OreDepositTier.LARGE);

    private SeismicDepositForecast() {}

    public static DepositTierLayout layout(OreDepositTier tier) {
        return DepositLayouts.forTier(tier);
    }

    /** Ores owned by datapack rules; automatic generation never places them. */
    public static List<ResourceLocation> dataDrivenOres(ServerLevel level) {
        return DataDrivenOreDepositBiomeModifier.configuredOreCatalog(level);
    }

    /**
     * Candidates planned for one cell of {@code tier}.
     *
     * @param exact use the full {@code /locate} path: move the lens into its source biome, then
     *     build its worldgen plan and keep it only if the plan wins arbitration. Exact, but moving
     *     a lens reads base columns and planning a LARGE lens takes hundreds of milliseconds, so
     *     only detailed surveys ask for it. Otherwise the planned centre is used as is, which can
     *     be off by the relocation distance — well inside one regional map pixel.
     */
    public static List<DepositCandidate> predict(
            ServerLevel level,
            OreDepositTier tier,
            int cellX,
            int cellZ,
            List<ResourceLocation> dataDrivenOres,
            boolean exact) {
        List<DepositCandidate> candidates = new ArrayList<>();
        long anchor = DepositLayouts.anchorChunkKey(tier, cellX, cellZ);
        int chunkX = DepositLayouts.chunkX(anchor);
        int chunkZ = DepositLayouts.chunkZ(anchor);
        Optional<OreDepositFeature.SelectedMaterial> selected =
                OreDepositFeature.predictedCompactMaterial(level, chunkX, chunkZ, tier.index());
        if (selected.isPresent() && !isDataDriven(level, selected.get().oreId())) {
            OreDepositFeature.SelectedMaterial material = selected.get();
            if (exact) {
                for (DepositCandidate candidate :
                        DepositResolvers.forTier(tier)
                                .getCandidatesForCell(level, cellX, cellZ, material.oreId())) {
                    if (OreDepositFeature.compactCandidateGenerates(level, candidate)) {
                        candidates.add(candidate);
                    }
                }
            } else {
                Block ore = BuiltInRegistries.BLOCK.get(material.oreId());
                DepositCandidate candidate =
                        OreDepositFeature.fitCompactCandidate(
                                level,
                                tier.index(),
                                material.biomeId(),
                                OreDepositFeature.createPlannedCandidate(
                                        level,
                                        chunkX,
                                        chunkZ,
                                        tier.index(),
                                        material.oreId(),
                                        material.materialKey(),
                                        OreDepositFeature.liveSizeMultiplier(ore),
                                        material.biomeId()));
                if (candidate != null) {
                    candidates.add(candidate);
                }
            }
        }
        for (ResourceLocation ore : dataDrivenOres) {
            for (DataDrivenOreDepositBiomeModifier.PlannedDeposit plan :
                    DataDrivenOreDepositBiomeModifier.plansForCell(
                            level, cellX, cellZ, ore, tier)) {
                // Wall-only rules depend on carvers, which unexplored base terrain cannot show.
                if (plan.placement() != OreDepositFeature.PlacementMode.CAVE_WALL) {
                    candidates.add(plan.candidate());
                }
            }
        }
        return List.copyOf(candidates);
    }

    private static boolean isDataDriven(ServerLevel level, ResourceLocation oreId) {
        Block block = BuiltInRegistries.BLOCK.get(oreId);
        return block != null
                && DataDrivenOreDepositBiomeModifier.explicitlyConfigured(level, block);
    }
}

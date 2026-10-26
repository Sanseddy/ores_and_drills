package dev.world.level.levelgen;

import dev.config.OreGenerationMode;
import dev.registry.ModAttachments;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * {@code /locate} for automatic TINY/SMALL deposits. They are too numerous for the persistent
 * locate index, so a confirmed result is read from the deposit data of a loaded chunk, and an
 * unexplored chunk is answered with a seed prediction of the same attempt stream, which is reported
 * as unverified.
 */
final class SmallDepositLocate {
    private SmallDepositLocate() {}

    /**
     * Automatic TINY/SMALL of the regular mode; datapack rules and vanilla-like worlds keep their
     * own paths.
     */
    static boolean usesSourceAttempts(OreDepositTier tier, boolean dataDriven) {
        return tier.index() < OreDepositFeature.TIER_MEDIUM
                && !dataDriven
                && !OreGenerationMode.vanillaLikeGeneration();
    }

    static boolean isLoaded(ServerLevel level, int chunkX, int chunkZ) {
        return level.getChunkSource().getChunkNow(chunkX, chunkZ) != null;
    }

    /**
     * The deposit block of the requested material and tier in a loaded chunk that is nearest to the
     * origin.
     */
    static LocatedDeposit confirmedInLoadedChunk(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            ResourceLocation effectiveOre,
            OreDepositTier tier,
            BlockPos origin) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(chunkX, chunkZ);
        if (chunk == null) {
            return null;
        }
        OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
        if (data == null || data.isEmpty()) {
            return null;
        }
        String material = materialOf(effectiveOre);
        List<ResourceLocation> ores = OreDepositPaletteData.get(level).ores();
        BlockPos[] nearest = new BlockPos[1];
        long[] nearestDistance = {Long.MAX_VALUE};
        data.forEach(
                chunkX,
                chunkZ,
                (pos, entry) -> {
                    if (entry.tier() != tier.index()
                            || entry.remainingOre() <= 0
                            || entry.oreIndex() < 0
                            || entry.oreIndex() >= ores.size()
                            || !material.equals(materialOf(ores.get(entry.oreIndex())))) {
                        return;
                    }
                    long distance = DepositLocator.horizontalDistanceSquared(origin, pos);
                    if (distance < nearestDistance[0]) {
                        nearestDistance[0] = distance;
                        nearest[0] = pos.immutable();
                    }
                });
        if (nearest[0] == null) {
            return null;
        }
        BlockPos pos = nearest[0];
        Direction exposed = null;
        for (Direction direction : Direction.Plane.HORIZONTAL) {
            if (CaveWallDetector.isOpenCaveSpace(level.getBlockState(pos.relative(direction)))) {
                exposed = direction.getOpposite();
                break;
            }
        }
        DepositCandidate candidate =
                new DepositCandidate(
                        SeedMixer.mix(pos.asLong() ^ ((long) tier.index() << 56)),
                        effectiveOre,
                        tier,
                        pos,
                        1,
                        1,
                        1,
                        pos.asLong(),
                        exposed,
                        1,
                        chunkX,
                        chunkZ);
        return new LocatedDeposit(
                candidate,
                DepositLocateStatus.CONFIRMED,
                DepositLocator.floorDistance(nearestDistance[0]),
                1.0D);
    }

    static List<SmallDepositAttemptPlanner.Prediction> predictions(
            ServerLevel level,
            int chunkX,
            int chunkZ,
            ResourceLocation effectiveOre,
            OreDepositTier tier) {
        return SmallDepositAttemptPlanner.predict(
                level, chunkX, chunkZ, materialOf(effectiveOre), effectiveOre, tier);
    }

    static ValidationResult predictedValidation(double hostCoverage) {
        int valid = (int) Math.round(hostCoverage * 7.0D);
        return new ValidationResult(
                true, valid, 7, Math.min(1.0D, Math.max(0.0D, hostCoverage)), 1, 1);
    }

    private static String materialOf(ResourceLocation oreId) {
        Block block = BuiltInRegistries.BLOCK.get(oreId);
        return block == null ? oreId.toString() : OreUnifier.materialKeyFor(block);
    }
}

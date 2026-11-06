package dev.world.geology.survey;

import dev.world.geology.CaveAnalysis;
import dev.world.geology.CavityProbe;
import dev.world.geology.NaturalGeology;
import dev.world.geology.RockCatalog;
import dev.world.geology.RockProperties;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseBasedChunkGenerator;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;

/**
 * Probes caves through the generator's final density function: a point with density at or below
 * zero is open. Below sea level the generator fills open space with its default fluid (water in the
 * overworld, lava in the Nether), and aquifers put lava below Y −54; the probe weighs such space by
 * the fluid's contrast with rock, so the Nether's lava sea — almost as stiff as netherrack — is not
 * mistaken for a giant cave.
 *
 * <p>The function is evaluated directly, not through a {@code NoiseChunk}, so it reads no chunk,
 * writes no cache and is safe on a worker thread like the generator's own worker pool. It describes
 * the original terrain: carvers and player tunnels are not seen.
 */
public final class TerrainCavityProbe implements CavityProbe, CaveAnalysis.Terrain {
    /** Aquifers place lava instead of water below this height. */
    private static final int AQUIFER_LAVA_LEVEL = -54;

    private final DensityFunction density;
    private final int seaLevel;
    private final double seaContrast;
    private final boolean lavaFloor;
    private final double lavaContrast;
    private final byte seaFill;

    private TerrainCavityProbe(
            DensityFunction density,
            int seaLevel,
            double seaContrast,
            boolean lavaFloor,
            double lavaContrast,
            byte seaFill) {
        this.density = density;
        this.seaLevel = seaLevel;
        this.seaContrast = seaContrast;
        this.lavaFloor = lavaFloor;
        this.lavaContrast = lavaContrast;
        this.seaFill = seaFill;
    }

    /** A probe for {@code level}, or {@link CavityProbe#SOLID} for non-noise generators. */
    public static CavityProbe of(ServerLevel level) {
        TerrainCavityProbe probe = terrain(level);
        return probe == null ? CavityProbe.SOLID : probe;
    }

    /** The cave analyser's view of the same terrain; solid rock for non-noise generators. */
    public static CaveAnalysis.Terrain caveTerrain(ServerLevel level) {
        TerrainCavityProbe probe = terrain(level);
        return probe == null ? CaveAnalysis.Terrain.SOLID_TERRAIN : probe;
    }

    private static TerrainCavityProbe terrain(ServerLevel level) {
        if (!(level.getChunkSource().getGenerator()
                instanceof NoiseBasedChunkGenerator generator)) {
            return null;
        }
        return of(
                generator.generatorSettings().value(),
                level.getChunkSource().randomState(),
                NaturalGeology.Setting.forDimension(level.dimension().location().toString()));
    }

    /** A probe for explicit generator settings, e.g. a dimension the server does not run. */
    public static TerrainCavityProbe of(
            NoiseGeneratorSettings settings,
            RandomState randomState,
            NaturalGeology.Setting setting) {
        int seaLevel = settings.seaLevel();
        RockProperties seaHost = NaturalGeology.host(setting, seaLevel - 1);
        return new TerrainCavityProbe(
                randomState.router().finalDensity(),
                seaLevel,
                CavityProbe.fillingContrast(seaHost, filling(settings.defaultFluid())),
                settings.aquifersEnabled(),
                CavityProbe.fillingContrast(
                        NaturalGeology.host(setting, AQUIFER_LAVA_LEVEL - 1), RockCatalog.LAVA),
                fillCode(settings.defaultFluid()));
    }

    private static byte fillCode(BlockState fluid) {
        if (fluid.is(Blocks.LAVA)) {
            return CaveAnalysis.LAVA;
        }
        return fluid.is(Blocks.WATER) ? CaveAnalysis.WATER : CaveAnalysis.AIR;
    }

    private static RockProperties filling(BlockState fluid) {
        if (fluid.is(Blocks.LAVA)) {
            return RockCatalog.LAVA;
        }
        return fluid.is(Blocks.WATER) ? RockCatalog.WATER : RockCatalog.AIR;
    }

    @Override
    public double contrast(int x, int y, int z) {
        if (density.compute(new DensityFunction.SinglePointContext(x, y, z)) > 0.0D) {
            return 0.0D;
        }
        if (lavaFloor && y < AQUIFER_LAVA_LEVEL) {
            return lavaContrast;
        }
        return y < seaLevel ? seaContrast : 1.0D;
    }

    @Override
    public double density(int x, int y, int z) {
        return density.compute(new DensityFunction.SinglePointContext(x, y, z));
    }

    @Override
    public byte fill(int y) {
        if (lavaFloor && y < AQUIFER_LAVA_LEVEL) {
            return CaveAnalysis.LAVA;
        }
        return y < seaLevel ? seaFill : CaveAnalysis.AIR;
    }
}

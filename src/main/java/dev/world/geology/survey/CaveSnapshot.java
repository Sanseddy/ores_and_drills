package dev.world.geology.survey;

import dev.world.geology.CaveAnalysis;
import dev.world.geology.CaveAnalyzer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.material.FluidState;

/**
 * The cave analyser's probe lattice, filled on the server thread in budgeted slices. Columns in
 * loaded chunks are read from the world, so mined-out and newly opened voids are seen; columns in
 * unloaded chunks stay {@link CaveAnalysis#UNKNOWN} and are answered from the terrain model on a
 * worker. Nothing is loaded or generated, and only lattice points are read: the result is no finer
 * than the analyser's spacing.
 */
public final class CaveSnapshot {
    /** How far above the station the lattice reaches, for caves under nearby hills. */
    private static final int HEADROOM = 24;

    private final ServerLevel level;
    private final CaveAnalysis.Grid grid;
    private final double centerX;
    private final double centerZ;
    private final double radius;
    private int cursor;

    private CaveSnapshot(
            ServerLevel level,
            CaveAnalysis.Grid grid,
            double centerX,
            double centerZ,
            double radius) {
        this.level = level;
        this.grid = grid;
        this.centerX = centerX;
        this.centerZ = centerZ;
        this.radius = radius;
    }

    /** A lattice for the request, or null when no analyser is installed. */
    static CaveSnapshot create(ServerLevel level, SurveyRequest request, int minY) {
        int analyzer = CaveAnalyzer.clamp(request.analyzer());
        int radius = CaveAnalyzer.radius(analyzer, request.radius());
        if (analyzer <= 0 || radius <= 0) {
            return null;
        }
        int spacing = CaveAnalyzer.spacing(analyzer);
        double centerX = request.station().getX() + 0.5D;
        double centerZ = request.station().getZ() + 0.5D;
        int top = Math.min(level.getMaxBuildHeight() - 1, request.station().getY() + HEADROOM);
        int size = (int) Math.ceil(radius * 2.0D / spacing) + 1;
        int ny = Math.max(1, (top - minY) / spacing + 1);
        int cells = size * size * ny;
        byte[] fill = new byte[cells];
        java.util.Arrays.fill(fill, CaveAnalysis.UNKNOWN);
        CaveAnalysis.Grid grid =
                new CaveAnalysis.Grid(
                        Math.floor(centerX - radius) + 0.5D,
                        top + 0.5D,
                        Math.floor(centerZ - radius) + 0.5D,
                        spacing,
                        size,
                        ny,
                        size,
                        new float[cells],
                        fill);
        return new CaveSnapshot(level, grid, centerX, centerZ, radius);
    }

    CaveAnalysis.Grid grid() {
        return grid;
    }

    double radius() {
        return radius;
    }

    double progress() {
        return cursor / (double) Math.max(1, grid.nx() * grid.nz());
    }

    /** Reads columns until {@code deadline}; true once every column is done. */
    boolean advance(long deadline) {
        int columns = grid.nx() * grid.nz();
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        while (cursor < columns) {
            if ((cursor & 15) == 0 && System.nanoTime() >= deadline) {
                return false;
            }
            int ix = cursor / grid.nz();
            int iz = cursor % grid.nz();
            cursor++;
            double wx = grid.x(ix);
            double wz = grid.z(iz);
            if (Math.hypot(wx - centerX, wz - centerZ) > radius) {
                // Outside the analysed circle: excluded like open sky, so its edge draws no wall.
                for (int iy = 0; iy < grid.ny(); iy++) {
                    grid.fill()[grid.index(ix, iy, iz)] = CaveAnalysis.SKY;
                }
                continue;
            }
            int x = (int) Math.floor(wx);
            int z = (int) Math.floor(wz);
            LevelChunk chunk = level.getChunkSource().getChunkNow(x >> 4, z >> 4);
            if (chunk == null) {
                continue;
            }
            int surface = chunk.getHeight(Heightmap.Types.OCEAN_FLOOR, x & 15, z & 15);
            for (int iy = 0; iy < grid.ny(); iy++) {
                int index = grid.index(ix, iy, iz);
                int y = (int) Math.floor(grid.y(iy));
                if (y > surface) {
                    grid.fill()[index] = CaveAnalysis.SKY;
                    grid.density()[index] = -1.0F;
                    continue;
                }
                byte code = code(chunk.getBlockState(pos.set(x, y, z)));
                grid.fill()[index] = code;
                grid.density()[index] = code == CaveAnalysis.SOLID ? 1.0F : -1.0F;
            }
        }
        return true;
    }

    /** Fill code of a block as the analyser reads it. */
    public static byte code(BlockState state) {
        if (state.isAir()) {
            return CaveAnalysis.AIR;
        }
        FluidState fluid = state.getFluidState();
        if (!fluid.isEmpty() && !state.blocksMotion()) {
            return fluid.is(FluidTags.LAVA) ? CaveAnalysis.LAVA : CaveAnalysis.WATER;
        }
        return state.blocksMotion() ? CaveAnalysis.SOLID : CaveAnalysis.AIR;
    }
}

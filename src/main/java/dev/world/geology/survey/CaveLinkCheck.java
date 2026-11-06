package dev.world.geology.survey;

import dev.world.geology.CaveAnalysis;
import dev.world.geology.CaveAnalyzer;
import java.util.ArrayDeque;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.chunk.LevelChunk;

/**
 * Checks the analyser's suspected connections on the server thread. Around each one it resamples a
 * small box at the analyser's confirmation step and searches for an open path between the two
 * caves. A coarse step can miss a narrow passage or step over a thin wall, which is exactly how a
 * weaker analyser is less reliable. Loaded chunks are read from the world, unloaded ones from the
 * terrain model; nothing is loaded.
 */
final class CaveLinkCheck {
    private final ServerLevel level;
    private final CaveAnalysis.Terrain terrain;
    private final List<CaveAnalysis.Link> links;
    private final boolean[] confirmed;
    private final int step;
    private final double spacing;
    private int cursor;

    CaveLinkCheck(
            ServerLevel level,
            CaveAnalysis.Terrain terrain,
            List<CaveAnalysis.Link> links,
            int analyzer,
            double spacing) {
        this.level = level;
        this.terrain = terrain;
        this.links = links;
        this.confirmed = new boolean[links.size()];
        this.step = CaveAnalyzer.confirmStep(analyzer);
        this.spacing = spacing;
        if (step <= 0) {
            cursor = links.size();
        }
    }

    boolean[] confirmed() {
        return confirmed;
    }

    double progress() {
        return links.isEmpty() ? 1.0D : cursor / (double) links.size();
    }

    boolean advance(long deadline) {
        while (cursor < links.size()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            confirmed[cursor] = connected(links.get(cursor));
            cursor++;
        }
        return true;
    }

    private boolean connected(CaveAnalysis.Link link) {
        double minX = Math.min(link.ax(), link.bx()) - spacing;
        double minY = Math.min(link.ay(), link.by()) - spacing;
        double minZ = Math.min(link.az(), link.bz()) - spacing;
        int nx = (int) Math.ceil((Math.abs(link.bx() - link.ax()) + 2.0D * spacing) / step) + 1;
        int ny = (int) Math.ceil((Math.abs(link.by() - link.ay()) + 2.0D * spacing) / step) + 1;
        int nz = (int) Math.ceil((Math.abs(link.bz() - link.az()) + 2.0D * spacing) / step) + 1;
        boolean[] open = new boolean[nx * ny * nz];
        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (int ix = 0; ix < nx; ix++) {
            for (int iy = 0; iy < ny; iy++) {
                for (int iz = 0; iz < nz; iz++) {
                    int x = (int) Math.floor(minX + ix * step);
                    int y = (int) Math.floor(minY + iy * step);
                    int z = (int) Math.floor(minZ + iz * step);
                    open[(ix * ny + iy) * nz + iz] = open(pos.set(x, y, z));
                }
            }
        }
        int start = cell(link.ax(), link.ay(), link.az(), minX, minY, minZ, nx, ny, nz);
        int goal = cell(link.bx(), link.by(), link.bz(), minX, minY, minZ, nx, ny, nz);
        if (!open[start] || !open[goal]) {
            return false;
        }
        boolean[] seen = new boolean[open.length];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(start);
        seen[start] = true;
        while (!queue.isEmpty()) {
            int cell = queue.poll();
            if (cell == goal) {
                return true;
            }
            int iz = cell % nz;
            int iy = (cell / nz) % ny;
            int ix = cell / (nz * ny);
            int[][] steps = {{1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}};
            for (int[] d : steps) {
                int jx = ix + d[0];
                int jy = iy + d[1];
                int jz = iz + d[2];
                if (jx < 0 || jy < 0 || jz < 0 || jx >= nx || jy >= ny || jz >= nz) {
                    continue;
                }
                int next = (jx * ny + jy) * nz + jz;
                if (open[next] && !seen[next]) {
                    seen[next] = true;
                    queue.add(next);
                }
            }
        }
        return false;
    }

    private int cell(
            double x,
            double y,
            double z,
            double minX,
            double minY,
            double minZ,
            int nx,
            int ny,
            int nz) {
        int ix = Math.max(0, Math.min(nx - 1, (int) Math.round((x - minX) / step)));
        int iy = Math.max(0, Math.min(ny - 1, (int) Math.round((y - minY) / step)));
        int iz = Math.max(0, Math.min(nz - 1, (int) Math.round((z - minZ) / step)));
        return (ix * ny + iy) * nz + iz;
    }

    private boolean open(BlockPos pos) {
        LevelChunk chunk = level.getChunkSource().getChunkNow(pos.getX() >> 4, pos.getZ() >> 4);
        if (chunk != null) {
            return CaveSnapshot.code(chunk.getBlockState(pos)) != CaveAnalysis.SOLID;
        }
        return terrain.density(pos.getX(), pos.getY(), pos.getZ()) <= 0.0D;
    }
}

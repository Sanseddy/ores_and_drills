package dev.world.level.levelgen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.QuartPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.biome.Biome;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Identifies one instance of a biome (a single cave or biome patch) so forced datapack rules can place an
 * exact number of deposits per instance. Region-based mod biomes use their region; other biomes use the
 * connected patch of the noise biome on a coarse grid, named after its smallest grid node. Patches too large
 * to walk fall back to fixed cells of the deposit layout.
 */
final class BiomeInstances {
    private static final int GRID = 16;
    private static final int VERTICAL_STEP = 16;
    private static final int MAX_PATCH_NODES = 4_096;
    private static final int MAX_CACHED_NODES = 262_144;
    private static final Map<String, Instance> NODE_CACHE = new ConcurrentHashMap<>();

    private BiomeInstances() {
    }

    /** One biome instance and the radius of a circle with the same area (unbounded for regions and cells). */
    record Instance(String key, int approximateRadius) {
    }

    static Instance at(ServerLevel level, BlockPos pos, ResourceLocation biome, int minimumY, int maximumY,
            int fallbackCellBlocks) {
        String region = OptionalBiomeRegionHints.regionKey(level, pos, biome);
        if (region != null) {
            return new Instance(region, Integer.MAX_VALUE);
        }
        int startX = Math.floorDiv(pos.getX(), GRID);
        int startZ = Math.floorDiv(pos.getZ(), GRID);
        String cacheKey = level.dimension().location() + "|" + biome + "|" + minimumY + "|" + maximumY + "|"
                + startX + "|" + startZ;
        Instance cached = NODE_CACHE.get(cacheKey);
        if (cached != null) {
            return cached;
        }

        ResourceKey<Biome> biomeKey = ResourceKey.create(Registries.BIOME, biome);
        Instance instance = null;
        if (inBiome(level, startX, startZ, biomeKey, minimumY, maximumY)) {
            Set<Long> visited = new HashSet<>();
            ArrayDeque<long[]> queue = new ArrayDeque<>();
            visited.add(ChunkPos.asLong(startX, startZ));
            queue.add(new long[] {startX, startZ});
            int smallestX = startX;
            int smallestZ = startZ;
            boolean complete = true;
            while (!queue.isEmpty()) {
                long[] node = queue.poll();
                int x = (int) node[0];
                int z = (int) node[1];
                if (x < smallestX || x == smallestX && z < smallestZ) {
                    smallestX = x;
                    smallestZ = z;
                }
                int[][] neighbours = {{x + 1, z}, {x - 1, z}, {x, z + 1}, {x, z - 1}};
                for (int[] neighbour : neighbours) {
                    long key = ChunkPos.asLong(neighbour[0], neighbour[1]);
                    if (visited.contains(key) || !inBiome(level, neighbour[0], neighbour[1], biomeKey, minimumY, maximumY)) {
                        continue;
                    }
                    if (visited.size() >= MAX_PATCH_NODES) {
                        complete = false;
                        queue.clear();
                        break;
                    }
                    visited.add(key);
                    queue.add(new long[] {neighbour[0], neighbour[1]});
                }
            }
            if (complete) {
                int radius = (int) Math.ceil(Math.sqrt(visited.size() * (double) GRID * GRID / Math.PI));
                instance = new Instance("patch:" + biome + ":" + smallestX + ":" + smallestZ, radius);
                if (NODE_CACHE.size() + visited.size() > MAX_CACHED_NODES) {
                    NODE_CACHE.clear();
                }
                String prefix = level.dimension().location() + "|" + biome + "|" + minimumY + "|" + maximumY + "|";
                for (long node : visited) {
                    NODE_CACHE.put(prefix + ChunkPos.getX(node) + "|" + ChunkPos.getZ(node), instance);
                }
            }
        }
        if (instance == null) {
            // The noise source cannot see this patch, or it is a huge biome: count per layout cell instead.
            int cellSize = Math.max(16, fallbackCellBlocks);
            instance = new Instance(
                    "cell:" + biome + ":" + Math.floorDiv(pos.getX(), cellSize) + ":" + Math.floorDiv(pos.getZ(), cellSize),
                    Integer.MAX_VALUE
            );
        }
        if (NODE_CACHE.size() > MAX_CACHED_NODES) {
            NODE_CACHE.clear();
        }
        NODE_CACHE.put(cacheKey, instance);
        return instance;
    }

    private static boolean inBiome(ServerLevel level, int gridX, int gridZ, ResourceKey<Biome> biome,
            int minimumY, int maximumY) {
        int x = gridX * GRID + GRID / 2;
        int z = gridZ * GRID + GRID / 2;
        for (int y = minimumY; y <= maximumY; y += VERTICAL_STEP) {
            if (level.getUncachedNoiseBiome(QuartPos.fromBlock(x), QuartPos.fromBlock(y), QuartPos.fromBlock(z))
                    .is(biome)) {
                return true;
            }
        }
        return false;
    }
}

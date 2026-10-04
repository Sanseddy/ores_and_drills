package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Method;
import java.util.Set;

/** Optional, reflection-only accelerators for mods that expose seed-based biome-region lookup. */
final class OptionalBiomeRegionHints {
    private static final String ALEX_CAVES_NAMESPACE = "alexscaves";
    private static final String ALEX_CAVES_RARITY_CLASS =
            "com.github.alexmodguy.alexscaves.server.level.biome.ACBiomeRarity";
    private static final int ALEX_CAVES_CHUNK_STEP = 4;
    private static final int REGION_EDGE_SEARCH_RADIUS = 192;
    private static final int REGION_EDGE_SEARCH_STEP = 16;
    private static volatile boolean alexCavesLookupResolved;
    private static volatile Method alexCavesLookup;
    private static volatile Method alexCavesCenterLookup;

    private OptionalBiomeRegionHints() {
    }

    static BlockPos findClosest(
            ServerLevel level,
            BlockPos origin,
            int radius,
            Set<ResourceLocation> allowedBiomes
    ) {
        Set<ResourceLocation> alexBiomes = allowedBiomes.stream()
                .filter(id -> ALEX_CAVES_NAMESPACE.equals(id.getNamespace()))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (alexBiomes.isEmpty()) {
            return null;
        }
        Method lookup = alexCavesLookup();
        if (lookup == null) {
            return null;
        }

        int originChunkX = origin.getX() >> 4;
        int originChunkZ = origin.getZ() >> 4;
        int maximumChunkRadius = Math.max(0, (radius + 15) / 16);
        long radiusSquared = (long) radius * radius;
        try {
            for (int ring = 0; ring <= maximumChunkRadius; ring += ALEX_CAVES_CHUNK_STEP) {
                for (int offsetX = -ring; offsetX <= ring; offsetX += ALEX_CAVES_CHUNK_STEP) {
                    for (int offsetZ = -ring; offsetZ <= ring; offsetZ += ALEX_CAVES_CHUNK_STEP) {
                        if (ring > 0 && Math.abs(offsetX) != ring && Math.abs(offsetZ) != ring) {
                            continue;
                        }
                        int x = (originChunkX + offsetX) * 16 + 8;
                        int z = (originChunkZ + offsetZ) * 16 + 8;
                        long dx = (long) x - origin.getX();
                        long dz = (long) z - origin.getZ();
                        if (dx * dx + dz * dz > radiusSquared) {
                            continue;
                        }
                        Object result = lookup.invoke(null, level.getSeed(), x, z);
                        if (result instanceof ResourceKey<?> key && alexBiomes.contains(key.location())) {
                            BlockPos center = alexCavesRegionCenter(level, x, z, origin.getY());
                            if (center != null) {
                                long centerDx = (long) center.getX() - origin.getX();
                                long centerDz = (long) center.getZ() - origin.getZ();
                                if (centerDx * centerDx + centerDz * centerDz <= radiusSquared) {
                                    return center;
                                }
                            }
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | LinkageError exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Optional Alex's Caves biome-region lookup became unavailable", exception
            );
            alexCavesLookup = null;
        }
        return null;
    }

    /**
     * Tests whether the seed-based region of an optional mod assigns this biome to the column. Alex's
     * Caves replaces the biome during chunk filling from climate values that the uncached noise source
     * does not reproduce, so the exact 3D shape cannot be predicted remotely. The region is reliable,
     * and generation moves a planned center onto the real biome from the chunk's own data.
     */
    static boolean matches(ServerLevel level, BlockPos pos, ResourceLocation requestedBiome) {
        if (!ALEX_CAVES_NAMESPACE.equals(requestedBiome.getNamespace())) {
            return false;
        }
        Method lookup = alexCavesLookup();
        if (lookup == null) {
            return false;
        }
        try {
            Object result = lookup.invoke(null, level.getSeed(), pos.getX(), pos.getZ());
            return result instanceof ResourceKey<?> key && requestedBiome.equals(key.location());
        } catch (ReflectiveOperationException | RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Optional Alex's Caves biome-region lookup became unavailable", exception
            );
            alexCavesLookup = null;
            return false;
        }
    }

    /**
     * Stable identity of the optional mod's biome region containing this column (one Alex's Caves cave), or
     * {@code null} for biomes that are not region-based.
     */
    static String regionKey(ServerLevel level, BlockPos pos, ResourceLocation biome) {
        Method lookup = alexCavesLookup();
        if (!ALEX_CAVES_NAMESPACE.equals(biome.getNamespace()) || lookup == null) {
            return null;
        }
        Method centerLookup = alexCavesCenterLookup;
        if (centerLookup == null) {
            return null;
        }
        try {
            // The real cave can reach a little past the region's own radius; such edge columns belong to the
            // nearest region of the same biome (regions of one biome are about 2000 blocks apart).
            for (int ring = 0; ring <= REGION_EDGE_SEARCH_RADIUS; ring += REGION_EDGE_SEARCH_STEP) {
                for (int dx = -ring; dx <= ring; dx += REGION_EDGE_SEARCH_STEP) {
                    for (int dz = -ring; dz <= ring; dz += REGION_EDGE_SEARCH_STEP) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != ring) {
                            continue;
                        }
                        int x = pos.getX() + dx;
                        int z = pos.getZ() + dz;
                        Object regionBiome = lookup.invoke(null, level.getSeed(), x, z);
                        if (!(regionBiome instanceof ResourceKey<?> key) || !biome.equals(key.location())) {
                            continue;
                        }
                        Object result = centerLookup.invoke(null, level.getSeed(), x, z);
                        if (result instanceof Vec3 center) {
                            return "region:" + biome + ":" + (long) Math.floor(center.x) + ":" + (long) Math.floor(center.z);
                        }
                    }
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Optional Alex's Caves biome-center lookup became unavailable", exception
            );
            alexCavesCenterLookup = null;
        }
        return null;
    }

    private static BlockPos alexCavesRegionCenter(ServerLevel level, int x, int z, int y) {
        Method centerLookup = alexCavesCenterLookup;
        if (centerLookup == null) {
            return null;
        }
        try {
            Object result = centerLookup.invoke(null, level.getSeed(), x, z);
            if (result instanceof Vec3 center) {
                // Alex's Caves exposes its Voronoi center in quart coordinates.
                return BlockPos.containing(center.x * 4.0D, y, center.z * 4.0D);
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Optional Alex's Caves biome-center lookup became unavailable", exception
            );
            alexCavesCenterLookup = null;
        }
        return null;
    }

    private static Method alexCavesLookup() {
        if (alexCavesLookupResolved) {
            return alexCavesLookup;
        }
        synchronized (OptionalBiomeRegionHints.class) {
            if (!alexCavesLookupResolved) {
                try {
                    Class<?> rarity = Class.forName(ALEX_CAVES_RARITY_CLASS, false,
                            OptionalBiomeRegionHints.class.getClassLoader());
                    alexCavesLookup = rarity.getMethod(
                            "getACBiomeForPosition", long.class, int.class, int.class
                    );
                    alexCavesCenterLookup = rarity.getMethod(
                            "getACBiomeCenterForPosition", long.class, int.class, int.class
                    );
                } catch (ClassNotFoundException | NoSuchMethodException | LinkageError unavailable) {
                    alexCavesLookup = null;
                    alexCavesCenterLookup = null;
                }
                alexCavesLookupResolved = true;
            }
            return alexCavesLookup;
        }
    }
}

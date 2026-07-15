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
    // Stay in the stable core of a late-installed cave biome. The outer Voronoi region only
    // selects which cave may appear; it is not itself proof that every block is in that biome.
    private static final int ALEX_CAVES_SAFE_CORE_RADIUS = 64;
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
     * Tests the seed-based biome region used by an optional mod. Alex's Caves replaces the
     * biome during chunk filling, so it is intentionally not always visible through the
     * vanilla noise-biome source used by remote planning and /locate.
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
            if (!(result instanceof ResourceKey<?> key) || !requestedBiome.equals(key.location())) {
                return false;
            }
            BlockPos center = alexCavesRegionCenter(level, pos.getX(), pos.getZ(), pos.getY());
            if (center == null) {
                return false;
            }
            long dx = (long) center.getX() - pos.getX();
            long dz = (long) center.getZ() - pos.getZ();
            return dx * dx + dz * dz
                    <= (long) ALEX_CAVES_SAFE_CORE_RADIUS * ALEX_CAVES_SAFE_CORE_RADIUS;
        } catch (ReflectiveOperationException | RuntimeException exception) {
            OresAndDrillsMod.LOGGER.debug(
                    "Optional Alex's Caves biome-region lookup became unavailable", exception
            );
            alexCavesLookup = null;
            return false;
        }
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

package dev.world.level.levelgen;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import net.minecraft.core.Holder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;

/** Bounded, per-world cache for immutable noise-biome samples used by generation prediction. */
final class BiomeNoiseCache {
    private static final int MAX_ENTRIES_PER_WORLD = 65_536;
    private static final Map<ServerLevel, Map<QuartKey, Holder<Biome>>> BY_LEVEL =
            new WeakHashMap<>();

    private BiomeNoiseCache() {}

    static Holder<Biome> get(ServerLevel level, int quartX, int quartY, int quartZ) {
        QuartKey key = new QuartKey(quartX, quartY, quartZ);
        synchronized (BY_LEVEL) {
            Map<QuartKey, Holder<Biome>> cache =
                    BY_LEVEL.computeIfAbsent(level, ignored -> newCache());
            Holder<Biome> cached = cache.get(key);
            if (cached != null) return cached;
            Holder<Biome> biome = level.getUncachedNoiseBiome(quartX, quartY, quartZ);
            cache.put(key, biome);
            return biome;
        }
    }

    static void clear() {
        synchronized (BY_LEVEL) {
            BY_LEVEL.clear();
        }
    }

    private static Map<QuartKey, Holder<Biome>> newCache() {
        return new LinkedHashMap<>(256, 0.75F, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<QuartKey, Holder<Biome>> eldest) {
                return size() > MAX_ENTRIES_PER_WORLD;
            }
        };
    }

    private record QuartKey(int x, int y, int z) {}
}

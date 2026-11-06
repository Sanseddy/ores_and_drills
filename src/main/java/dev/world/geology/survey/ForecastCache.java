package dev.world.geology.survey;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.DepositCandidate;
import dev.world.level.levelgen.OreDepositTier;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import net.minecraft.Util;
import net.minecraft.server.level.ServerLevel;

/**
 * Deposit forecasts per cell for one world. A forecast depends only on the seed, the cell and the
 * generation settings, so overlapping surveys reuse it; the confirmed-index and chunk-status checks
 * are applied per survey because they change as the world generates. Bounded, and dropped whole on
 * any configuration or datapack change.
 */
final class ForecastCache {
    static final int CAPACITY = 4096;

    /** {@code exact}: see {@link dev.world.level.levelgen.SeismicDepositForecast#predict}. */
    record Key(OreDepositTier tier, int cellX, int cellZ, boolean exact) {}

    private final Map<Key, List<DepositCandidate>> completed =
            new LinkedHashMap<>(256, 0.75F, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, List<DepositCandidate>> eldest) {
                    return size() > CAPACITY;
                }
            };
    private final Map<Key, CompletableFuture<List<DepositCandidate>>> loading = new HashMap<>();

    synchronized CompletableFuture<List<DepositCandidate>> get(
            ServerLevel level, Key key, Supplier<List<DepositCandidate>> loader) {
        List<DepositCandidate> done = completed.get(key);
        if (done == null && !key.exact()) {
            // An exact forecast is at least as good as a coarse one for the same cell.
            done = completed.get(new Key(key.tier(), key.cellX(), key.cellZ(), true));
        }
        if (done != null) {
            return CompletableFuture.completedFuture(done);
        }
        CompletableFuture<List<DepositCandidate>> pending = loading.get(key);
        if (pending != null) {
            return pending;
        }
        CompletableFuture<List<DepositCandidate>> future =
                CompletableFuture.supplyAsync(loader, Util.backgroundExecutor())
                        .exceptionally(
                                failure -> {
                                    OresAndDrillsMod.LOGGER.debug(
                                            "Could not forecast deposits of cell [{}, {}] in {}",
                                            key.cellX(),
                                            key.cellZ(),
                                            level.dimension().location(),
                                            failure);
                                    return null;
                                });
        CompletableFuture<List<DepositCandidate>> stored =
                future.thenApply(
                        candidates -> {
                            synchronized (ForecastCache.this) {
                                loading.remove(key);
                                // Null marks a cancelled or failed forecast: never cache it.
                                if (candidates == null) {
                                    return List.of();
                                }
                                completed.put(key, candidates);
                                return candidates;
                            }
                        });
        if (!stored.isDone()) {
            loading.put(key, stored);
        }
        return stored;
    }

    synchronized int size() {
        return completed.size();
    }

    synchronized void clear() {
        completed.clear();
        loading.clear();
    }
}

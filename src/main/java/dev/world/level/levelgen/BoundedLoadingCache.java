package dev.world.level.levelgen;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/** Bounded completed LRU plus one in-flight load per key. Loaders never run under the cache lock. */
final class BoundedLoadingCache<K, V> {
    private final int capacity;
    private final Map<K,V> completed = new LinkedHashMap<>(64,0.75F,true);
    private final Map<K,CompletableFuture<V>> loading = new HashMap<>();
    private long hits, misses;
    BoundedLoadingCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Positive capacity required");
        this.capacity = capacity;
    }
    V get(K key, Supplier<V> loader) {
        CompletableFuture<V> pending;
        boolean owner;
        synchronized (this) {
            V cached = completed.get(key);
            if (cached != null) { hits++; return cached; }
            pending = loading.get(key);
            owner = pending == null;
            if (owner) { pending = new CompletableFuture<>(); loading.put(key,pending); misses++; }
            else hits++;
        }
        if (!owner) return pending.join();
        try {
            V value = java.util.Objects.requireNonNull(loader.get());
            synchronized (this) {
                completed.put(key,value);
                if (completed.size()>capacity) completed.remove(completed.keySet().iterator().next());
                loading.remove(key);
                pending.complete(value);
            }
            return value;
        } catch (RuntimeException | Error failure) {
            synchronized (this) { loading.remove(key); pending.completeExceptionally(failure); }
            throw failure;
        }
    }
    synchronized int size() { return completed.size(); }
    synchronized long hits() { return hits; }
    synchronized long misses() { return misses; }
}

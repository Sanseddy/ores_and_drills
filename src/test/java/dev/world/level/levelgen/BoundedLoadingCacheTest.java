package dev.world.level.levelgen;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedLoadingCacheTest {
    @Test
    void simultaneousRequestsShareOneLoadAndDistinctKeysLoadWithoutHoldingTheLock()
            throws Exception {
        BoundedLoadingCache<Integer, String> cache = new BoundedLoadingCache<>(2);
        AtomicInteger loads = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1),
                entered = new CountDownLatch(1),
                release = new CountDownLatch(1);
        try (var pool = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = new ArrayList<Future<String>>();
            for (int i = 0; i < 16; i++)
                futures.add(
                        pool.submit(
                                () -> {
                                    start.await();
                                    return cache.get(
                                            1,
                                            () -> {
                                                loads.incrementAndGet();
                                                entered.countDown();
                                                await(release);
                                                return "same";
                                            });
                                }));
            start.countDown();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            release.countDown();
            for (var future : futures) assertEquals("same", future.get(5, TimeUnit.SECONDS));
            assertEquals(1, loads.get());
            assertEquals(15, cache.hits());
            assertEquals(1, cache.misses());
            CountDownLatch both = new CountDownLatch(2);
            var first =
                    pool.submit(
                            () ->
                                    cache.get(
                                            2,
                                            () -> {
                                                both.countDown();
                                                await(both);
                                                return "two";
                                            }));
            var second =
                    pool.submit(
                            () ->
                                    cache.get(
                                            3,
                                            () -> {
                                                both.countDown();
                                                await(both);
                                                return "three";
                                            }));
            assertEquals("two", first.get(5, TimeUnit.SECONDS));
            assertEquals("three", second.get(5, TimeUnit.SECONDS));
            assertEquals(2, cache.size());
        } finally {
            release.countDown();
        }
    }

    @Test
    void failedLoadsCanRetryAndLruRemainsBounded() {
        BoundedLoadingCache<Integer, Integer> cache = new BoundedLoadingCache<>(2);
        assertThrows(
                IllegalStateException.class,
                () ->
                        cache.get(
                                1,
                                () -> {
                                    throw new IllegalStateException("fixture");
                                }));
        assertEquals(1, cache.get(1, () -> 1));
        assertEquals(2, cache.get(2, () -> 2));
        assertEquals(1, cache.get(1, () -> fail("Must be cached")));
        cache.get(3, () -> 3);
        assertEquals(22, cache.get(2, () -> 22));
        assertEquals(2, cache.size());
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS))
                throw new AssertionError("Loader held the cache lock");
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}

package dev.world.level.levelgen;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StableLongTopKTest {
    @Test
    void primitiveSelectionMatchesTheExistingStableSelector() {
        Random random = new Random(0x5EEDL);
        StableTopK<Long> objects = new StableTopK<>(37);
        StableLongTopK<String> primitives = new StableLongTopK<>(37);

        for (long value = 0; value < 2_000; value++) {
            double distance = random.nextInt(50);
            int depth = random.nextInt(5);
            objects.offer(value, distance, depth);
            primitives.offer(value, "ore", distance, depth);
        }

        assertEquals(objects.toSortedList(), primitives.mapToList((value, ignored) -> value));
    }

    @Test
    void mapperRunsOnlyForRetainedEntries() {
        StableLongTopK<Void> selection = new StableLongTopK<>(16);
        for (int value = 0; value < 10_000; value++) {
            selection.offer(value, null, value, 0);
        }

        AtomicInteger mapped = new AtomicInteger();
        List<Long> values = selection.mapToList((value, ignored) -> {
            mapped.incrementAndGet();
            return value;
        });

        assertEquals(16, mapped.get());
        assertEquals(16, values.size());
    }

    @Test
    void preservesScanOrderWhenNothingWasTrimmed() {
        StableLongTopK<Void> selection = new StableLongTopK<>(8);
        selection.offer(10L, null, 3.0D, 0);
        selection.offer(20L, null, 1.0D, 0);
        selection.offer(30L, null, 2.0D, 0);

        assertEquals(List.of(10L, 20L, 30L), selection.mapToList((value, ignored) -> value));
    }
}

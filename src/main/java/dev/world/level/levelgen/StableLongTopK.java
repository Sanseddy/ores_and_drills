package dev.world.level.levelgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Primitive-long variant of {@link StableTopK}. It is used while scanning deposit lenses so rejected
 * block positions do not need a {@code BlockPos} and {@code DepositPosition} allocation of their own.
 */
final class StableLongTopK<T> {
    private final int limit;
    private final PriorityQueue<Entry<T>> worstFirst;
    private long nextOrder;

    StableLongTopK(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.worstFirst = new PriorityQueue<>(limit, StableLongTopK::compareWorstFirst);
    }

    void offer(long value, T metadata, double distance, int depth) {
        long order = nextOrder++;
        if (worstFirst.size() < limit) {
            worstFirst.add(new Entry<>(value, metadata, distance, depth, order));
            return;
        }

        Entry<T> worst = worstFirst.peek();
        if (compareKeys(distance, depth, order, worst) < 0) {
            worstFirst.poll();
            worst.set(value, metadata, distance, depth, order);
            worstFirst.add(worst);
        }
    }

    <R> List<R> mapToList(EntryMapper<T, R> mapper) {
        List<Entry<T>> sorted = new ArrayList<>(worstFirst);
        if (nextOrder > limit) {
            sorted.sort(StableLongTopK::compareBestFirst);
        } else {
            // Preserve the scanner's original order when no limiting was necessary.
            sorted.sort(Comparator.comparingLong(entry -> entry.order));
        }

        List<R> result = new ArrayList<>(sorted.size());
        for (Entry<T> entry : sorted) {
            result.add(mapper.map(entry.value, entry.metadata));
        }
        return result;
    }

    @FunctionalInterface
    interface EntryMapper<T, R> {
        R map(long value, T metadata);
    }

    private static int compareBestFirst(Entry<?> first, Entry<?> second) {
        return compareKeys(first.distance, first.depth, first.order, second);
    }

    private static int compareWorstFirst(Entry<?> first, Entry<?> second) {
        return compareBestFirst(second, first);
    }

    private static int compareKeys(double distance, int depth, long order, Entry<?> other) {
        int comparison = Double.compare(distance, other.distance);
        if (comparison == 0) {
            comparison = Integer.compare(depth, other.depth);
        }
        if (comparison == 0) {
            comparison = Long.compare(order, other.order);
        }
        return comparison;
    }

    private static final class Entry<T> {
        private long value;
        private T metadata;
        private double distance;
        private int depth;
        private long order;

        private Entry(long value, T metadata, double distance, int depth, long order) {
            set(value, metadata, distance, depth, order);
        }

        private void set(long value, T metadata, double distance, int depth, long order) {
            this.value = value;
            this.metadata = metadata;
            this.distance = distance;
            this.depth = depth;
            this.order = order;
        }
    }
}

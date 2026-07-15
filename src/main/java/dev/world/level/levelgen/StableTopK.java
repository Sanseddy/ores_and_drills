package dev.world.level.levelgen;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/** Bounded stable selection for the distance/depth ordering used by deposit lenses. */
final class StableTopK<T> {
    private final int limit;
    private final PriorityQueue<Entry<T>> worstFirst;
    private long nextOrder;

    StableTopK(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException("limit must be positive");
        }
        this.limit = limit;
        this.worstFirst = new PriorityQueue<>(limit, StableTopK::compareWorstFirst);
    }

    void offer(T candidate, double distance, int depth) {
        long order = nextOrder++;
        if (worstFirst.size() < limit) {
            worstFirst.add(new Entry<>(candidate, distance, depth, order));
            return;
        }

        Entry<T> worst = worstFirst.peek();
        if (compareKeys(distance, depth, order, worst) < 0) {
            // Recycle the evicted entry: the worldgen hot path therefore allocates at most `limit`
            // wrappers even when a lens contains many thousands of candidates.
            worstFirst.poll();
            worst.set(candidate, distance, depth, order);
            worstFirst.add(worst);
        }
    }

    List<T> toSortedList() {
        List<Entry<T>> sorted = new ArrayList<>(worstFirst);
        sorted.sort(StableTopK::compareBestFirst);
        List<T> result = new ArrayList<>(sorted.size());
        for (Entry<T> entry : sorted) {
            result.add(entry.value);
        }
        return result;
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
        private T value;
        private double distance;
        private int depth;
        private long order;

        private Entry(T value, double distance, int depth, long order) {
            set(value, distance, depth, order);
        }

        private void set(T value, double distance, int depth, long order) {
            this.value = value;
            this.distance = distance;
            this.depth = depth;
            this.order = order;
        }
    }
}

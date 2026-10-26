package dev.world.level.levelgen;

import dev.OresAndDrillsMod;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Collectors;
import net.minecraft.resources.ResourceLocation;

/**
 * Per-material counters of automatic TINY/SMALL generation. Source attempts are the original
 * placed-feature attempts that really ran (after the biome check and the selector branch roll);
 * candidates are what the retention gate kept. Neither is a count of placed veins or ore blocks.
 */
public final class SmallDepositDiagnostics {
    public enum Refusal {
        /**
         * The vein found no replaceable {@code #c:stones} host, e.g. its attempt position was in
         * air.
         */
        NO_HOST_ROCK,
        /** The vein would touch another deposit block. */
        OVERLAP,
        /** An additional candidate's re-drawn position fell outside the source biome. */
        OUTSIDE_SOURCE_BIOME,
        /** Technical: the chunk's deposit attachment budget is full. */
        CHUNK_ENTRY_LIMIT,
        /** Technical: the source chain produced more positions than the per-chunk guard. */
        ATTEMPT_LIMIT,
        /** Technical: the persistent ore palette is full. */
        PALETTE_FULL,
        /** Technical: the source's placement chain threw while it was evaluated. */
        PLACEMENT_ERROR,
        /**
         * Technical: the source chain put an attempt outside the chunks whose terrain is generated.
         */
        OUTSIDE_GENERATION_AREA,
        /** The world rejected every block write of the vein. */
        WRITE_REJECTED
    }

    private static final Map<Key, Counters> COUNTERS = new ConcurrentHashMap<>();

    private SmallDepositDiagnostics() {}

    record Key(String dimension, String material) implements Comparable<Key> {
        @Override
        public int compareTo(Key other) {
            int byDimension = dimension.compareTo(other.dimension);
            return byDimension != 0 ? byDimension : material.compareTo(other.material);
        }
    }

    public record Snapshot(
            long sourceAttempts,
            long candidates,
            long tinyCandidates,
            long smallCandidates,
            long placedTiny,
            long placedSmall,
            long oreBlocks,
            long exposedOreBlocks,
            long wallRolls,
            long wallAnchoredDeposits,
            long visibleDeposits,
            long totalReserve,
            long blocksTrimmedToReserve,
            Map<Refusal, Long> refusals) {
        public long placed() {
            return placedTiny + placedSmall;
        }

        public long refusals(Refusal refusal) {
            return refusals.getOrDefault(refusal, 0L);
        }
    }

    static final class Counters {
        final LongAdder sourceAttempts = new LongAdder();
        final LongAdder candidates = new LongAdder();
        final LongAdder tinyCandidates = new LongAdder();
        final LongAdder smallCandidates = new LongAdder();
        final LongAdder placedTiny = new LongAdder();
        final LongAdder placedSmall = new LongAdder();
        final LongAdder oreBlocks = new LongAdder();

        /**
         * Placed ore blocks with an air neighbour: the part of a deposit visible from a cave or the
         * surface.
         */
        final LongAdder exposedOreBlocks = new LongAdder();

        /** Candidates whose rarity roll chose a cave wall (CAVE_WALL placement), placed or not. */
        final LongAdder wallRolls = new LongAdder();

        /** Placed deposits whose vein was built on a cave wall (CAVE_WALL placement). */
        final LongAdder wallAnchoredDeposits = new LongAdder();

        /**
         * Placed deposits with at least one block exposed to air, i.e. visible from a cave or the
         * surface.
         */
        final LongAdder visibleDeposits = new LongAdder();

        final LongAdder totalReserve = new LongAdder();
        final LongAdder blocksTrimmedToReserve = new LongAdder();

        /** Source-attempt count at which the next summary is logged (64, 128, 256, ...). */
        private final java.util.concurrent.atomic.AtomicLong nextLogAt =
                new java.util.concurrent.atomic.AtomicLong(64);

        private final Map<Refusal, LongAdder> refusals = new EnumMap<>(Refusal.class);

        Counters() {
            for (Refusal refusal : Refusal.values()) {
                refusals.put(refusal, new LongAdder());
            }
        }

        void candidate(boolean tiny) {
            candidates.increment();
            (tiny ? tinyCandidates : smallCandidates).increment();
        }

        boolean refuse(Refusal refusal) {
            refusals.get(refusal).increment();
            return false;
        }

        boolean placed(boolean tiny, int blocks, int reserve) {
            (tiny ? placedTiny : placedSmall).increment();
            oreBlocks.add(blocks);
            totalReserve.add(reserve);
            return true;
        }

        Snapshot snapshot() {
            Map<Refusal, Long> refused = new EnumMap<>(Refusal.class);
            refusals.forEach((refusal, adder) -> refused.put(refusal, adder.sum()));
            return new Snapshot(
                    sourceAttempts.sum(),
                    candidates.sum(),
                    tinyCandidates.sum(),
                    smallCandidates.sum(),
                    placedTiny.sum(),
                    placedSmall.sum(),
                    oreBlocks.sum(),
                    exposedOreBlocks.sum(),
                    wallRolls.sum(),
                    wallAnchoredDeposits.sum(),
                    visibleDeposits.sum(),
                    totalReserve.sum(),
                    blocksTrimmedToReserve.sum(),
                    Map.copyOf(refused));
        }
    }

    static Counters forMaterial(ResourceLocation dimension, String material) {
        return COUNTERS.computeIfAbsent(
                new Key(dimension.toString(), material), ignored -> new Counters());
    }

    /** Sorted copy keyed {@code dimension|material}. */
    public static Map<String, Snapshot> snapshot() {
        Map<String, Snapshot> result = new TreeMap<>();
        COUNTERS.forEach(
                (key, counters) ->
                        result.put(key.dimension() + "|" + key.material(), counters.snapshot()));
        return result;
    }

    public static void reset() {
        COUNTERS.clear();
    }

    /**
     * Logs a material's summary each time its source attempts pass the next power of two. Attempts
     * grow by a whole chunk's worth per call, so an exact power of two is usually skipped; crossing
     * it is not.
     */
    static void maybeLog(ResourceLocation dimension, String material, Counters counters) {
        long attempts = counters.sourceAttempts.sum();
        long threshold = counters.nextLogAt.get();
        if (attempts < threshold || !OresAndDrillsMod.LOGGER.isDebugEnabled()) {
            return;
        }
        long next = Long.highestOneBit(attempts) << 1;
        if (counters.nextLogAt.compareAndSet(threshold, Math.max(next, threshold << 1))) {
            OresAndDrillsMod.LOGGER.debug(
                    "Ore deposits TINY/SMALL: {}",
                    format(dimension + "|" + material, counters.snapshot()));
        }
    }

    public static void logAll() {
        if (!OresAndDrillsMod.LOGGER.isDebugEnabled()) {
            return;
        }
        snapshot()
                .forEach(
                        (key, snapshot) ->
                                OresAndDrillsMod.LOGGER.debug(
                                        "Ore deposits TINY/SMALL: {}", format(key, snapshot)));
    }

    public static String format(String key, Snapshot snapshot) {
        double kept =
                snapshot.sourceAttempts() == 0
                        ? 0.0D
                        : snapshot.candidates() / (double) snapshot.sourceAttempts();
        String refused =
                snapshot.refusals().entrySet().stream()
                        .filter(entry -> entry.getValue() > 0)
                        .sorted(Map.Entry.comparingByKey())
                        .map(
                                entry ->
                                        entry.getKey().name().toLowerCase(Locale.ROOT)
                                                + "="
                                                + entry.getValue())
                        .collect(Collectors.joining(", "));
        return String.format(
                Locale.ROOT,
                "%s | source attempts=%d | candidates=%d (%.2f%%; tiny=%d, small=%d) | placed"
                        + " tiny=%d, small=%d (wall rolls=%d, on cave walls=%d, visible from air=%d) |"
                        + " ore blocks=%d (exposed to air=%d) | reserve=%d | trimmed to reserve=%d |"
                        + " refusals=[%s]",
                key,
                snapshot.sourceAttempts(),
                snapshot.candidates(),
                kept * 100.0D,
                snapshot.tinyCandidates(),
                snapshot.smallCandidates(),
                snapshot.placedTiny(),
                snapshot.placedSmall(),
                snapshot.wallRolls(),
                snapshot.wallAnchoredDeposits(),
                snapshot.visibleDeposits(),
                snapshot.oreBlocks(),
                snapshot.exposedOreBlocks(),
                snapshot.totalReserve(),
                snapshot.blocksTrimmedToReserve(),
                refused);
    }
}

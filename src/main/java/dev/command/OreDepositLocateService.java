package dev.command;

import dev.OresAndDrillsMod;
import dev.world.level.levelgen.ConfirmedDepositIndex;
import dev.world.level.levelgen.DepositTerrainValidator;
import dev.world.level.levelgen.IncrementalDepositSearch;
import dev.world.level.levelgen.LocatedDeposit;
import dev.world.level.levelgen.OreDepositTier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** Tick-budgeted scheduler for ore-deposit locate requests. */
public final class OreDepositLocateService {
    private static final long TICK_BUDGET_NANOS = 3_000_000L;
    private static final int OPERATIONS_PER_SLICE = 256;
    private static final int MAX_ACTIVE_SEARCHES = 64;
    private static final int MAX_SUBSCRIBERS_PER_SEARCH = 64;
    private static final int MAX_CACHED_CONFIRMED_RESULTS = 128;
    private static final Map<MinecraftServer, ServerState> SERVERS = new IdentityHashMap<>();
    private static long configurationEpoch;

    private OreDepositLocateService() {}

    public static synchronized SubmitResult submit(
            ServerLevel level,
            BlockPos origin,
            ResourceLocation requestedOre,
            List<OreDepositTier> tiers,
            int searchRadius,
            Completion completion) {
        ServerState state =
                SERVERS.computeIfAbsent(level.getServer(), ignored -> new ServerState());
        QueryKey key =
                new QueryKey(
                        level.dimension().location(),
                        level.getSeed(),
                        origin.getX(),
                        origin.getZ(),
                        requestedOre,
                        List.copyOf(tiers),
                        searchRadius,
                        configurationEpoch,
                        ConfirmedDepositIndex.get(level).revision());
        LocatedDeposit cached = state.completed.get(key);
        if (cached != null) {
            completion.complete(Optional.of(cached), null, new Metrics(0, 0, 0));
            return SubmitResult.CACHED;
        }
        LocateJob existing = state.active.get(key);
        if (existing != null) {
            if (existing.completions.size() >= MAX_SUBSCRIBERS_PER_SEARCH) return SubmitResult.BUSY;
            existing.completions.add(completion);
            return SubmitResult.JOINED;
        }
        if (state.active.size() >= MAX_ACTIVE_SEARCHES) return SubmitResult.BUSY;

        LocateJob job =
                new LocateJob(key, level, origin, requestedOre, tiers, searchRadius, completion);
        state.active.put(key, job);
        state.queue.addLast(job);
        return SubmitResult.STARTED;
    }

    public static synchronized void onServerTick(ServerTickEvent.Post event) {
        ServerState state = SERVERS.get(event.getServer());
        if (state == null || state.queue.isEmpty()) return;
        long deadline = System.nanoTime() + TICK_BUDGET_NANOS;
        int visits = state.queue.size();
        while (visits-- > 0 && !state.queue.isEmpty() && System.nanoTime() < deadline) {
            LocateJob job = state.queue.removeFirst();
            job.advance(deadline);
            if (job.done) {
                state.active.remove(job.key);
                if (job.failure == null && job.best != null && job.best.confirmed()) {
                    state.completed.put(job.key, job.best);
                }
                job.notifyCompletions();
            } else {
                state.queue.addLast(job);
            }
        }
    }

    public static synchronized void onServerStopped(ServerStoppedEvent event) {
        ServerState state = SERVERS.remove(event.getServer());
        // Connections and packet listeners are already shutting down here. Drop callbacks silently:
        // reporting a routine cancellation would attempt to send chat over a closing connection.
        if (state != null) state.cancel(false);
    }

    /** Cancels stale work and results after a config/datapack change. */
    public static synchronized void invalidateAll() {
        configurationEpoch++;
        for (ServerState state : SERVERS.values()) state.cancel(true);
        SERVERS.clear();
    }

    public enum SubmitResult {
        STARTED,
        JOINED,
        CACHED,
        BUSY
    }

    @FunctionalInterface
    public interface Completion {
        void complete(Optional<LocatedDeposit> result, Throwable failure, Metrics metrics);
    }

    public record Metrics(long elapsedMillis, long cellsChecked, long candidatesChecked) {}

    private static final class ServerState {
        private final Map<QueryKey, LocateJob> active = new LinkedHashMap<>();
        private final ArrayDeque<LocateJob> queue = new ArrayDeque<>();
        private final Map<QueryKey, LocatedDeposit> completed =
                new LinkedHashMap<>(32, 0.75F, true) {
                    @Override
                    protected boolean removeEldestEntry(
                            Map.Entry<QueryKey, LocatedDeposit> eldest) {
                        return size() > MAX_CACHED_CONFIRMED_RESULTS;
                    }
                };

        private void cancel(boolean notifyCompletions) {
            IllegalStateException cancellation =
                    notifyCompletions
                            ? new IllegalStateException(
                                    "Locate search cancelled after configuration reload")
                            : null;
            for (LocateJob job : queue) {
                job.done = true;
                if (notifyCompletions) {
                    job.failure = cancellation;
                    job.notifyCompletions();
                } else {
                    job.completions.clear();
                }
            }
            active.clear();
            queue.clear();
            completed.clear();
        }
    }

    private static final class LocateJob {
        private final QueryKey key;
        private final List<IncrementalDepositSearch> searches;
        private final List<Completion> completions = new ArrayList<>();
        private final long startedNanos = System.nanoTime();
        private int nextSearch;
        private int unfinishedSearches;
        private LocatedDeposit best;
        private Throwable failure;
        private boolean done;

        private LocateJob(
                QueryKey key,
                ServerLevel level,
                BlockPos origin,
                ResourceLocation requestedOre,
                List<OreDepositTier> tiers,
                int searchRadius,
                Completion completion) {
            this.key = key;
            DepositTerrainValidator validator = new DepositTerrainValidator();
            this.searches =
                    tiers.stream()
                            .map(
                                    tier ->
                                            new IncrementalDepositSearch(
                                                    validator,
                                                    level,
                                                    origin,
                                                    requestedOre,
                                                    tier,
                                                    searchRadius))
                            .toList();
            this.unfinishedSearches = searches.size();
            completions.add(completion);
        }

        private void advance(long deadline) {
            try {
                int operations = 0;
                while (operations++ < OPERATIONS_PER_SLICE && System.nanoTime() < deadline) {
                    IncrementalDepositSearch search = searches.get(nextSearch);
                    nextSearch = (nextSearch + 1) % searches.size();
                    boolean wasDone = search.isDone();
                    if (!wasDone) search.advance(deadline, 1);

                    LocatedDeposit found = search.currentResult().orElse(null);
                    if (found != null) {
                        // Navigator semantics: the deterministic tier/cell round-robin stops at the
                        // first fully validated deposit instead of proving a global nearest result.
                        best = found;
                        done = true;
                        return;
                    }
                    if (!wasDone && search.isDone() && --unfinishedSearches == 0) {
                        done = true;
                        return;
                    }
                }
            } catch (RuntimeException exception) {
                failure = exception;
                done = true;
                OresAndDrillsMod.LOGGER.debug("Incremental ore-deposit locate failed", exception);
            }
        }

        private void notifyCompletions() {
            long cells = searches.stream().mapToLong(IncrementalDepositSearch::cellsChecked).sum();
            long candidates =
                    searches.stream().mapToLong(IncrementalDepositSearch::candidatesChecked).sum();
            Metrics metrics =
                    new Metrics((System.nanoTime() - startedNanos) / 1_000_000L, cells, candidates);
            Optional<LocatedDeposit> result = Optional.ofNullable(best);
            for (Completion completion : List.copyOf(completions)) {
                try {
                    completion.complete(result, failure, metrics);
                } catch (RuntimeException callbackFailure) {
                    OresAndDrillsMod.LOGGER.debug(
                            "Could not deliver ore-deposit locate result", callbackFailure);
                }
            }
            completions.clear();
        }
    }

    private record QueryKey(
            ResourceLocation dimension,
            long seed,
            int originX,
            int originZ,
            ResourceLocation ore,
            List<OreDepositTier> tiers,
            int radius,
            long configEpoch,
            long depositRevision) {}
}

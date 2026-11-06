package dev.world.geology.survey;

import dev.config.SeismicSurveyConfig;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.neoforged.neoforge.event.level.LevelEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/**
 * Tick-budgeted scheduler of seismic surveys. Each server tick it spends at most the configured
 * budget on the server-thread part of the running surveys; everything heavier runs on Minecraft's
 * background executor over immutable snapshots.
 */
public final class SeismicSurveyService {
    /** Surveys whose station has not asked about them for this long are abandoned. */
    private static final long ABANDON_TICKS = 20L * 60L * 10L;

    private static final Map<MinecraftServer, State> SERVERS = new IdentityHashMap<>();
    private static final Map<ServerLevel, ForecastCache> FORECASTS = new WeakHashMap<>();
    private static volatile long longestTickNanos;

    private SeismicSurveyService() {}

    private static final class State {
        private final Map<UUID, SurveyJob> byStation = new HashMap<>();
        private final Map<UUID, Long> lastPolled = new HashMap<>();
        private final ArrayDeque<SurveyJob> queue = new ArrayDeque<>();
    }

    /**
     * Starts {@code request}, or returns the station's running survey when it has the same
     * configuration. A different configuration replaces the running survey.
     */
    public static synchronized SurveyJob submit(ServerLevel level, SurveyRequest request) {
        State state = SERVERS.computeIfAbsent(level.getServer(), ignored -> new State());
        SurveyJob existing = state.byStation.get(request.stationId());
        if (existing != null
                && !existing.failed()
                && existing.level() == level
                && existing.request().jobHash() == request.jobHash()) {
            state.lastPolled.put(request.stationId(), level.getGameTime());
            return existing;
        }
        if (existing != null) {
            existing.cancel();
            state.queue.remove(existing);
        }
        SurveyJob job = new SurveyJob(level, request, forecasts(level));
        state.byStation.put(request.stationId(), job);
        state.lastPolled.put(request.stationId(), level.getGameTime());
        state.queue.addLast(job);
        return job;
    }

    /** The station's survey, if one is running or finished but not yet collected. */
    @Nullable
    public static synchronized SurveyJob find(ServerLevel level, UUID station) {
        State state = SERVERS.get(level.getServer());
        if (state == null) {
            return null;
        }
        SurveyJob job = state.byStation.get(station);
        if (job != null) {
            state.lastPolled.put(station, level.getGameTime());
        }
        return job;
    }

    /** Forgets a station's survey after its result was stored, or cancels it. */
    public static synchronized void release(ServerLevel level, UUID station) {
        State state = SERVERS.get(level.getServer());
        if (state == null) {
            return;
        }
        SurveyJob job = state.byStation.remove(station);
        state.lastPolled.remove(station);
        if (job != null) {
            if (!job.isDone()) {
                job.cancel();
            }
            state.queue.remove(job);
        }
    }

    public static synchronized void onServerTick(ServerTickEvent.Post event) {
        State state = SERVERS.get(event.getServer());
        if (state == null || state.queue.isEmpty()) {
            return;
        }
        long start = System.nanoTime();
        long deadline = start + SeismicSurveyConfig.TICK_BUDGET_MICROS.get() * 1_000L;
        int visits = state.queue.size();
        while (visits-- > 0 && !state.queue.isEmpty() && System.nanoTime() < deadline) {
            SurveyJob job = state.queue.removeFirst();
            Long polled = state.lastPolled.get(job.request().stationId());
            long now = job.level().getGameTime();
            if (polled == null || now - polled > ABANDON_TICKS) {
                job.cancel();
                state.byStation.remove(job.request().stationId(), job);
                state.lastPolled.remove(job.request().stationId());
                continue;
            }
            if (!job.advance(deadline)) {
                state.queue.addLast(job);
            }
        }
        longestTickNanos = Math.max(longestTickNanos, System.nanoTime() - start);
    }

    public static synchronized void onLevelUnload(LevelEvent.Unload event) {
        if (!(event.getLevel() instanceof ServerLevel level)) {
            return;
        }
        State state = SERVERS.get(level.getServer());
        if (state != null) {
            state.queue.removeIf(
                    job -> {
                        if (job.level() != level) {
                            return false;
                        }
                        job.cancel();
                        state.byStation.remove(job.request().stationId(), job);
                        return true;
                    });
        }
        ForecastCache cache = FORECASTS.remove(level);
        if (cache != null) {
            cache.clear();
        }
    }

    public static synchronized void onServerStopped(ServerStoppedEvent event) {
        State state = SERVERS.remove(event.getServer());
        if (state != null) {
            state.queue.forEach(SurveyJob::cancel);
        }
        FORECASTS.clear();
    }

    /** Drops every forecast after a configuration or datapack change. */
    public static synchronized void invalidateForecasts() {
        FORECASTS.values().forEach(ForecastCache::clear);
        FORECASTS.clear();
    }

    static synchronized ForecastCache forecasts(ServerLevel level) {
        return FORECASTS.computeIfAbsent(level, ignored -> new ForecastCache());
    }

    /** Longest server-thread time one tick of this service has taken, for diagnostics. */
    public static long longestTickNanos() {
        return longestTickNanos;
    }

    public static void resetLongestTick() {
        longestTickNanos = 0L;
    }

    /** Surveys queued on {@code server}, for tests and diagnostics. */
    public static synchronized int running(MinecraftServer server) {
        State state = SERVERS.get(server);
        return state == null ? 0 : state.queue.size();
    }
}

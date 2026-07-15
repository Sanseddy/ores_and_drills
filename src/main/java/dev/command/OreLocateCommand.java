package dev.command;

import dev.FactoryExpansionMod;
import dev.registry.ModAttachments;
import dev.registry.ModBlocks;
import dev.world.level.levelgen.OreDepositChunkData;
import dev.world.level.levelgen.OreDepositFeature;
import dev.world.level.levelgen.OreDepositPaletteData;
import dev.world.level.levelgen.LargeDepositSpatialIndex;
import dev.world.level.levelgen.OreSpawnDimensions;
import dev.world.level.levelgen.OreUnifier;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentUtils;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.TickTask;
import net.minecraft.server.level.ChunkResult;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * {@code /locate oredeposit <ore>} or {@code /locate oredeposit <ore> <tier>} — finds the nearest ore
 * deposit within a bounded requested radius. The deterministic seed plan is searched first; the persistent
 * large-deposit index is used afterwards to verify any previously generated records the plan did not return.
 */
public final class OreLocateCommand {
    /** Default search radius used when the optional command argument is omitted. */
    private static final int DEFAULT_BLOCK_RADIUS = 16_000;
    /**
     * A larger radius multiplies both prediction work and the distance to an eventual generated candidate.
     * Keep manual searches bounded so a command cannot starve normal player-driven chunk generation.
     */
    private static final int MAX_BLOCK_RADIUS = 32_000;
    private static final int ANY_TIER = -1;
    /**
     * Floor for the per-search prediction-check budget (see {@code LocateSearch#maxPredictionChecksPerTier}).
     * A flat 32,768 silently truncated the search area far short of the requested radius.
     * predictsMaterialForChunk() only samples
     * biomes and deterministic RNG (no chunk generation), so the actual per-search budget is sized to the
     * requested radius instead - this constant now only guards against a degenerate near-zero radius.
     */
    private static final int MAX_PREDICTION_CHECKS_PER_TIER = 32_768;
    /**
     * Limits main-thread prediction work between asynchronous chunk-load requests. predictsMaterialForChunk()
     * is a cached-parameter biome sample plus a few deterministic RNG draws - no chunk generation or I/O -
     * so this can run fairly high without risking tick time.
     */
    private static final int PREDICTION_CHECKS_PER_STEP = 2_048;
    /**
     * Forced toxic-cave probing samples a full vertical biome column. Batch it, but let the wall-clock
     * guard below stop the batch before it can consume a tick: one probe per tick made a nearby uranium
     * search visibly take minutes even when every probe was fast on the current machine.
     */
    private static final int EXPENSIVE_PREDICTION_CHECKS_PER_STEP = 64;
    /** Wall-clock guard for unexpectedly expensive modded biome samplers on the server thread. */
    private static final long MAX_PREDICTION_NANOS_PER_STEP = 5_000_000L;
    /**
     * Every real generation attempt is a genuine, permanent chunk generation - unlike prediction checks,
     * this isn't free: every candidate this generates stays on disk and has to be unloaded/saved like any
     * other chunk, so a large cap here means a single "not found" search can leave thousands of never-visited
     * chunks behind, which then all have to be flushed during the next clean shutdown.
     */
    /**
     * TINY/SMALL prediction already applies the same seed-derived retention gate as generation. The source
     * placed-feature stream and terrain can still reject a predicted chunk. A small fixed cap keeps a command
     * from generating a remote strip of the world and starving the player's normal chunk-loading queue.
     */
    private static final int MAX_FORCE_GENERATED_SMALL_CANDIDATES_PER_TIER = 16;
    private static final int MAX_FORCE_GENERATED_CANDIDATES_PER_TIER = 8;
    /** Maximum horizontal reach of a generated lens beyond its fixed planned origin. */
    private static final int DEPOSIT_CENTER_MARGIN = OreDepositFeature.MAX_UNDERGROUND_RADIUS;
    /** The index stores one real block, not a geometric center; a lens can extend two radii from it. */
    private static final int KNOWN_RECORD_FOOTPRINT_MARGIN = OreDepositFeature.MAX_DEPOSIT_FOOTPRINT_DIAMETER;
    /** Maximum horizontal correction from a planned position to a compatible #c:stones host. */
    private static final int TAGGED_STONE_RELOCATION_MARGIN =
            OreDepositFeature.MAX_TAGGED_STONE_RELOCATION_RADIUS;
    /** Maximum distance from a source-chunk center to any generated deposit block. */
    private static final int SOURCE_CHUNK_DEPOSIT_MARGIN =
            12 + TAGGED_STONE_RELOCATION_MARGIN + DEPOSIT_CENTER_MARGIN;
    /** A new index record stores the corrected center; legacy records may point at another lens block. */
    private static final int SOURCE_CHUNK_RECORD_MARGIN =
            TAGGED_STONE_RELOCATION_MARGIN + DEPOSIT_CENTER_MARGIN;
    /** Ring bounds use the same complete source-chunk-to-footprint reach as candidate filtering. */
    private static final int RING_SEARCH_MARGIN = SOURCE_CHUNK_DEPOSIT_MARGIN;
    private static final SimpleCommandExceptionType ERROR_UNKNOWN_ORE =
            new SimpleCommandExceptionType(Component.translatable("commands.ores_and_drills.locate.unknown_ore"));
    private static final SimpleCommandExceptionType ERROR_UNKNOWN_TIER =
            new SimpleCommandExceptionType(Component.translatable("commands.ores_and_drills.locate.unknown_type"));

    private OreLocateCommand() {
    }

    public static void register(RegisterCommandsEvent event) {
        // Merges onto vanilla's own "locate" command node (Brigadier merges same-named nodes on
        // registration) instead of registering a separate top-level "oredeposit" command.
        event.getDispatcher().register(
                Commands.literal("locate")
                        .then(Commands.literal("oredeposit")
                                .requires(source -> source.hasPermission(2))
                                .then(Commands.argument("ore", ResourceLocationArgument.id())
                                        .suggests(OreLocateCommand::suggestOres)
                                        .executes(context -> locate(context, ANY_TIER))
                                        // /locate oredeposit <ore> <type> — e.g. "minecraft:copper_ore small"
                                        .then(Commands.argument("type", StringArgumentType.word())
                                                .suggests(OreLocateCommand::suggestTiers)
                                                .executes(context -> locate(context, tierArgument(context)))
                                                // /locate oredeposit <ore> <type> <radius>
                                                .then(Commands.argument("radius", IntegerArgumentType.integer(1, MAX_BLOCK_RADIUS))
                                                        .executes(context -> locate(
                                                                context,
                                                                tierArgument(context),
                                                                IntegerArgumentType.getInteger(context, "radius")
                                                        ))))))
        );
    }

    /** Tab-completion only offers unified (canonical) ores, so duplicate cross-mod entries for the same material don't clutter the list. */
    private static CompletableFuture<Suggestions> suggestOres(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        List<ResourceLocation> ores = new ArrayList<>();
        for (ResourceLocation id : OreDepositPaletteData.get(context.getSource().getLevel()).ores()) {
            Block block = BuiltInRegistries.BLOCK.get(id);
            if (block != null && OreUnifier.isCanonicalSource(block)) {
                ores.add(id);
            }
        }
        return SharedSuggestionProvider.suggestResource(ores, builder);
    }

    private static CompletableFuture<Suggestions> suggestTiers(CommandContext<CommandSourceStack> context, SuggestionsBuilder builder) {
        return SharedSuggestionProvider.suggest(List.of("tiny", "small", "medium", "large"), builder);
    }

    private static int tierArgument(CommandContext<CommandSourceStack> context) throws CommandSyntaxException {
        return tierByName(StringArgumentType.getString(context, "type"));
    }

    private static int tierByName(String name) throws CommandSyntaxException {
        return switch (name.toLowerCase(Locale.ROOT)) {
            case "tiny" -> OreDepositFeature.TIER_TINY;
            case "small" -> OreDepositFeature.TIER_SMALL;
            case "medium" -> OreDepositFeature.TIER_MEDIUM;
            case "large" -> OreDepositFeature.TIER_LARGE;
            default -> throw ERROR_UNKNOWN_TIER.create();
        };
    }

    private static int locate(CommandContext<CommandSourceStack> context, int tierFilter) throws CommandSyntaxException {
        return locate(context, tierFilter, DEFAULT_BLOCK_RADIUS);
    }

    private static int locate(CommandContext<CommandSourceStack> context, int tierFilter, int blockRadius)
            throws CommandSyntaxException {
        CommandSourceStack source = context.getSource();
        ServerLevel level = source.getLevel();
        ResourceLocation oreId = ResourceLocationArgument.getId(context, "ore");
        OreSpawnDimensions.ensureOriginalPlacedFeaturesScanned(level.registryAccess());

        List<ResourceLocation> ores = OreDepositPaletteData.get(level).ores();
        String materialKey = materialKey(oreId);
        if (materialKey.isEmpty()) {
            throw ERROR_UNKNOWN_ORE.create();
        }
        Set<Integer> oreIndices = matchingOreIndices(ores, oreId);
        if (oreIndices.isEmpty()) {
            throw ERROR_UNKNOWN_ORE.create();
        }

        BlockPos origin = BlockPos.containing(source.getPosition());
        new LocateSearch(source, level, origin, oreId, materialKey, oreIndices, blockRadius, tierFilter).advance();
        return 1;
    }

    private static String materialKey(ResourceLocation requestedOreId) {
        Block requestedBlock = BuiltInRegistries.BLOCK.get(requestedOreId);
        return requestedBlock == null ? "" : OreUnifier.materialKeyFor(requestedBlock);
    }

    private static Set<Integer> matchingOreIndices(List<ResourceLocation> ores, ResourceLocation requestedOreId) {
        String requestedMaterial = materialKey(requestedOreId);
        if (requestedMaterial.isEmpty()) {
            return Set.of();
        }

        Set<Integer> indices = new HashSet<>();
        for (int index = 0; index < ores.size(); index++) {
            Block candidate = BuiltInRegistries.BLOCK.get(ores.get(index));
            if (candidate != null && requestedMaterial.equals(OreUnifier.materialKeyFor(candidate))) {
                indices.add(index);
            }
        }
        return indices;
    }

    private static void sendLocatedResult(CommandSourceStack source, BlockPos origin, ResourceLocation oreId, BlockPos found) {
        int distance = (int) Math.sqrt(horizontalDistanceSqr(origin, found));
        Component coordsComponent = ComponentUtils.wrapInSquareBrackets(
                        Component.translatable("chat.coordinates", found.getX(), found.getY(), found.getZ()))
                .withStyle(style -> style.withColor(ChatFormatting.GREEN)
                        .withClickEvent(new ClickEvent(
                                ClickEvent.Action.SUGGEST_COMMAND,
                                "/tp @s " + found.getX() + " " + found.getY() + " " + found.getZ()
                        ))
                        .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.translatable("chat.coordinates.tooltip"))));
        source.sendSuccess(() -> Component.translatable(
                "commands.ores_and_drills.locate.success",
                Component.translationArg(oreId),
                coordsComponent,
                distance
        ), false);
    }

    static double horizontalDistanceSqr(BlockPos first, BlockPos second) {
        return LocateDistanceMath.horizontalDistanceSqr(
                first.getX(), first.getZ(), second.getX(), second.getZ()
        );
    }

    /**
     * Incremental, server-thread-safe force-generation search. Each step performs a bounded number of
     * predictions, then waits for one FULL-chunk future before continuing on the server thread. This
     * preserves /locate's unexplored-world behavior without blocking a tick on getChunk().
     */
    private static final class LocateSearch {
        private final CommandSourceStack source;
        private final ServerLevel level;
        private final BlockPos origin;
        private final ResourceLocation oreId;
        private final String materialKey;
        private final Set<Integer> oreIndices;
        private final int blockRadius;
        private final double maxDistSqr;
        private final int lastTier;
        private final int maxPredictionChecksPerTier;
        private final List<LargeDepositSpatialIndex.DepositRecord> knownRecords;
        private final int originChunkX;
        private final int originChunkZ;
        private final int chunkRadius;

        private BlockPos closest;
        private double closestDistSqr = Double.MAX_VALUE;
        private int tier;
        private int ring;
        private int ringCursor;
        private int predictionChecks;
        private int generatedCandidates;
        private int knownRecordIndex;
        private boolean anchorGrid;
        private int ringStepChunks;
        private int ringRadius;
        private boolean complete;

        LocateSearch(
                CommandSourceStack source,
                ServerLevel level,
                BlockPos origin,
                ResourceLocation oreId,
                String materialKey,
                Set<Integer> oreIndices,
                int blockRadius,
                int tierFilter
        ) {
            this.source = source;
            this.level = level;
            this.origin = origin;
            this.oreId = oreId;
            this.materialKey = materialKey;
            this.oreIndices = oreIndices;
            this.blockRadius = blockRadius;
            this.maxDistSqr = (double) blockRadius * blockRadius;
            this.originChunkX = origin.getX() >> 4;
            this.originChunkZ = origin.getZ() >> 4;
            this.chunkRadius = LocateDistanceMath.chunkRadius(blockRadius, RING_SEARCH_MARGIN);

            boolean uraniumForcedToLarge = tierFilter == ANY_TIER
                    && materialKey.equals("uranium") && ModList.get().isLoaded("alexscaves");
            this.tier = tierFilter == ANY_TIER
                    ? (uraniumForcedToLarge ? OreDepositFeature.TIER_LARGE : 0)
                    : tierFilter;
            this.lastTier = tierFilter == ANY_TIER ? OreDepositFeature.TIER_COUNT - 1 : tierFilter;

            // Budget the search to actually cover the requested radius: the ring search visits every
            // chunk out to chunkRadius (a (2*chunkRadius+1) square), so anything less than that area
            // guarantees giving up before the edge of the requested radius is ever reached.
            long fullSearchArea = (long) (2 * chunkRadius + 1) * (2 * chunkRadius + 1);
            long budget = Math.max(MAX_PREDICTION_CHECKS_PER_TIER, fullSearchArea + PREDICTION_CHECKS_PER_STEP);
            this.maxPredictionChecksPerTier = (int) Math.min(Integer.MAX_VALUE - 1L, budget);

            // MEDIUM/LARGE deposits persist their real tagged-stone-corrected center in this SavedData index as
            // soon as they're generated, including ones that generated naturally from ordinary chunk loading
            // long before this /locate call. Seed anchors are deliberately searched first; these records are
            // only a fallback for existing physical deposits after the deterministic search is exhausted.
            this.knownRecords = lastTier >= OreDepositFeature.TIER_MEDIUM
                    ? LargeDepositSpatialIndex.get(level).recordsWithin(
                            origin, blockRadius + KNOWN_RECORD_FOOTPRINT_MARGIN, tierFilter, materialKey
                    )
                            .stream()
                            .sorted(Comparator.comparingDouble(record -> horizontalDistanceSqr(record.center(), origin)))
                            .toList()
                    : List.of();
            configureTierSearch();
        }

        void advance() {
            if (complete) {
                return;
            }
            if (tier <= lastTier) {
                advanceRingSearch();
                return;
            }
            if (advanceKnownRecords()) {
                return;
            }
            finish();
        }

        /** @return true if a chunk load was requested and continuation is asynchronous. */
        private boolean advanceKnownRecords() {
            while (knownRecordIndex < knownRecords.size()) {
                LargeDepositSpatialIndex.DepositRecord record = knownRecords.get(knownRecordIndex++);
                if (!LocateDistanceMath.centerCanReachRadius(
                        record.center().getX(), record.center().getZ(), origin.getX(), origin.getZ(),
                        blockRadius, KNOWN_RECORD_FOOTPRINT_MARGIN
                )) {
                    continue;
                }
                requestChunk(record.center().getX() >> 4, record.center().getZ() >> 4, record.tier());
                return true;
            }
            return false;
        }

        private void advanceRingSearch() {
            int processed = 0;
            int stepLimit = OreDepositFeature.usesForcedAlexUraniumAnchorGrid(materialKey, tier)
                    ? EXPENSIVE_PREDICTION_CHECKS_PER_STEP
                    : PREDICTION_CHECKS_PER_STEP;
            long stepStarted = System.nanoTime();

            while (processed++ < stepLimit) {
                if (processed > 1 && System.nanoTime() - stepStarted >= MAX_PREDICTION_NANOS_PER_STEP) {
                    break;
                }
                if (tier > lastTier) {
                    advance();
                    return;
                }
                if (predictionChecks >= maxPredictionChecksPerTier) {
                    nextTier();
                    continue;
                }
                if (generatedCandidates >= generatedCandidateLimit()) {
                    // An unqualified /locate must still check the remaining tiers. Previously a miss in
                    // TINY stopped the whole command, so SMALL/MEDIUM/LARGE were never considered.
                    if (tier < lastTier) {
                        nextTier();
                        continue;
                    }
                    // A verified deposit is more useful than an incomplete-search failure. It may not be
                    // provably nearest after the final safety cap, but it is always a real matching block.
                    if (closest != null) {
                        finish();
                        return;
                    }
                    // The seed plan had priority up to its safety budget. Before reporting an incomplete
                    // search, still verify any already-persisted MEDIUM/LARGE records; they require no new
                    // broad candidate scan and may provide a real result immediately.
                    if (advanceKnownRecords()) {
                        return;
                    }
                    finishAtGenerationLimit();
                    return;
                }
                // Forced uranium now uses a one-chunk grid: every chunk is a deterministic candidate and
                // the full-height Toxic Caves biome check is the only prediction filter. Keep the generic
                // grid traversal here so locate remains synchronized if that spacing is made configurable.
                long packedOffset = nextRingOffset();
                if (packedOffset == LocateDistanceMath.NO_RING_OFFSET) {
                    nextTier();
                    continue;
                }

                int chunkX;
                int chunkZ;
                if (anchorGrid) {
                    int regionX = Math.floorDiv(originChunkX, ringStepChunks)
                            + LocateDistanceMath.ringOffsetX(packedOffset);
                    int regionZ = Math.floorDiv(originChunkZ, ringStepChunks)
                            + LocateDistanceMath.ringOffsetZ(packedOffset);
                    long anchor = OreDepositFeature.locateAnchorChunkKey(level, regionX, regionZ, materialKey, tier);
                    chunkX = ChunkPos.getX(anchor);
                    chunkZ = ChunkPos.getZ(anchor);
                } else {
                    chunkX = originChunkX + LocateDistanceMath.ringOffsetX(packedOffset);
                    chunkZ = originChunkZ + LocateDistanceMath.ringOffsetZ(packedOffset);
                }
                predictionChecks++;

                if (!isPredictedCandidate(chunkX, chunkZ)) {
                    continue;
                }

                // A Toxic Caves candidate only proves that this chunk may host uranium. The final position
                // is selected from a real compatible stone block, so returning the geometric chunk center
                // can point into air. Generate and scan this bounded candidate just like other tiers.
                generatedCandidates++;
                requestChunk(chunkX, chunkZ, tier);
                return;
            }

            // Do not monopolize a tick when no candidate needs loading. MinecraftServer#execute() runs
            // inline instead of queuing when called from the server thread (which this always is), so it
            // would not actually defer anything here - tell() with a future tick is what actually yields.
            level.getServer().tell(new TickTask(level.getServer().getTickCount() + 1, this::advance));
        }

        private long nextRingOffset() {
            while (ring <= ringRadius) {
                // A ring has 8 * ring positions, not (2 * ring + 1)^2.  The previous implementation
                // enumerated that whole square on every ring and discarded its interior, making the
                // command cubic in the requested radius.  At the default radius it exhausted its budget
                // around ring 100 instead of reaching ring 625.
                int perimeter = ring == 0 ? 1 : ring * 8;
                if (ringCursor >= perimeter) {
                    ring++;
                    ringCursor = 0;
                    continue;
                }
                if (closest != null
                        && LocateDistanceMath.minimumReachableDistanceForGridRing(
                                ring, ringStepChunks, SOURCE_CHUNK_DEPOSIT_MARGIN
                        )
                        * LocateDistanceMath.minimumReachableDistanceForGridRing(
                                ring, ringStepChunks, SOURCE_CHUNK_DEPOSIT_MARGIN
                        )
                        >= closestDistSqr) {
                    // Remaining rings for this tier cannot put a deposit closer than one we have already
                    // verified. Other tiers still get a chance to beat it in nextTier().
                    ring = chunkRadius + 1;
                    continue;
                }

                int index = ringCursor++;
                return LocateDistanceMath.ringOffset(ring, index);
            }
            return LocateDistanceMath.NO_RING_OFFSET;
        }

        private boolean isPredictedCandidate(int chunkX, int chunkZ) {
            int centerX = (chunkX << 4) + 8;
            int centerZ = (chunkZ << 4) + 8;
            return LocateDistanceMath.centerCanReachRadius(
                    centerX, centerZ, origin.getX(), origin.getZ(),
                    blockRadius, SOURCE_CHUNK_DEPOSIT_MARGIN
            ) && OreDepositFeature.predictsMaterialForChunk(level, chunkX, chunkZ, tier, materialKey);
        }

        private void requestChunk(int chunkX, int chunkZ, int candidateTier) {
            // TINY/SMALL use the original vanilla placement stream, so they do not have a planned
            // center. This point is only bookkeeping for the real-chunk scan; no locate result is ever
            // returned from it. MEDIUM/LARGE retain their deterministic center for spatial-index lookup.
            BlockPos predictedCenter = candidateTier < OreDepositFeature.TIER_MEDIUM
                    ? new BlockPos((chunkX << 4) + 8, 64, (chunkZ << 4) + 8)
                    : OreDepositFeature.plannedCenterForChunk(level, chunkX, chunkZ, candidateTier, materialKey);
            LargeDepositSpatialIndex spatialIndex = candidateTier >= OreDepositFeature.TIER_MEDIUM
                    ? LargeDepositSpatialIndex.get(level)
                    : null;
            Set<LargeDepositSpatialIndex.DepositRecord> recordsBeforeGeneration = spatialIndex == null
                    ? Set.of()
                    : new HashSet<>(spatialIndex.recordsWithin(
                            predictedCenter, SOURCE_CHUNK_RECORD_MARGIN, candidateTier, materialKey
                    ));

            // ServerChunkCache#getChunkFuture unconditionally managedBlock()s the calling thread when
            // Thread.currentThread() == the server thread (see ServerChunkCache.getChunkFuture's "flag"
            // branch) - it only takes the non-blocking branch when called from some other thread. The
            // default CompletableFuture.supplyAsync() executor (ForkJoinPool.commonPool(), whose actual
            // parallelism depends on the host's core count and JVM flags) does not reliably guarantee
            // that: on constrained hosts it can end up running this supplier back on the server thread,
            // which then deadlocks inside its own managedBlock() pump (see ServerHangWatchdog crashes).
            // Util.backgroundExecutor() is vanilla's dedicated off-main-thread pool and is never the
            // server thread, so it always takes the safe, non-blocking branch.
            CompletableFuture.supplyAsync(() -> level.getChunkSource().getChunkFuture(
                            chunkX, chunkZ, ChunkStatus.FULL, true
                    ), Util.backgroundExecutor())
                    .thenCompose(future -> future)
                    .whenComplete((result, error) -> {
                        // MinecraftServer#execute() runs its task inline instead of queuing it when called
                        // from the server thread (BlockableEventLoop's reentrant fast path). Since this
                        // future can complete on the server thread itself (e.g. vanilla resolving it
                        // synchronously via its own main-thread chunk executor), that inline run would chain
                        // straight into the next requestChunk() call with zero ticks between force-generated
                        // candidates - hammering DistanceManager back-to-back inside the same tick, which is
                        // exactly the pattern that trips its chunksToUpdateFutures ConcurrentModificationException
                        // (a reentrant mutation of that HashSet during its own iteration). tell() with a future
                        // tick unconditionally queues the continuation, guaranteeing it always runs on a later
                        // tick instead of nesting into whatever call stack completed this future.
                        int nextTick = level.getServer().getTickCount() + 1;
                        level.getServer().tell(new TickTask(nextTick, () -> handleLoadedChunk(
                                result, error, chunkX, chunkZ, candidateTier,
                                predictedCenter, recordsBeforeGeneration
                        )));
                    });
        }

        private void handleLoadedChunk(
                ChunkResult<ChunkAccess> result,
                Throwable error,
                int chunkX,
                int chunkZ,
                int candidateTier,
                BlockPos predictedCenter,
                Set<LargeDepositSpatialIndex.DepositRecord> recordsBeforeGeneration
        ) {
            if (complete) {
                return;
            }
            boolean awaitingRelocatedChunk = false;
            if (error == null && result != null && result.isSuccess()) {
                ChunkAccess chunk = result.orElse(null);
                if (chunk != null) {
                    awaitingRelocatedChunk = inspectGeneratedCandidate(
                            chunk, chunkX, chunkZ, candidateTier, predictedCenter, recordsBeforeGeneration
                    );
                }
            } else if (error != null) {
                FactoryExpansionMod.LOGGER.warn(
                        "Ore locate could not load candidate chunk [{}, {}] for {} tier {}",
                        chunkX, chunkZ, materialKey, candidateTier, error
                );
            }
            if (!awaitingRelocatedChunk) {
                advance();
            }
        }

        /** @return true when verification continues asynchronously in a relocated record's real chunk. */
        private boolean inspectGeneratedCandidate(
                ChunkAccess chunk,
                int chunkX,
                int chunkZ,
                int candidateTier,
                BlockPos predictedCenter,
                Set<LargeDepositSpatialIndex.DepositRecord> recordsBeforeGeneration
        ) {
            scanRealChunk(chunk, chunkX, chunkZ, candidateTier);

            int neighborRadius = LocateDistanceMath.chunkRadius(SOURCE_CHUNK_DEPOSIT_MARGIN, 0);
            for (int offsetX = -neighborRadius; offsetX <= neighborRadius; offsetX++) {
                for (int offsetZ = -neighborRadius; offsetZ <= neighborRadius; offsetZ++) {
                    if (offsetX == 0 && offsetZ == 0) {
                        continue;
                    }
                    int neighborX = chunkX + offsetX;
                    int neighborZ = chunkZ + offsetZ;
                    ChunkAccess neighbor = level.getChunkSource().getChunk(
                            neighborX, neighborZ, ChunkStatus.EMPTY, false
                    );
                    if (neighbor != null) {
                        scanRealChunk(neighbor, neighborX, neighborZ, candidateTier);
                    }
                }
            }

            if (candidateTier < OreDepositFeature.TIER_MEDIUM) {
                return false;
            }
            LargeDepositSpatialIndex spatialIndex = LargeDepositSpatialIndex.get(level);
            for (LargeDepositSpatialIndex.DepositRecord record : spatialIndex.recordsWithin(
                    predictedCenter, SOURCE_CHUNK_RECORD_MARGIN, candidateTier, materialKey
            )) {
                int actualChunkX = record.center().getX() >> 4;
                int actualChunkZ = record.center().getZ() >> 4;
                ChunkAccess actualChunk = actualChunkX == chunkX && actualChunkZ == chunkZ
                        ? chunk
                        : level.getChunkSource().getChunk(actualChunkX, actualChunkZ, ChunkStatus.EMPTY, false);
                if (actualChunk == null) {
                    // Tagged-stone correction may move the recorded center outside the source chunk.
                    // Loading only the predicted chunk and immediately continuing could then miss a real
                    // deposit whose attachment still lives in a not-yet-FULL neighbour.
                    generatedCandidates++;
                    requestChunk(actualChunkX, actualChunkZ, candidateTier);
                    return true;
                }
                boolean visibleThroughAttachment = actualChunk != null
                        && scanRealChunk(actualChunk, actualChunkX, actualChunkZ, candidateTier);
                if (!visibleThroughAttachment && recordsBeforeGeneration.contains(record)) {
                    // Existing v4-and-older index records have no block count. Once all of their possible
                    // footprint chunks happen to be loaded, prune only if none retains a live matching block.
                    spatialIndex.pruneLegacyRecordIfExhausted(level, record);
                }
                // A spatial-index center is a plan center, not necessarily an occupied block.  Never use
                // it as a locate result until scanRealChunk verified a live attachment; otherwise a cave
                // or a cross-chunk placement can send the player into air.
            }
            return false;
        }

        private int generatedCandidateLimit() {
            return tier >= OreDepositFeature.TIER_MEDIUM
                    ? MAX_FORCE_GENERATED_CANDIDATES_PER_TIER
                    : MAX_FORCE_GENERATED_SMALL_CANDIDATES_PER_TIER;
        }

        private void nextTier() {
            tier++;
            ring = 0;
            ringCursor = 0;
            predictionChecks = 0;
            generatedCandidates = 0;
            configureTierSearch();
        }

        /** Computes tier-dependent ring traversal once, rather than once per predicted chunk. */
        private void configureTierSearch() {
            anchorGrid = tier <= lastTier && OreDepositFeature.usesLocateAnchorGrid(materialKey, tier);
            ringStepChunks = !anchorGrid ? 1
                    : (OreDepositFeature.usesForcedAlexUraniumAnchorGrid(materialKey, tier)
                    ? OreDepositFeature.forcedUraniumRegionSpacingChunks()
                    : OreDepositFeature.largeDepositRegionSpacingChunks());
            ringRadius = LocateDistanceMath.gridRingRadius(chunkRadius, ringStepChunks);
        }

        private void finish() {
            complete = true;
            if (closest == null) {
                source.sendFailure(Component.translatable(
                        "commands.ores_and_drills.locate.not_found",
                        Component.translationArg(oreId), blockRadius
                ));
                return;
            }
            sendLocatedResult(source, origin, oreId, closest);
        }

        /**
         * Generating an unbounded number of chunks just to answer a command is unsafe, but reporting
         * "not found" after the safety cap is also incorrect. Stop explicitly so callers can distinguish
         * an incomplete search from a verified empty radius.
         */
        private void finishAtGenerationLimit() {
            complete = true;
            source.sendFailure(Component.translatable(
                    "commands.ores_and_drills.locate.search_limit",
                    Component.translationArg(oreId), blockRadius, generatedCandidateLimit()
            ));
        }

        /**
         * Scans a real, generated chunk's ore-deposit attachment for a live match, updating {@link #closest}
         * with any block within range. Removes any attachment entries whose backing block no longer exists.
         *
         * @return true if the chunk contains at least one live matching block (in range or not), meaning the
         *         deposit is still accounted for through the block attachment rather than only the spatial index.
         */
        private boolean scanRealChunk(ChunkAccess chunk, int chunkX, int chunkZ, int tierFilter) {
            OreDepositChunkData data = chunk.getExistingDataOrNull(ModAttachments.ORE_DEPOSITS);
            if (data == null) {
                return false;
            }

            List<StaleDeposit> stale = new ArrayList<>();
            boolean[] containsLiveMatch = {false};
            data.forEach(chunkX, chunkZ, (pos, entry) -> {
                if (!oreIndices.contains(entry.oreIndex()) || entry.remainingOre() <= 0 || entry.tier() != tierFilter) {
                    return;
                }

                // The attachment's data can outlive the actual block (carved away by another mod's worldgen,
                // overwritten, etc.); trusting it anyway would send players to air/bedrock and, since this data
                // is deterministic, keep sending them to the exact same dead spot on every later /locate too.
                if (!chunk.getBlockState(pos).is(ModBlocks.ORE_DEPOSIT.get())) {
                    stale.add(new StaleDeposit(pos, entry.tier()));
                    return;
                }

                containsLiveMatch[0] = true;
                considerCandidate(pos);
            });

            boolean removedStaleData = false;
            for (StaleDeposit staleDeposit : stale) {
                if (!data.remove(staleDeposit.pos())) {
                    continue;
                }
                removedStaleData = true;
                if (staleDeposit.tier() >= OreDepositFeature.TIER_MEDIUM) {
                    // Keep the persisted spacing/locate index in sync when another worldgen feature
                    // removed the block without going through OreDepositData.remove().
                    LargeDepositSpatialIndex.get(level).markBlockDepleted(
                            staleDeposit.pos(), staleDeposit.tier(), materialKey
                    );
                }
            }
            if (removedStaleData) {
                // Attachments are serialized separately from block states.  Removing an entry only from
                // the in-memory map would make it reappear after the chunk is reloaded, so persist and
                // broadcast this repair just like OreDepositData.remove does for normal block removal.
                chunk.setUnsaved(true);
                chunk.syncData(ModAttachments.ORE_DEPOSITS);
            }
            return containsLiveMatch[0];
        }

        private void considerCandidate(BlockPos pos) {
            double distSqr = horizontalDistanceSqr(pos, origin);
            if (distSqr <= maxDistSqr && distSqr < closestDistSqr) {
                closestDistSqr = distSqr;
                closest = pos;
            }
        }

        private record StaleDeposit(BlockPos pos, int tier) {
        }
    }
}

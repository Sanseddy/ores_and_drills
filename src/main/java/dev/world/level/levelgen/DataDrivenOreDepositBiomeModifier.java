package dev.world.level.levelgen;

import com.mojang.serialization.Codec;
import com.mojang.serialization.MapCodec;
import com.mojang.serialization.codecs.RecordCodecBuilder;
import dev.OresAndDrillsMod;
import dev.registry.ModBlockTags;
import dev.registry.ModWorldgen;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.WeakHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.WorldGenLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.feature.ConfiguredFeature;
import net.minecraft.world.level.levelgen.feature.configurations.OreConfiguration;
import net.minecraft.world.level.levelgen.structure.templatesystem.BlockMatchTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.RuleTest;
import net.minecraft.world.level.levelgen.structure.templatesystem.TagMatchTest;
import net.neoforged.neoforge.common.world.BiomeModifier;
import net.neoforged.neoforge.common.world.ModifiableBiomeInfo;
import net.neoforged.neoforge.registries.NeoForgeRegistries;

/**
 * A datapack biome modifier whose decoded rules are consumed directly by generation and /locate.
 */
public record DataDrivenOreDepositBiomeModifier(
        List<String> ores,
        List<String> biomes,
        Map<String, TierSettings> sizes,
        List<String> dimensions,
        int minY,
        int maxY,
        List<String> replaceables,
        int priority)
        implements BiomeModifier {
    private static final Map<Holder<Biome>, RuleSession> ACTIVE_RULES =
            java.util.Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<ResourceLocation, List<ConfiguredFeature<?, ?>>> DECORATION_FEATURES =
            new java.util.concurrent.ConcurrentHashMap<>();

    static List<OreDepositFeature.Configuration> compactConfigurations() {
        return DECORATION_FEATURES.values().stream()
                .flatMap(List::stream)
                .map(feature -> (OreDepositFeature.Configuration) feature.config())
                .filter(
                        config ->
                                config.sizeTier() >= OreDepositFeature.TIER_MEDIUM
                                        && config.dataDriven().isPresent()
                                        && !config.dataDriven().get().forced())
                .toList();
    }

    public static final MapCodec<DataDrivenOreDepositBiomeModifier> CODEC =
            RecordCodecBuilder.mapCodec(
                    instance ->
                            instance.group(
                                            Codec.STRING
                                                    .listOf()
                                                    .fieldOf("ores")
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::ores),
                                            Codec.STRING
                                                    .listOf()
                                                    .optionalFieldOf(
                                                            "biomes",
                                                            List.of("#minecraft:is_overworld"))
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::biomes),
                                            Codec.unboundedMap(Codec.STRING, TierSettings.CODEC)
                                                    .fieldOf("sizes")
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::sizes),
                                            Codec.STRING
                                                    .listOf()
                                                    .optionalFieldOf(
                                                            "dimensions",
                                                            List.of("minecraft:overworld"))
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::dimensions),
                                            Codec.INT
                                                    .optionalFieldOf("min_y", -64)
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::minY),
                                            Codec.INT
                                                    .optionalFieldOf("max_y", 320)
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::maxY),
                                            Codec.STRING
                                                    .listOf()
                                                    .optionalFieldOf(
                                                            "replaceables", List.of("#c:stones"))
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::replaceables),
                                            Codec.INT
                                                    .optionalFieldOf("priority", 0)
                                                    .forGetter(
                                                            DataDrivenOreDepositBiomeModifier
                                                                    ::priority))
                                    .apply(instance, DataDrivenOreDepositBiomeModifier::new));

    /** Highest priority wins; equal priorities fall back to a load-order independent hash. */
    private static final Comparator<DataDrivenOreDepositBiomeModifier> RULE_ORDER =
            Comparator.comparingInt(DataDrivenOreDepositBiomeModifier::priority)
                    .thenComparingLong(DataDrivenOreDepositBiomeModifier::stableSignature);

    public DataDrivenOreDepositBiomeModifier {
        ores = List.copyOf(ores);
        biomes = List.copyOf(biomes);
        sizes = Map.copyOf(sizes);
        dimensions = List.copyOf(dimensions);
        replaceables = List.copyOf(replaceables);
        if (maxY < minY) {
            throw new IllegalArgumentException("max_y must be greater than or equal to min_y");
        }
        for (String size : sizes.keySet()) {
            if (OreDepositTier.fromName(size).isEmpty()) {
                throw new IllegalArgumentException("Unknown ore deposit size: " + size);
            }
        }
    }

    @Override
    public void modify(
            Holder<Biome> biome, Phase phase, ModifiableBiomeInfo.BiomeInfo.Builder builder) {
        if (phase == Phase.BEFORE_EVERYTHING) {
            synchronized (ACTIVE_RULES) {
                RuleSession session = ACTIVE_RULES.get(biome);
                if (session == null || session.builder() != builder) {
                    session = new RuleSession(builder, new ArrayList<>());
                    ACTIVE_RULES.put(biome, session);
                    biome.unwrapKey().ifPresent(key -> DECORATION_FEATURES.remove(key.location()));
                }
                List<DataDrivenOreDepositBiomeModifier> rules = session.rules();
                if (!rules.contains(this)) {
                    rules.add(this);
                }
            }
            return;
        }
        if (phase != Phase.AFTER_EVERYTHING || !matchesBiome(biome)) {
            return;
        }
        ResourceLocation biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);
        if (biomeId == null) {
            return;
        }

        // Rules are not attached to the biome's feature list: some cave-biome mods (Alex's Caves)
        // install their biomes outside the vanilla feature index, so such a feature would silently
        // never run there. decorate() runs them from the chunk's real biome data instead.
        List<ConfiguredFeature<?, ?>> features =
                DECORATION_FEATURES.computeIfAbsent(
                        biomeId, ignored -> new java.util.concurrent.CopyOnWriteArrayList<>());
        for (Block ore : matchingUnifiedOres()) {
            if (ReplaceOreFeaturesBiomeModifier.isOreDisabled(ore)
                    || ReplaceOreFeaturesBiomeModifier.isOreIgnored(ore)) {
                continue;
            }
            for (Map.Entry<String, TierSettings> entry : sizes.entrySet()) {
                OreDepositTier tier = OreDepositTier.fromName(entry.getKey()).orElseThrow();
                if (!isWinner(biome, ore, tier)) {
                    continue;
                }
                TierSettings tierSettings = entry.getValue();
                // A forced rule places exactly `count` deposits per biome instance from one
                // feature, which keeps its own per-instance ledger; frequency rules keep one
                // feature per cell slot.
                int slots = tierSettings.forced() ? 1 : tierSettings.candidateSlots();
                for (int slot = 0; slot < slots; slot++) {
                    double chance = tierSettings.chanceForSlot(slot);
                    if (chance <= 0.0D) {
                        continue;
                    }
                    OreDepositFeature.DataDrivenSettings settings =
                            featureSettings(tierSettings, chance, slot);
                    OreDepositFeature.Configuration configuration =
                            new OreDepositFeature.Configuration(
                                    targetsFor(ore),
                                    tier.index(),
                                    1.0F,
                                    1.0F,
                                    tierSettings.sizeMultiplier(),
                                    tierSettings.richnessMultiplier(),
                                    biome.unwrapKey().map(key -> key.location()),
                                    Optional.of(settings));
                    ConfiguredFeature<OreDepositFeature.Configuration, OreDepositFeature>
                            configured =
                                    new ConfiguredFeature<>(
                                            ModWorldgen.ORE_DEPOSIT_FEATURE.get(), configuration);
                    features.add(configured);
                }
            }
        }
    }

    /**
     * Runs the datapack rules of every biome present in this chunk. Called after vanilla biome
     * decoration; each rule still places only from its planned source chunk and only when the
     * planned center is in its biome.
     */
    public static void decorate(WorldGenLevel level, ChunkAccess chunk, ChunkGenerator generator) {
        OreDepositFeature.placePendingForcedSlices(level, chunk.getPos());
        ReplaceOreFeaturesBiomeModifier.decorateCompactDeposits(level, chunk);
        if (DECORATION_FEATURES.isEmpty()) {
            return;
        }
        // Frequency-based MEDIUM/LARGE plans can cross into a chunk with no source biome at all.
        DECORATION_FEATURES.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .flatMap(entry -> entry.getValue().stream())
                .filter(
                        feature ->
                                feature.config() instanceof OreDepositFeature.Configuration config
                                        && config.sizeTier() >= OreDepositFeature.TIER_MEDIUM
                                        && config.dataDriven().isPresent()
                                        && !config.dataDriven().get().forced())
                .forEach(
                        feature ->
                                OreDepositFeature.placeCompactDeposits(
                                        level,
                                        chunk.getPos(),
                                        (OreDepositFeature.Configuration) feature.config()));
        // Like vanilla decoration, a rule runs when its biome is in this chunk or a neighbour: its
        // planned center may sit just outside the real biome and is then moved onto it (see
        // OreDepositFeature).
        Set<Holder<Biome>> biomes = new java.util.HashSet<>();
        ChunkPos chunkPos = chunk.getPos();
        for (int offsetX = -1; offsetX <= 1; offsetX++) {
            for (int offsetZ = -1; offsetZ <= 1; offsetZ++) {
                ChunkAccess neighbour =
                        level.getChunk(
                                chunkPos.x + offsetX,
                                chunkPos.z + offsetZ,
                                ChunkStatus.BIOMES,
                                false);
                if (neighbour == null) {
                    continue;
                }
                for (LevelChunkSection section : neighbour.getSections()) {
                    section.getBiomes().getAll(biomes::add);
                }
            }
        }
        BlockPos origin =
                new BlockPos(
                        chunkPos.getMinBlockX(),
                        level.getMinBuildHeight(),
                        chunkPos.getMinBlockZ());
        for (Holder<Biome> biome : biomes) {
            ResourceLocation biomeId = biome.unwrapKey().map(key -> key.location()).orElse(null);
            List<ConfiguredFeature<?, ?>> features =
                    biomeId == null ? null : DECORATION_FEATURES.get(biomeId);
            if (features == null) {
                continue;
            }
            for (ConfiguredFeature<?, ?> feature : features) {
                feature.place(
                        level,
                        generator,
                        RandomSource.create(level.getSeed() ^ chunkPos.toLong()),
                        origin);
            }
        }
    }

    @Override
    public MapCodec<? extends BiomeModifier> codec() {
        return ModWorldgen.ORE_DEPOSIT_RULE.get();
    }

    /**
     * Any explicit rule for an ore owns that ore completely; automatic source-feature conversion is
     * disabled.
     */
    static boolean explicitlyConfigured(Holder<Biome> biome, Block ore) {
        synchronized (ACTIVE_RULES) {
            RuleSession session = ACTIVE_RULES.get(biome);
            return session != null
                    && session.rules().stream().anyMatch(rule -> rule.matchesUnifiedOre(ore));
        }
    }

    static boolean explicitlyConfigured(ServerLevel level, Block ore) {
        return loadedRules(level).stream().anyMatch(rule -> rule.matchesUnifiedOre(ore));
    }

    /**
     * Registered ore blocks selected by live datapack/KubeJS rules. Unlike the convention-tag
     * catalog this deliberately also contains direct block ids which have no ore tag at all.
     */
    public static List<ResourceLocation> configuredOreCatalog(ServerLevel level) {
        return loadedRules(level).stream()
                .filter(rule -> !rule.sizes().isEmpty())
                .flatMap(rule -> rule.matchingUnifiedOres().stream())
                .map(BuiltInRegistries.BLOCK::getKey)
                .filter(id -> id != null)
                .filter(
                        id ->
                                !id.equals(
                                        ResourceLocation.fromNamespaceAndPath(
                                                OresAndDrillsMod.MOD_ID, "ore_deposit")))
                .distinct()
                .sorted(Comparator.comparing(ResourceLocation::toString))
                .toList();
    }

    /**
     * Finds a real allowed 3D biome before /locate evaluates the comparatively expensive per-cell
     * plans. This is only a search-space accelerator: returned deposits are still created and
     * validated by the exact same plansForCell/createDataDrivenCandidate path used by chunk
     * generation.
     */
    static BlockPos findClosestAllowedBiome(
            ServerLevel level, BlockPos origin, Block ore, OreDepositTier tier, int radius) {
        List<DataDrivenOreDepositBiomeModifier> rules =
                loadedRules(level).stream()
                        .filter(
                                rule ->
                                        rule.matchesUnifiedOre(ore)
                                                && rule.matchesDimension(
                                                        level.dimension().location())
                                                && rule.settings(tier).isPresent())
                        .toList();
        if (rules.isEmpty()) {
            return null;
        }
        Set<ResourceLocation> exactBiomeIds =
                rules.stream()
                        .flatMap(rule -> rule.biomes.stream())
                        .filter(selector -> !selector.startsWith("#"))
                        .map(ResourceLocation::tryParse)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        BlockPos optionalHint =
                OptionalBiomeRegionHints.findClosest(level, origin, radius, exactBiomeIds);
        if (optionalHint != null) {
            OresAndDrillsMod.LOGGER.debug(
                    "Data-driven locate found optional biome-region hint at {} for ore {} tier {}",
                    optionalHint,
                    BuiltInRegistries.BLOCK.getKey(ore),
                    tier.serializedName());
            return optionalHint;
        }
        var found =
                level.findClosestBiome3d(
                        biome -> rules.stream().anyMatch(rule -> rule.matchesBiome(biome)),
                        origin,
                        radius,
                        128,
                        16);
        BlockPos result = found == null ? null : found.getFirst();
        OresAndDrillsMod.LOGGER.debug(
                "Data-driven locate generic biome hint for ore {} tier {}: {}",
                BuiltInRegistries.BLOCK.getKey(ore),
                tier.serializedName(),
                result);
        return result;
    }

    /** Resumable, order-identical counterpart of BiomeSource.findClosestBiome3d for /locate. */
    static AllowedBiomeSearch beginClosestAllowedBiomeSearch(
            ServerLevel level, BlockPos origin, Block ore, OreDepositTier tier, int radius) {
        List<DataDrivenOreDepositBiomeModifier> rules =
                loadedRules(level).stream()
                        .filter(
                                rule ->
                                        rule.matchesUnifiedOre(ore)
                                                && rule.matchesDimension(
                                                        level.dimension().location())
                                                && rule.settings(tier).isPresent())
                        .toList();
        if (rules.isEmpty()) return AllowedBiomeSearch.completed(null);
        Set<ResourceLocation> exactBiomeIds =
                rules.stream()
                        .flatMap(rule -> rule.biomes.stream())
                        .filter(selector -> !selector.startsWith("#"))
                        .map(ResourceLocation::tryParse)
                        .filter(java.util.Objects::nonNull)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet());
        BlockPos optionalHint =
                OptionalBiomeRegionHints.findClosest(level, origin, radius, exactBiomeIds);
        if (optionalHint != null) return AllowedBiomeSearch.completed(optionalHint);
        boolean possible =
                level.getChunkSource().getGenerator().getBiomeSource().possibleBiomes().stream()
                        .anyMatch(
                                biome -> rules.stream().anyMatch(rule -> rule.matchesBiome(biome)));
        if (!possible) return AllowedBiomeSearch.completed(null);
        return new AllowedBiomeSearch(level, origin, radius, rules);
    }

    static final class AllowedBiomeSearch {
        private static final int HORIZONTAL_STEP = 128;
        private static final int VERTICAL_STEP = 16;
        private final ServerLevel level;
        private final BlockPos origin;
        private final List<DataDrivenOreDepositBiomeModifier> rules;
        private final Iterator<BlockPos.MutableBlockPos> horizontalOffsets;
        private final int[] sampleYs;
        private BlockPos.MutableBlockPos horizontalOffset;
        private int yIndex;
        private boolean done;
        private BlockPos result;

        private AllowedBiomeSearch(
                ServerLevel level,
                BlockPos origin,
                int radius,
                List<DataDrivenOreDepositBiomeModifier> rules) {
            this.level = level;
            this.origin = origin.immutable();
            this.rules = rules;
            this.horizontalOffsets =
                    BlockPos.spiralAround(
                                    BlockPos.ZERO,
                                    Math.floorDiv(radius, HORIZONTAL_STEP),
                                    Direction.EAST,
                                    Direction.SOUTH)
                            .iterator();
            this.sampleYs =
                    Mth.outFromOrigin(
                                    origin.getY(),
                                    level.getMinBuildHeight() + 1,
                                    level.getMaxBuildHeight(),
                                    VERTICAL_STEP)
                            .toArray();
        }

        private AllowedBiomeSearch(BlockPos result) {
            this.level = null;
            this.origin = BlockPos.ZERO;
            this.rules = List.of();
            this.horizontalOffsets = java.util.Collections.emptyIterator();
            this.sampleYs = new int[0];
            this.result = result;
            this.done = true;
        }

        static AllowedBiomeSearch completed(BlockPos result) {
            return new AllowedBiomeSearch(result);
        }

        void advance(long deadlineNanos, int maximumSamples) {
            int samples = 0;
            while (!done && samples++ < maximumSamples && System.nanoTime() < deadlineNanos) {
                if (horizontalOffset == null) {
                    if (!horizontalOffsets.hasNext()) {
                        done = true;
                        return;
                    }
                    horizontalOffset = horizontalOffsets.next();
                    yIndex = 0;
                }
                int x = origin.getX() + horizontalOffset.getX() * HORIZONTAL_STEP;
                int z = origin.getZ() + horizontalOffset.getZ() * HORIZONTAL_STEP;
                int y = sampleYs[yIndex++];
                Holder<Biome> biome =
                        BiomeNoiseCache.get(
                                level,
                                net.minecraft.core.QuartPos.fromBlock(x),
                                net.minecraft.core.QuartPos.fromBlock(y),
                                net.minecraft.core.QuartPos.fromBlock(z));
                if (rules.stream().anyMatch(rule -> rule.matchesBiome(biome))) {
                    result = new BlockPos(x, y, z);
                    done = true;
                    return;
                }
                if (yIndex >= sampleYs.length) horizontalOffset = null;
            }
        }

        boolean isDone() {
            return done;
        }

        BlockPos result() {
            if (!done) throw new IllegalStateException("Biome search has not completed");
            return result;
        }
    }

    /**
     * Returns the exact per-cell plans used by the feature, including priority and fractional
     * slots.
     */
    static List<PlannedDeposit> plansForCell(
            ServerLevel level, int cellX, int cellZ, ResourceLocation oreId, OreDepositTier tier) {
        if (!BuiltInRegistries.BLOCK.containsKey(oreId)) {
            return List.of();
        }
        Block requestedOre = BuiltInRegistries.BLOCK.get(oreId);
        Block ore = OreUnifier.canonicalMaterialBlockFor(requestedOre).orElse(requestedOre);
        ResourceLocation unifiedOreId = BuiltInRegistries.BLOCK.getKey(ore);
        if (unifiedOreId == null) {
            return List.of();
        }
        List<DataDrivenOreDepositBiomeModifier> rules =
                loadedRules(level).stream()
                        .filter(
                                rule ->
                                        (rule.matchesOre(requestedOre)
                                                        || rule.matchesUnifiedOre(ore))
                                                && rule.matchesDimension(
                                                        level.dimension().location())
                                                && rule.settings(tier).isPresent())
                        .toList();
        if (rules.isEmpty()) {
            return List.of();
        }

        List<PlannedDeposit> candidates = new ArrayList<>();
        level.registryAccess()
                .registryOrThrow(Registries.BIOME)
                .holders()
                .forEach(
                        biome -> {
                            DataDrivenOreDepositBiomeModifier winner =
                                    rules.stream()
                                            .filter(rule -> rule.matchesBiome(biome))
                                            .max(RULE_ORDER)
                                            .orElse(null);
                            if (winner == null) {
                                return;
                            }
                            ResourceLocation biomeId =
                                    biome.unwrapKey().map(key -> key.location()).orElse(null);
                            if (biomeId == null) {
                                return;
                            }
                            TierSettings tierSettings = winner.settings(tier).orElseThrow();
                            // Forced rules still need seed plans for unexplored terrain. Real
                            // generation keeps the per-biome-instance ledger authoritative, while
                            // /locate uses these immutable slots as the coordinates that will be
                            // offered when their source chunk is decorated. Without them, rules
                            // such as #c:ores/uranium in a 3D cave biome could not be located until
                            // the deposit was already generated.
                            int slots = tierSettings.candidateSlots();
                            for (int slot = 0; slot < slots; slot++) {
                                double chance = tierSettings.chanceForSlot(slot);
                                OreDepositFeature.DataDrivenSettings settings =
                                        winner.featureSettings(tierSettings, chance, slot);
                                if (!OreDepositFeature.passesDataDrivenFrequency(
                                        level,
                                        cellX,
                                        cellZ,
                                        biomeId,
                                        unifiedOreId,
                                        tier.index(),
                                        settings)) {
                                    continue;
                                }
                                DepositCandidate candidate =
                                        OreDepositFeature.createDataDrivenCandidate(
                                                level,
                                                cellX,
                                                cellZ,
                                                biomeId,
                                                unifiedOreId,
                                                tier.index(),
                                                settings);
                                if (matchesPlannedBiome(level, candidate.center(), biomeId)) {
                                    candidates.add(
                                            new PlannedDeposit(
                                                    candidate,
                                                    biomeId,
                                                    winner.replaceables,
                                                    tierSettings.placement(),
                                                    tierSettings.forced()));
                                }
                            }
                        });
        if (!candidates.isEmpty()) {
            OresAndDrillsMod.LOGGER.debug(
                    "Data-driven cell [{}, {}] planned {} candidate(s) for {} tier {}",
                    cellX,
                    cellZ,
                    candidates.size(),
                    unifiedOreId,
                    tier.serializedName());
        }
        return List.copyOf(candidates);
    }

    /** Shared remote-planning biome test used by both generation planning and /locate. */
    static boolean matchesPlannedBiome(ServerLevel level, BlockPos pos, ResourceLocation biomeId) {
        Holder<Biome> noiseBiome =
                BiomeNoiseCache.get(
                        level,
                        net.minecraft.core.QuartPos.fromBlock(pos.getX()),
                        net.minecraft.core.QuartPos.fromBlock(pos.getY()),
                        net.minecraft.core.QuartPos.fromBlock(pos.getZ()));
        return noiseBiome.is(net.minecraft.resources.ResourceKey.create(Registries.BIOME, biomeId))
                || OptionalBiomeRegionHints.matches(level, pos, biomeId);
    }

    static List<DepositCandidate> candidatesForCell(
            ServerLevel level, int cellX, int cellZ, ResourceLocation oreId, OreDepositTier tier) {
        return plansForCell(level, cellX, cellZ, oreId, tier).stream()
                .map(PlannedDeposit::candidate)
                .toList();
    }

    private OreDepositFeature.DataDrivenSettings featureSettings(
            TierSettings settings, double chance, int slot) {
        return new OreDepositFeature.DataDrivenSettings(
                dimensions,
                minY,
                maxY,
                settings.placement(),
                settings.forced(),
                chance,
                slot,
                stableSignature(),
                settings.sizeMultiplier(),
                settings.richnessMultiplier(),
                settings.count());
    }

    private static List<DataDrivenOreDepositBiomeModifier> loadedRules(ServerLevel level) {
        return level
                .registryAccess()
                .registryOrThrow(NeoForgeRegistries.Keys.BIOME_MODIFIERS)
                .stream()
                .filter(DataDrivenOreDepositBiomeModifier.class::isInstance)
                .map(DataDrivenOreDepositBiomeModifier.class::cast)
                .toList();
    }

    private boolean isWinner(Holder<Biome> biome, Block ore, OreDepositTier tier) {
        synchronized (ACTIVE_RULES) {
            RuleSession session = ACTIVE_RULES.get(biome);
            return (session == null
                            ? java.util.stream.Stream.<DataDrivenOreDepositBiomeModifier>empty()
                            : session.rules().stream())
                    .filter(
                            rule ->
                                    rule.matchesBiome(biome)
                                            && rule.matchesUnifiedOre(ore)
                                            && rule.settings(tier).isPresent())
                    .max(RULE_ORDER)
                    .map(rule -> rule == this)
                    .orElse(false);
        }
    }

    Optional<TierSettings> settings(OreDepositTier tier) {
        return Optional.ofNullable(sizes.get(tier.serializedName()));
    }

    boolean matchesBiome(Holder<Biome> biome) {
        return matchesHolder(biome, biomes, Registries.BIOME);
    }

    boolean matchesOre(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        if (id == null) {
            return false;
        }
        Holder<Block> holder = BuiltInRegistries.BLOCK.wrapAsHolder(block);
        return matchesHolder(holder, ores, Registries.BLOCK);
    }

    /** True when this rule selects any registered variant of the supplied unified material. */
    boolean matchesUnifiedOre(Block unifiedOre) {
        String material = OreUnifier.materialKeyFor(unifiedOre);
        if (material.isEmpty()) {
            return matchesOre(unifiedOre);
        }
        return BuiltInRegistries.BLOCK.stream()
                .anyMatch(
                        candidate ->
                                matchesOre(candidate)
                                        && material.equals(OreUnifier.materialKeyFor(candidate)));
    }

    boolean matchesDimension(ResourceLocation dimension) {
        return matchesId(dimension, dimensions);
    }

    long stableSignature() {
        String canonical =
                "ores="
                        + ores.stream().sorted().toList()
                        + ";biomes="
                        + biomes.stream().sorted().toList()
                        + ";dimensions="
                        + dimensions.stream().sorted().toList()
                        + ";height="
                        + minY
                        + ":"
                        + maxY
                        + ";replaceables="
                        + replaceables.stream().sorted().toList()
                        + ";priority="
                        + priority
                        + ";sizes="
                        + sizes.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(entry -> entry.getKey() + "=" + entry.getValue())
                                .toList();
        return SeedMixer.hash64(canonical);
    }

    private List<Block> matchingUnifiedOres() {
        return BuiltInRegistries.BLOCK.stream()
                .filter(this::matchesOre)
                .map(ore -> OreUnifier.canonicalMaterialBlockFor(ore).orElse(ore))
                .distinct()
                .sorted(Comparator.comparing(ore -> BuiltInRegistries.BLOCK.getKey(ore).toString()))
                .toList();
    }

    private List<OreConfiguration.TargetBlockState> targetsFor(Block ore) {
        List<OreConfiguration.TargetBlockState> result = new ArrayList<>();
        for (String selector : replaceables) {
            RuleTest test = ruleTest(selector);
            if (test != null) {
                result.add(OreConfiguration.target(test, ore.defaultBlockState()));
            }
        }
        return List.copyOf(result);
    }

    /** Targets whose host is installed by the cave mod after the base noise terrain is sampled. */
    static List<OreConfiguration.TargetBlockState> lateReplacementTargets(
            ServerLevel level, OreDepositFeature.Configuration config) {
        if (config.dataDriven().isEmpty()
                || config.sourceBiome().isEmpty()
                || config.targets().isEmpty()) return List.of();
        ResourceLocation biome = config.sourceBiome().get();
        if (biome.getNamespace().equals("minecraft")) return List.of();
        long salt = config.dataDriven().get().ruleSalt();
        for (var rule : loadedRules(level)) {
            if (rule.stableSignature() != salt) continue;
            List<OreConfiguration.TargetBlockState> result = new ArrayList<>();
            RandomSource random = RandomSource.create(0);
            for (String selector : rule.replaceables()) {
                if (!isLateReplacement(biome, selector)) continue;
                ResourceLocation id = ResourceLocation.tryParse(selector);
                if (!BuiltInRegistries.BLOCK.containsKey(id)) continue;
                BlockState host = BuiltInRegistries.BLOCK.get(id).defaultBlockState();
                for (var target : config.targets())
                    if (target.target.test(host, random) && !result.contains(target))
                        result.add(target);
            }
            return List.copyOf(result);
        }
        return List.of();
    }

    private static boolean isLateReplacement(ResourceLocation biome, String selector) {
        if (selector.startsWith("#")) return false;
        ResourceLocation id = ResourceLocation.tryParse(selector);
        return id != null && id.getNamespace().equals(biome.getNamespace());
    }

    private static RuleTest ruleTest(String selector) {
        if (selector.startsWith("#")) {
            ResourceLocation id = ResourceLocation.tryParse(selector.substring(1));
            return id == null ? null : new TagMatchTest(TagKey.create(Registries.BLOCK, id));
        }
        ResourceLocation id = ResourceLocation.tryParse(selector);
        if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) {
            return null;
        }
        return new BlockMatchTest(BuiltInRegistries.BLOCK.get(id));
    }

    private static boolean matchesBlock(BlockState state, List<String> selectors) {
        ResourceLocation blockId = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        for (String selector : selectors) {
            if (selector.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(selector.substring(1));
                if (tagId != null && state.is(TagKey.create(Registries.BLOCK, tagId))) {
                    return true;
                }
            } else if (blockId != null && selector.equals(blockId.toString())) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesId(ResourceLocation id, List<String> selectors) {
        return selectors.stream()
                .anyMatch(selector -> !selector.startsWith("#") && selector.equals(id.toString()));
    }

    private static <T> boolean matchesHolder(
            Holder<T> holder,
            List<String> selectors,
            net.minecraft.resources.ResourceKey<? extends net.minecraft.core.Registry<T>>
                    registry) {
        ResourceLocation id = holder.unwrapKey().map(key -> key.location()).orElse(null);
        for (String selector : selectors) {
            if (selector.startsWith("#")) {
                ResourceLocation tagId = ResourceLocation.tryParse(selector.substring(1));
                if (tagId != null && holder.is(TagKey.create(registry, tagId))) {
                    return true;
                }
            } else if (id != null && selector.equals(id.toString())) {
                return true;
            }
        }
        return false;
    }

    public record TierSettings(
            double frequency,
            boolean forced,
            int count,
            OreDepositFeature.PlacementMode placement,
            float sizeMultiplier,
            float richnessMultiplier) {
        static final Codec<TierSettings> CODEC =
                RecordCodecBuilder.create(
                        instance ->
                                instance.group(
                                                Codec.DOUBLE
                                                        .optionalFieldOf("frequency", 0.0D)
                                                        .forGetter(TierSettings::frequency),
                                                Codec.BOOL
                                                        .optionalFieldOf("forced", false)
                                                        .forGetter(TierSettings::forced),
                                                Codec.INT
                                                        .optionalFieldOf("count", 1)
                                                        .forGetter(TierSettings::count),
                                                OreDepositFeature.PlacementMode.CODEC
                                                        .optionalFieldOf(
                                                                "placement",
                                                                OreDepositFeature.PlacementMode
                                                                        .DEFAULT)
                                                        .forGetter(TierSettings::placement),
                                                Codec.FLOAT
                                                        .optionalFieldOf("size_multiplier", 1.0F)
                                                        .forGetter(TierSettings::sizeMultiplier),
                                                Codec.FLOAT
                                                        .optionalFieldOf(
                                                                "richness_multiplier", 1.0F)
                                                        .forGetter(
                                                                TierSettings::richnessMultiplier))
                                        .apply(instance, TierSettings::new));

        public TierSettings {
            if (!Double.isFinite(frequency) || frequency < 0.0D || frequency > 64.0D) {
                throw new IllegalArgumentException(
                        "frequency must be between 0 and 64 candidates per cell");
            }
            if (count < 1 || count > 64) {
                throw new IllegalArgumentException(
                        "count must be between 1 and 64 forced candidates per cell");
            }
            if (sizeMultiplier <= 0.0F || richnessMultiplier <= 0.0F) {
                throw new IllegalArgumentException(
                        "size_multiplier and richness_multiplier must be positive");
            }
        }

        int candidateSlots() {
            return forced ? count : (int) Math.ceil(frequency);
        }

        double chanceForSlot(int slot) {
            return forced ? 1.0D : Math.min(1.0D, frequency - slot);
        }
    }

    private record RuleSession(
            ModifiableBiomeInfo.BiomeInfo.Builder builder,
            List<DataDrivenOreDepositBiomeModifier> rules) {}

    record PlannedDeposit(
            DepositCandidate candidate,
            ResourceLocation biomeId,
            List<String> replaceables,
            OreDepositFeature.PlacementMode placement,
            boolean forced) {
        PlannedDeposit {
            replaceables = List.copyOf(replaceables);
        }

        boolean matchesReplacement(BlockState state) {
            return state.getFluidState().isEmpty() && matchesBlock(state, replaceables);
        }

        /**
         * Remote base columns predate terrain conversions performed while an optional biome is
         * installed into a chunk. If that mod owns both the biome and a configured replacement
         * block, tagged base stone is the deterministic precursor. Real generation still calls
         * {@link #matchesReplacement(BlockState)} against the finished chunk and remains strict.
         */
        java.util.function.BiPredicate<BlockPos, BlockState> predictedReplacement(
                ServerLevel level) {
            boolean ownsLateReplacement =
                    replaceables.stream()
                            .anyMatch(selector -> isLateReplacement(biomeId, selector));
            return (pos, state) ->
                    matchesReplacement(state)
                            || ownsLateReplacement
                                    && state.getFluidState().isEmpty()
                                    && state.is(ModBlockTags.STONES)
                                    && DataDrivenOreDepositBiomeModifier.matchesPlannedBiome(
                                            level, pos, biomeId);
        }
    }
}

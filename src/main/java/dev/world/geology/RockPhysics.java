package dev.world.geology;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.OresAndDrillsMod;
import dev.registry.ModBlockTags;
import dev.world.geology.ore.OreSignatureTable;
import dev.world.level.levelgen.OreGenerationWeights;
import dev.world.level.levelgen.OreTags;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.AddReloadListenerEvent;
import net.neoforged.neoforge.event.TagsUpdatedEvent;

/**
 * Elastic properties of any block, extensible by datapacks and other mods. Lookup order: datapack
 * block entries, datapack tag entries (later files win), properties other mods registered through
 * {@link #register}, the built-in {@link RockCatalog}, the conditional mineral {@link
 * OreSignatureTable} derives for an ore, then broad tag heuristics.
 *
 * <p>Datapack format, {@code data/<namespace>/rock_physics/<name>.json}:
 *
 * <pre>{@code
 * {"entries": [{"targets": ["create:limestone", "#c:ores/uranium"],
 *               "density": 2.7, "velocity": 6000, "quality": 120}]}
 * }</pre>
 *
 * Lookups read block tags, so they run on the server thread; survey workers receive the resolved
 * values.
 */
public final class RockPhysics {
    public static final String DIRECTORY = "rock_physics";

    private static final Map<ResourceLocation, RockProperties> REGISTERED =
            new ConcurrentHashMap<>();
    private static final Map<Block, RockProperties> CACHE = new ConcurrentHashMap<>();
    private static volatile Map<ResourceLocation, RockProperties> datapackBlocks = Map.of();
    private static volatile List<TagRule> datapackTags = List.of();
    private static volatile int cachedEpoch = -1;

    private RockPhysics() {}

    /**
     * Lets another mod describe its rock or ore. Datapack entries still override it; call during
     * mod initialisation.
     */
    public static void register(ResourceLocation blockId, RockProperties properties) {
        REGISTERED.put(blockId, properties);
        clearCaches();
    }

    public static RockProperties forBlock(BlockState state) {
        if (state.getBlock() instanceof LiquidBlock) {
            return state.getFluidState().is(FluidTags.LAVA) ? RockCatalog.LAVA : RockCatalog.WATER;
        }
        if (state.isAir()) {
            return RockCatalog.AIR;
        }
        return forBlock(state.getBlock());
    }

    public static RockProperties forBlock(Block block) {
        // Ore minerals follow the recorded generation data, which can change after start-up.
        int epoch = OreGenerationWeights.dataEpoch();
        if (epoch != cachedEpoch) {
            CACHE.clear();
            cachedEpoch = epoch;
        }
        return CACHE.computeIfAbsent(block, RockPhysics::resolve);
    }

    /** Properties of the ore mineral of {@code oreId}, as the survey mixes it into host rock. */
    public static RockProperties forOre(ResourceLocation oreId) {
        Block block = BuiltInRegistries.BLOCK.getOptional(oreId).orElse(null);
        if (block == null) {
            return OreSignatureTable.neutral();
        }
        return forBlock(block);
    }

    /** Properties a datapack or another mod set for this block explicitly, if any. */
    public static Optional<RockProperties> explicitFor(BlockState state) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        RockProperties explicit = datapackBlocks.get(id);
        if (explicit != null) {
            return Optional.of(explicit);
        }
        List<TagRule> rules = datapackTags;
        for (int i = rules.size() - 1; i >= 0; i--) {
            if (state.is(rules.get(i).tag())) {
                return Optional.of(rules.get(i).properties());
            }
        }
        return Optional.ofNullable(REGISTERED.get(id));
    }

    private static RockProperties resolve(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        BlockState state = block.defaultBlockState();
        Optional<RockProperties> explicit = explicitFor(state);
        if (explicit.isPresent()) {
            return explicit.get();
        }
        RockProperties catalog = RockCatalog.block(id.toString()).orElse(null);
        if (catalog != null) {
            return catalog;
        }
        if (OreTags.isOre(state)) {
            return OreSignatureTable.mineral(block);
        }
        if (state.is(BlockTags.BASE_STONE_NETHER)) {
            return RockCatalog.NETHERRACK;
        }
        if (state.is(BlockTags.DIRT) || state.is(BlockTags.SAND)) {
            return RockCatalog.SOIL;
        }
        if (state.is(BlockTags.DEEPSLATE_ORE_REPLACEABLES)) {
            return RockCatalog.DEEPSLATE;
        }
        if (state.is(ModBlockTags.STONES) || state.is(BlockTags.BASE_STONE_OVERWORLD)) {
            return RockCatalog.STONE;
        }
        String path = id.getPath();
        if (path.contains("deepslate")) {
            return RockCatalog.DEEPSLATE;
        }
        return RockCatalog.STONE;
    }

    public static void onAddReloadListeners(AddReloadListenerEvent event) {
        event.addListener(new Loader());
    }

    public static void onTagsUpdated(TagsUpdatedEvent event) {
        clearCaches();
    }

    private static void clearCaches() {
        CACHE.clear();
        OreSignatureTable.invalidate();
    }

    static void apply(Map<ResourceLocation, JsonElement> files) {
        Map<ResourceLocation, RockProperties> blocks = new LinkedHashMap<>();
        List<TagRule> tags = new ArrayList<>();
        files.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(
                        file -> {
                            try {
                                parse(file.getValue(), blocks, tags);
                            } catch (RuntimeException invalid) {
                                OresAndDrillsMod.LOGGER.warn(
                                        "Ignoring invalid rock physics file {}: {}",
                                        file.getKey(),
                                        invalid.getMessage());
                            }
                        });
        datapackBlocks = Map.copyOf(blocks);
        datapackTags = List.copyOf(tags);
        clearCaches();
    }

    private static void parse(
            JsonElement element, Map<ResourceLocation, RockProperties> blocks, List<TagRule> tags) {
        JsonObject root = GsonHelper.convertToJsonObject(element, "rock physics file");
        JsonArray entries = GsonHelper.getAsJsonArray(root, "entries");
        for (JsonElement entryElement : entries) {
            JsonObject entry = GsonHelper.convertToJsonObject(entryElement, "entry");
            RockProperties properties =
                    new RockProperties(
                            GsonHelper.getAsDouble(entry, "density"),
                            GsonHelper.getAsDouble(entry, "velocity"),
                            GsonHelper.getAsDouble(entry, "quality", 100.0D));
            for (JsonElement targetElement : GsonHelper.getAsJsonArray(entry, "targets")) {
                String target = GsonHelper.convertToString(targetElement, "target");
                if (target.startsWith("#")) {
                    ResourceLocation tag = ResourceLocation.parse(target.substring(1));
                    tags.add(new TagRule(TagKey.create(Registries.BLOCK, tag), properties));
                } else {
                    blocks.put(ResourceLocation.parse(target), properties);
                }
            }
        }
    }

    private record TagRule(TagKey<Block> tag, RockProperties properties) {}

    private static final class Loader extends SimpleJsonResourceReloadListener {
        private static final Gson GSON = new GsonBuilder().create();

        private Loader() {
            super(GSON, DIRECTORY);
        }

        @Override
        protected void apply(
                Map<ResourceLocation, JsonElement> files,
                ResourceManager resourceManager,
                ProfilerFiller profiler) {
            RockPhysics.apply(files);
        }
    }
}
